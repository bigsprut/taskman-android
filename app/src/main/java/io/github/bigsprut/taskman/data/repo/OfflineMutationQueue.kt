package io.github.bigsprut.taskman.data.repo

import io.github.bigsprut.taskman.data.local.PendingMutationDao
import io.github.bigsprut.taskman.data.local.entity.PendingMutationEntity
import io.github.bigsprut.taskman.data.remote.TaskmanJson
import io.github.bigsprut.taskman.data.remote.dto.TaskInDto
import kotlinx.coroutines.flow.Flow

/**
 * Очередь неотправленных мутаций задач (Ф4, §5.3).
 *
 * Одна запись на задачу: повторные правки схлопываются, поэтому очередь не растёт от каждого
 * нажатия чекбокса, а сервер получает последнюю полную версию объекта.
 *
 * CAS-версия (`updated_at`) фиксируется при **первой** правке задачи и ею же заполняется payload:
 * после успешной отправки сервер поднимет версию, и новая отправка с «версией из каждой правки»
 * получила бы ложный 409.
 */
class OfflineMutationQueue(
    private val dao: PendingMutationDao,
) {

    fun observePendingCount(): Flow<Int> = dao.observeCount()

    /**
     * Ставит upsert задачи в очередь (схлопывая с прежней записью).
     *
     * @param payload полный объект задачи; `payload.updatedAt` — версия для CAS.
     */
    suspend fun enqueueUpsert(payload: TaskInDto, nowSeconds: Double) {
        val taskId = payload.id ?: return
        val existing = dao.find(taskId)
        val baseUpdatedAt = existing?.baseUpdatedAt ?: payload.updatedAt
        val stored = payload.copy(updatedAt = baseUpdatedAt)
        dao.upsert(
            PendingMutationEntity(
                taskId = taskId,
                kind = PendingMutationEntity.KIND_UPSERT,
                payloadJson = TaskmanJson.encodeToString(stored),
                baseUpdatedAt = baseUpdatedAt,
                createdAt = existing?.createdAt ?: nowSeconds,
            ),
        )
    }

    /** Ставит удаление задачи: upsert-запись для неё больше не нужна. */
    suspend fun enqueueDelete(taskId: String, nowSeconds: Double) {
        val existing = dao.find(taskId)
        dao.upsert(
            PendingMutationEntity(
                taskId = taskId,
                kind = PendingMutationEntity.KIND_DELETE,
                payloadJson = null,
                baseUpdatedAt = existing?.baseUpdatedAt,
                createdAt = existing?.createdAt ?: nowSeconds,
            ),
        )
    }

    suspend fun pending(): List<PendingMutationEntity> = dao.pending()

    suspend fun remove(taskId: String) = dao.remove(taskId)

    /** Очередь чистится вместе с кэшем при выходе/смене сервера. */
    suspend fun clear() = dao.clear()
}
