package io.github.bigsprut.taskman.data.repo

import io.github.bigsprut.taskman.data.local.entity.PendingMutationEntity
import io.github.bigsprut.taskman.data.local.entity.TaskEntity
import io.github.bigsprut.taskman.data.remote.ApiFailure
import io.github.bigsprut.taskman.data.remote.TaskmanApi
import io.github.bigsprut.taskman.data.remote.TaskmanJson
import io.github.bigsprut.taskman.data.remote.dto.TaskInDto
import io.github.bigsprut.taskman.domain.TaskDraft
import io.github.bigsprut.taskman.domain.TaskStatus
import java.io.IOException
import kotlinx.coroutines.CancellationException
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

    /** Сохранение черновика редактора (Ф2): локально сразу, сервер — по возможности. */
    suspend fun saveDraft(draft: TaskDraft): MutationOutcome {
        val existing = reader.taskEntity(draft.id)
        val payload = TaskPayload.fromDraft(draft)
        val optimistic = draft.toEntity(existing)
        return submit(payload = payload, taskId = draft.id) { writer.applyUpsert(optimistic) }
    }

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
     * @return `Success` — очередь пуста; `Queued` — сеть пропала, остаток ждёт;
     *   `Conflict` — 409 (запись снята, состояние перечитано); `Failed` — сервер отказал.
     */
    suspend fun flush(): MutationOutcome {
        val pending = queue.pending()
        if (pending.isEmpty()) return MutationOutcome.Success

        for (record in pending) {
            val response: Response<*> = try {
                when (record.kind) {
                    PendingMutationEntity.KIND_UPSERT -> {
                        val payload = record.payloadJson
                            ?.let { TaskmanJson.decodeFromString<TaskInDto>(it) }
                        if (payload == null) {
                            queue.remove(record.taskId)
                            continue
                        }
                        api.upsertTasks(listOf(payload))
                    }

                    else -> api.deleteTask(record.taskId)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (io: IOException) {
                // Сеть пропала: остаток очереди ждёт следующего раза (§5.3-Ф4).
                return MutationOutcome.Queued
            } catch (error: Exception) {
                return MutationOutcome.Failed(ApiFailure.Internal(error))
            }

            if (response.isSuccessful) {
                queue.remove(record.taskId)
                continue
            }

            when (val failure = ApiFailure.fromHttpResponse(response.code(), response.errorBody())) {
                ApiFailure.Unauthorized, ApiFailure.DeviceNotApproved -> {
                    // Навигацию уведут события сессии; запись оставляем — отправим после
                    // подтверждения устройства/входа.
                    tasks.reportAuthFailure(failure)
                    return MutationOutcome.Failed(failure)
                }

                is ApiFailure.Conflict -> {
                    queue.remove(record.taskId)
                    tasks.refresh()
                    return MutationOutcome.Conflict(failure.actualUpdatedAt)
                }

                else -> {
                    // Сервер-отказ не копится: снимаем запись и показываем точную причину.
                    queue.remove(record.taskId)
                    tasks.refresh()
                    return MutationOutcome.Failed(failure)
                }
            }
        }

        tasks.refresh()
        return MutationOutcome.Success
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
    }
}
