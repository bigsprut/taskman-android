package io.github.bigsprut.taskman.data.repo

import io.github.bigsprut.taskman.data.remote.ApiFailure
import io.github.bigsprut.taskman.data.remote.TaskmanApi
import io.github.bigsprut.taskman.domain.TaskDraft
import io.github.bigsprut.taskman.domain.TaskStatus
import java.io.IOException
import kotlinx.coroutines.CancellationException
import retrofit2.Response

/** Итог одной мутации задачи. */
sealed interface MutationOutcome {

    data object Success : MutationOutcome

    /**
     * 409: задачу успели изменить другим клиентом (CAS по `updated_at`, §4).
     * Состояние уже перечитано с сервера — показываем конфликт и не перезаписываем молча.
     */
    class Conflict(val actualUpdatedAt: Double?) : MutationOutcome

    /** Любой другой сбой: сеть, 4xx/5xx, внутренняя ошибка. */
    class Failed(val failure: ApiFailure) : MutationOutcome
}

/**
 * Мутации задач: чекбокс «Выполнена ↔ Создана» и смахивание-удаление (§5.3-Ф1).
 *
 * Принципы:
 * - после успешной мутации состояние перечитывается с сервера ([TaskRepository.refresh]):
 *   даты и историю статусов считает сервер, локальная догадка разошлась бы с ним;
 * - 409 не «лечим» повтором — перечитываем состояние и отдаём конфликт в UI (§4);
 * - 401/403 сообщаются сессии через [TaskRepository.authFailures], чтобы приложение увело
 *   пользователя на вход или на экран ожидания устройства.
 */
class TaskMutationRepository(
    private val api: TaskmanApi,
    private val reader: TaskSnapshotReader,
    private val tasks: TaskRepository,
) {

    /** Чекбокс: переводит задачу в `completed` или обратно в `created` (ТЗ §5.3-Ф1). */
    suspend fun setCompleted(taskId: String, completed: Boolean): MutationOutcome {
        val entity = reader.taskEntity(taskId)
            ?: return MutationOutcome.Failed(
                ApiFailure.Internal(IllegalStateException("task $taskId is absent in local snapshot")),
            )
        val tagIds = reader.tagIds(taskId)
        val target = if (completed) TaskStatus.COMPLETED else TaskStatus.CREATED
        val payload = TaskPayload.forStatusToggle(entity, tagIds, target)
        return execute { api.upsertTasks(listOf(payload)) }
    }

    /** Смахивание-удаление: сервер удаляет задачу вместе с подзадачами (§4). */
    suspend fun delete(taskId: String): MutationOutcome = execute { api.deleteTask(taskId) }

    /**
     * Сохранение черновика редактора (Ф2): UPSERT полного объекта с CAS.
     *
     * Вызывается при закрытии листа **любым** путём (§5.3-Ф2), поэтому метод не зависит от
     * того, чем закрыли: бэкенд/жест/кнопка — все приходят сюда.
     */
    suspend fun saveDraft(draft: TaskDraft): MutationOutcome =
        execute { api.upsertTasks(listOf(TaskPayload.fromDraft(draft))) }

    /**
     * Новая подзадача: `list_id` и `parent_id` берутся из локальной записи родителя,
     * `id = null` — сервер выдаст свой.
     */
    suspend fun addSubtask(parentId: String, title: String): MutationOutcome {
        val parent = reader.taskEntity(parentId)
            ?: return MutationOutcome.Failed(
                ApiFailure.Internal(IllegalStateException("parent $parentId is absent in local snapshot")),
            )
        return execute {
            api.upsertTasks(listOf(TaskPayload.newSubtask(parent.listId, parentId, title)))
        }
    }

    /** Переименование подзадачи (инлайн в редакторе): полный payload с новым названием. */
    suspend fun renameSubtask(taskId: String, title: String): MutationOutcome {
        val entity = reader.taskEntity(taskId)
            ?: return MutationOutcome.Failed(
                ApiFailure.Internal(IllegalStateException("task $taskId is absent in local snapshot")),
            )
        val tagIds = reader.tagIds(taskId)
        return execute {
            api.upsertTasks(listOf(TaskPayload.fromEntity(entity, tagIds, title = title)))
        }
    }

    private suspend fun execute(mutation: suspend () -> Response<*>): MutationOutcome = try {
        val response = mutation()
        if (response.isSuccessful) {
            // Сервер — источник правды: забираем актуальные даты, историю и updated_at.
            tasks.refresh()
            MutationOutcome.Success
        } else {
            when (val failure = ApiFailure.fromHttpResponse(response.code(), response.errorBody())) {
                is ApiFailure.Conflict -> {
                    // Рефетч обязателен: без него UI показывал бы устаревшую версию (§4).
                    tasks.refresh()
                    MutationOutcome.Conflict(failure.actualUpdatedAt)
                }

                ApiFailure.Unauthorized, ApiFailure.DeviceNotApproved -> {
                    tasks.reportAuthFailure(failure)
                    MutationOutcome.Failed(failure)
                }

                else -> MutationOutcome.Failed(failure)
            }
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (io: IOException) {
        MutationOutcome.Failed(ApiFailure.Network(io))
    } catch (error: Exception) {
        MutationOutcome.Failed(ApiFailure.Internal(error))
    }
}
