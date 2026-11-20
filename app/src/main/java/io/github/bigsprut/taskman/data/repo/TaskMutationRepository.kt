package io.github.bigsprut.taskman.data.repo

import io.github.bigsprut.taskman.data.local.entity.PendingMutationEntity
import io.github.bigsprut.taskman.data.local.entity.TaskEntity
import io.github.bigsprut.taskman.data.remote.ApiFailure
import io.github.bigsprut.taskman.data.remote.TaskmanApi
import io.github.bigsprut.taskman.data.remote.TaskmanJson
import io.github.bigsprut.taskman.data.remote.dto.TaskInDto
import io.github.bigsprut.taskman.data.remote.dto.UpsertTasksResponse
import io.github.bigsprut.taskman.domain.TaskDraft
import io.github.bigsprut.taskman.domain.TaskStatus
import java.io.IOException
import kotlinx.coroutines.CancellationException
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlinx.coroutines.flow.Flow
import retrofit2.Response

/** Итог одной мутации задачи. */
sealed interface MutationOutcome {

    /** Мутация ушла на сервер и подтверждена им. */
    data object Success : MutationOutcome

    /**
     * Сети нет: правка сохранена локально (Room) и поставлена в очередь (Ф4).
     * UI показывает «сохранено локально», а не ошибку.
     */
    data object Queued : MutationOutcome

    /**
     * 409: задачу успели изменить другим клиентом (CAS по `updated_at`, §4).
     * Состояние уже перечитано с сервера — показываем конфликт и не перезаписываем молча.
     */
    class Conflict(val actualUpdatedAt: Double?) : MutationOutcome

    /** Любой другой сбой: 4xx/5xx, внутренняя ошибка. Сервер-отказ в очередь не копится. */
    class Failed(val failure: ApiFailure) : MutationOutcome
}

/**
 * Мутации задач: чекбокс, смахивание-удаление, редактор и подзадачи (Ф1–Ф4).
 *
 * С Ф4 путь такой: правка **сразу** применяется к Room (источник правды для UI), ставится
 * в очередь и отправляется. Если сети нет — остаётся в очереди (`MutationOutcome.Queued`),
 * отправку позже сделает `MutationSyncWorker`. Сервер-отказ (4xx) снимает запись из очереди
 * и отдаётся в UI точной причиной (§5.3-Ф4); 409 — рефетч и явный конфликт.
 *
 * @param now источник времени в миллисекундах (подменяется в тестах).
 * @param requestBackgroundSync страховка: ставит разовую работу, которая дошлёт очередь,
 *   даже если приложение закроют сразу после правки.
 */
class TaskMutationRepository(
    private val api: TaskmanApi,
    private val reader: TaskSnapshotReader,
    private val tasks: TaskRepository,
    private val queue: OfflineMutationQueue,
    private val writer: LocalTaskWriter,
    private val now: () -> Long = System::currentTimeMillis,
    private val requestBackgroundSync: () -> Unit = {},
) {

    /**
     * Версии, подтверждённые сервером на **наши** мутации (Ф7).
     *
     * По ним отличаем «409 после собственного изменения» (можно молча повторить) от чужого
     * конфликта: сервер возвращает `actual_updated_at`, и если это наша последняя подтверждённая
     * версия — задача изменилась именно нами, а не другим устройством.
     */
    private val acknowledgedVersions = ConcurrentHashMap<String, Double>()

    /** Сколько мутаций ждёт отправки — для баннера «есть неотправленные изменения». */
    fun observePendingCount(): Flow<Int> = queue.observePendingCount()

    /**
     * Ручная отправка: кнопка «Отправить сейчас» в баннере (Q18).
     *
     * Отправляем **сразу** в текущем процессе, а не только через WorkManager: уникальная
     * работа с политикой `KEEP` может ждать предыдущую с backoff, и кнопка выглядела бы
     * «мёртвой». Планировщик всё равно дёргаем — как страховку, если сеть пропадёт.
     */
    suspend fun syncNow(): MutationOutcome {
        requestBackgroundSync()
        return flush()
    }

    /** Чекбокс: переводит задачу в `completed` или обратно в `created` (ТЗ §5.3-Ф1). */
    suspend fun setCompleted(taskId: String, completed: Boolean): MutationOutcome {
        val entity = reader.taskEntity(taskId) ?: return absent(taskId)
        val tagIds = reader.tagIds(taskId)
        val target = if (completed) TaskStatus.COMPLETED else TaskStatus.CREATED
        val payload = TaskPayload.forStatusToggle(entity, tagIds, target)
        val optimistic = entity.copy(
            status = target.wireValue,
            statusDateTs = nowSeconds(),
            completedAt = if (completed) nowSeconds() else null,
        )
        return submit(payload = payload, taskId = taskId) { writer.applyUpsert(optimistic) }
    }

    /** Смахивание-удаление: локально скрываем поддерево, затем DELETE на сервер (очередь). */
    suspend fun delete(taskId: String): MutationOutcome =
        submit(payload = null, taskId = taskId) { writer.applyDelete(taskId) }

    /**
     * Сохранение черновика редактора (Ф2): локально сразу, сервер — по возможности.
     *
     * Если поля не изменились, upsert **не отправляется** (Ф7): пустой автосейв при закрытии
     * листа не должен ни гонять сеть, ни создавать повод для конфликта версий.
     */
    suspend fun saveDraft(draft: TaskDraft): MutationOutcome {
        val existing = reader.taskEntity(draft.id)
        val tagIds = reader.tagIds(draft.id)
        val payload = TaskPayload.fromDraft(draft)
        if (existing != null && !isChanged(payload, existing, tagIds)) {
            return MutationOutcome.Success
        }
        val optimistic = draft.toEntity(existing)
        return submit(payload = payload, taskId = draft.id) { writer.applyUpsert(optimistic) }
    }

    /**
     * Локальная версия задачи после последней подтверждённой мутации (для черновика редактора).
     *
     * Сначала смотрим версии, которые сервер вернул в ответ на наши мутации (они самые свежие
     * даже если запись в Room ещё не перечитана), затем — снапшот.
     */
    suspend fun latestVersion(taskId: String): Double? =
        acknowledgedVersions[taskId] ?: reader.taskEntity(taskId)?.updatedAt

    /** Переименование подзадачи: обычный upsert, поэтому тоже умеет копиться в очереди. */
    suspend fun renameSubtask(taskId: String, title: String): MutationOutcome {
        val entity = reader.taskEntity(taskId) ?: return absent(taskId)
        val tagIds = reader.tagIds(taskId)
        val payload = TaskPayload.fromEntity(entity, tagIds, title = title)
        return submit(payload = payload, taskId = taskId) {
            writer.applyUpsert(entity.copy(title = title))
        }
    }

    /**
     * Новая корневая задача из списка (§5.3-Ф2: FAB «+»).
     *
     * `id` выдаёт сервер, поэтому очередь не подходит (как у подзадач): прямой путь, при
     * сетевом сбое — сообщение, запись не копится. Категорию не задаём — сервер применит
     * категорию по умолчанию из настроек (§4).
     */
    suspend fun createTask(title: String): MutationOutcome {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return MutationOutcome.Failed(ApiFailure.InvalidInput)
        val listId = reader.firstListId()
            ?: return MutationOutcome.Failed(
                ApiFailure.Internal(IllegalStateException("no task list in local snapshot")),
            )
        return executeImmediate { api.upsertTasks(listOf(TaskPayload.newTask(listId, trimmed))) }
    }

    /**
     * Новая подзадача: `id` выдаёт сервер, локально его не придумать — поэтому только
     * прямой путь (без очереди). Сетевой сбой — сообщение, запись не копится.
     */
    suspend fun addSubtask(parentId: String, title: String): MutationOutcome {
        val parent = reader.taskEntity(parentId) ?: return absent(parentId)
        return executeImmediate {
            api.upsertTasks(listOf(TaskPayload.newSubtask(parent.listId, parentId, title)))
        }
    }

    /**
     * Отправляет очередь по порядку.
     *
     * Особый случай (Ф7): 409, у которого `actual_updated_at` совпадает с версией, только что
     * подтверждённой сервером на **нашу** мутацию, — это конфликт «сам с собой» (клиент ещё не
     * успел применить новую версию). Такой конфликт молча перечитывается и повторяется один раз;
     * пользователь видит сообщение только при реально чужой версии.
     *
     * @return `Success` — очередь пуста; `Queued` — сеть пропала, остаток ждёт;
     *   `Conflict` — 409 (запись снята, состояние перечитано); `Failed` — сервер отказал.
     */
    suspend fun flush(): MutationOutcome {
        val pending = queue.pending()
        if (pending.isEmpty()) return MutationOutcome.Success

        for (record in pending) {
            val payload = if (record.kind == PendingMutationEntity.KIND_UPSERT) {
                record.payloadJson?.let { TaskmanJson.decodeFromString<TaskInDto>(it) }
            } else {
                null
            }
            if (record.kind == PendingMutationEntity.KIND_UPSERT && payload == null) {
                // Битая запись без payload: снимаем и идём дальше, чтобы очередь не встала.
                queue.remove(record.taskId)
                continue
            }

            when (val result = send(record, payload, updatedAtOverride = null)) {
                is SendResult.Success -> {
                    acceptServerVersions(result.response)
                    queue.remove(record.taskId)
                }

                SendResult.Network -> return MutationOutcome.Queued

                is SendResult.InternalError -> return MutationOutcome.Failed(
                    ApiFailure.Internal(result.cause),
                )

                is SendResult.Failure -> {
                    val failure = result.failure
                    when (failure) {
                        ApiFailure.Unauthorized, ApiFailure.DeviceNotApproved -> {
                            // Навигацию уведут события сессии; запись оставляем — отправим после
                            // подтверждения устройства/входа.
                            tasks.reportAuthFailure(failure)
                            return MutationOutcome.Failed(failure)
                        }

                        is ApiFailure.Conflict -> {
                            val actual = failure.actualUpdatedAt
                            val acknowledged = acknowledgedVersions[record.taskId]
                            val ownVersion = actual != null && acknowledged != null &&
                                sameVersion(actual, acknowledged)

                            if (ownVersion && record.kind == PendingMutationEntity.KIND_UPSERT) {
                                // «Конфликт сам с собой»: перечитываем состояние и повторяем
                                // с фактической версией один раз.
                                tasks.refresh()
                                when (val retry = send(record, payload, actual)) {
                                    is SendResult.Success -> {
                                        acceptServerVersions(retry.response)
                                        queue.remove(record.taskId)
                                    }

                                    SendResult.Network -> return MutationOutcome.Queued

                                    is SendResult.InternalError -> return MutationOutcome.Failed(
                                        ApiFailure.Internal(retry.cause),
                                    )

                                    is SendResult.Failure -> {
                                        queue.remove(record.taskId)
                                        tasks.refresh()
                                        val retryConflict = retry.failure as? ApiFailure.Conflict
                                        return MutationOutcome.Conflict(
                                            retryConflict?.actualUpdatedAt ?: actual,
                                        )
                                    }
                                }
                            } else {
                                queue.remove(record.taskId)
                                tasks.refresh()
                                return MutationOutcome.Conflict(actual)
                            }
                        }

                        else -> {
                            // Сервер-отказ не копится: снимаем запись и показываем точную причину.
                            queue.remove(record.taskId)
                            tasks.refresh()
                            return MutationOutcome.Failed(failure)
                        }
                    }
                }
            }
        }

        tasks.refresh()
        return MutationOutcome.Success
    }

    /**
     * Одна попытка отправки записи очереди.
     *
     * @param updatedAtOverride версия для повторной отправки после «своего» 409.
     */
    private suspend fun send(
        record: PendingMutationEntity,
        payload: TaskInDto?,
        updatedAtOverride: Double?,
    ): SendResult = try {
        val response: Response<*> = when (record.kind) {
            PendingMutationEntity.KIND_UPSERT -> {
                val body = payload ?: return SendResult.InternalError(
                    IllegalStateException("upsert record without payload: ${record.taskId}"),
                )
                api.upsertTasks(listOf(body.copy(updatedAt = updatedAtOverride ?: body.updatedAt)))
            }

            else -> api.deleteTask(record.taskId)
        }
        if (response.isSuccessful) {
            SendResult.Success(response)
        } else {
            SendResult.Failure(ApiFailure.fromHttpResponse(response.code(), response.errorBody()))
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (io: IOException) {
        SendResult.Network
    } catch (error: Exception) {
        SendResult.InternalError(error)
    }

    /**
     * Применяет версии из ответа сервера к локальному снапшоту (Ф7).
     *
     * Без этого следующий upsert уходил бы со старым `updated_at` и получал ложный 409.
     * Для `DELETE` ответ без `saved` — просто ничего не делаем.
     */
    private suspend fun acceptServerVersions(response: Response<*>) {
        val body = response.body() as? UpsertTasksResponse ?: return
        for (saved in body.saved) {
            val version = saved.updatedAt ?: continue
            acknowledgedVersions[saved.id] = version
            writer.applyServerVersion(saved.id, version)
        }
    }

    /**
     * Изменились ли поля черновика относительно локальной записи — для no-op автосейва (Ф7).
     *
     * `updated_at` и «пустую» `status_date` не считаем изменениями: их значения не отражают
     * намерений пользователя, а лишь техническую версию.
     */
    private fun isChanged(payload: TaskInDto, entity: TaskEntity, tagIds: List<String>): Boolean {
        if (payload.title != entity.title) return true
        if (payload.content != entity.content) return true
        if (payload.status != entity.status) return true
        if (payload.categoryId != entity.categoryId) return true
        if (payload.parentId != entity.parentId) return true
        if (payload.source != entity.source) return true
        if (payload.createdAt != entity.createdAt) return true
        if (payload.completedAt != entity.completedAt) return true
        if (payload.tags.sorted() != tagIds.sorted()) return true
        // status_date передаём только при явном выборе; если он совпал с текущим — это не правка.
        if (payload.statusDate != null && !sameVersion(payload.statusDate, entity.statusDateTs)) {
            return true
        }
        return false
    }

    private fun sameVersion(first: Double, second: Double): Boolean = abs(first - second) < VERSION_EPSILON

    /** Результат одной попытки отправки записи очереди. */
    private sealed interface SendResult {
        class Success(val response: Response<*>) : SendResult
        data object Network : SendResult
        class Failure(val failure: ApiFailure) : SendResult
        class InternalError(val cause: Throwable) : SendResult
    }

    private suspend fun submit(
        payload: TaskInDto?,
        taskId: String,
        applyLocal: suspend () -> Unit,
    ): MutationOutcome = try {
        applyLocal()
        val nowSeconds = nowSeconds()
        if (payload != null) {
            queue.enqueueUpsert(payload, nowSeconds)
        } else {
            queue.enqueueDelete(taskId, nowSeconds)
        }
        // Страховка WorkManager'ом: если приложение закроют, очередь дошлёт воркер.
        requestBackgroundSync()
        flush()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        MutationOutcome.Failed(ApiFailure.Internal(error))
    }

    /** Прямая мутация без очереди (только для действий, требующих ответа сервера). */
    private suspend fun executeImmediate(mutation: suspend () -> Response<*>): MutationOutcome = try {
        val response = mutation()
        if (response.isSuccessful) {
            acceptServerVersions(response)
            tasks.refresh()
            MutationOutcome.Success
        } else {
            val failure = ApiFailure.fromHttpResponse(response.code(), response.errorBody())
            if (failure is ApiFailure.Unauthorized || failure is ApiFailure.DeviceNotApproved) {
                tasks.reportAuthFailure(failure)
            }
            MutationOutcome.Failed(failure)
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (io: IOException) {
        MutationOutcome.Failed(ApiFailure.Network(io))
    } catch (error: Exception) {
        MutationOutcome.Failed(ApiFailure.Internal(error))
    }

    private fun absent(taskId: String): MutationOutcome = MutationOutcome.Failed(
        ApiFailure.Internal(IllegalStateException("task $taskId is absent in local snapshot")),
    )

    private fun nowSeconds(): Double = now() / MILLIS_PER_SECOND

    private fun TaskDraft.toEntity(existing: TaskEntity?): TaskEntity = TaskEntity(
        id = id,
        listId = listId,
        parentId = parentId,
        categoryId = categoryId,
        title = title,
        content = content,
        status = status.wireValue,
        source = source,
        createdAt = createdAt ?: existing?.createdAt,
        updatedAt = updatedAt ?: existing?.updatedAt,
        completedAt = if (status == TaskStatus.COMPLETED) {
            statusDateTs ?: completedAt ?: existing?.completedAt
        } else {
            null
        },
        statusDateTs = statusDateTs ?: existing?.statusDateTs ?: nowSeconds(),
        deleted = false,
    )

    private companion object {
        const val MILLIS_PER_SECOND = 1_000.0
        /** Допуск при сравнении unix-версий (дробные секунды из JSON). */
        const val VERSION_EPSILON = 1e-6
    }
}
