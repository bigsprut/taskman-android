package io.github.bigsprut.taskman.data.repo

import io.github.bigsprut.taskman.data.local.entity.AttachmentEntity
import io.github.bigsprut.taskman.data.local.entity.CategoryEntity
import io.github.bigsprut.taskman.data.local.entity.EmployeeEntity
import io.github.bigsprut.taskman.data.local.entity.TagEntity
import io.github.bigsprut.taskman.data.local.entity.TaskEntity
import io.github.bigsprut.taskman.data.local.entity.TaskListEntity
import io.github.bigsprut.taskman.data.local.entity.TaskStatusHistoryEntity
import io.github.bigsprut.taskman.data.local.entity.TaskTagEntity
import io.github.bigsprut.taskman.domain.Task
import kotlinx.coroutines.flow.Flow

/**
 * Содержимое `/api/state`, приведённое к локальным сущностям.
 *
 * Отдельный тип (а не DTO напрямую) нужен, чтобы маппинг было видно целиком и можно было
 * проверить его обычным JVM-тестом без Android-окружения.
 */
data class StateSnapshot(
    val lists: List<TaskListEntity> = emptyList(),
    val categories: List<CategoryEntity> = emptyList(),
    val employees: List<EmployeeEntity> = emptyList(),
    val tags: List<TagEntity> = emptyList(),
    val tasks: List<TaskEntity> = emptyList(),
    val attachments: List<AttachmentEntity> = emptyList(),
    val taskTags: List<TaskTagEntity> = emptyList(),
    val history: List<TaskStatusHistoryEntity> = emptyList(),
    val defaultCategoryId: String? = null,
)

/**
 * Хранилище снапшота. Интерфейс, чтобы репозиторий не зависел от Room и тестировался
 * на подставном хранилище (в Ф4 та же роль понадобится офлайн-очереди).
 */
interface StateStore {

    /** Полностью заменяет снапшот состояния. Должно быть атомарным. */
    suspend fun replaceSnapshot(snapshot: StateSnapshot)

    /** Задачи для UI в произвольном порядке: сортировкой владеет домен. */
    fun observeTasks(): Flow<List<Task>>

    /** Локальный кэш задач при выходе/смене сервера. */
    suspend fun clear()
}
