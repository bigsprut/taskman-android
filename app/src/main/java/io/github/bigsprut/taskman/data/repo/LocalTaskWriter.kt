package io.github.bigsprut.taskman.data.repo

import io.github.bigsprut.taskman.data.local.entity.TaskEntity
import io.github.bigsprut.taskman.data.local.TaskmanDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Локальное (оптимистичное) применение правок к Room — основа offline-first (§5.3-Ф4).
 *
 * Интерфейс нужен, чтобы репозиторий мутаций тестировался на подставном писателе:
 * правила очереди проверяются без Android/Room.
 */
interface LocalTaskWriter {
    suspend fun applyUpsert(entity: TaskEntity)
    suspend fun applyDelete(taskId: String)

    /**
     * Обновляет локальную версию (`updated_at`) из ответа сервера после собственной мутации
     * (Ф7). По умолчанию — no-op: тестовые писатели могут не вести версии, а боевой
     * `DatabaseLocalTaskWriter` пишет её в Room.
     */
    suspend fun applyServerVersion(taskId: String, updatedAt: Double) = Unit
}

/** Реализация поверх Room: правка полей задачи или soft-delete поддерева. */
class DatabaseLocalTaskWriter(
    private val database: TaskmanDatabase,
) : LocalTaskWriter {

    override suspend fun applyUpsert(entity: TaskEntity) = withContext(Dispatchers.IO) {
        database.stateDao().upsertTask(entity)
    }

    override suspend fun applyDelete(taskId: String) = withContext(Dispatchers.IO) {
        database.stateDao().markDeletedSubtree(taskId)
    }

    override suspend fun applyServerVersion(taskId: String, updatedAt: Double) =
        withContext(Dispatchers.IO) {
            database.stateDao().updateTaskUpdatedAt(taskId, updatedAt)
        }
}
