package io.github.bigsprut.taskman.data.repo

import io.github.bigsprut.taskman.data.local.entity.AttachmentEntity
import io.github.bigsprut.taskman.data.local.entity.CategoryEntity
import io.github.bigsprut.taskman.data.local.entity.EmployeeEntity
import io.github.bigsprut.taskman.data.local.entity.TagEntity
import io.github.bigsprut.taskman.data.local.entity.TaskEntity
import io.github.bigsprut.taskman.data.local.entity.TaskListEntity
import io.github.bigsprut.taskman.data.local.entity.TaskStatusHistoryEntity
import io.github.bigsprut.taskman.data.local.entity.TaskTagEntity
import io.github.bigsprut.taskman.domain.Category
import io.github.bigsprut.taskman.domain.Employee
import io.github.bigsprut.taskman.domain.Tag
import io.github.bigsprut.taskman.domain.Task
import io.github.bigsprut.taskman.domain.TaskHistoryEntry
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

/** Полная запись задачи для редактора: сущность + её теги (payload собирается из них, §4). */
data class TaskDraftSnapshot(
    val entity: TaskEntity,
    val tagIds: List<String>,
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

    /** Каталог тегов для чипов фильтра и бейджей. */
    fun observeTags(): Flow<List<Tag>>

    /** Каталог категорий для чипов фильтра и редактора. */
    fun observeCategories(): Flow<List<Category>>

    /** Справочник сотрудников — поле «сотрудник» в редакторе (§5.3-Ф2). */
    fun observeEmployees(): Flow<List<Employee>>

    /** Категория по умолчанию из `/api/state.settings` (§5.3-Ф5). */
    fun observeDefaultCategoryId(): Flow<String?>

    /** Полный снапшот для экспорта в markdown (§5.3-Ф5). */
    suspend fun loadExportSnapshot(): io.github.bigsprut.taskman.data.export.ExportSnapshot

    /** История статусов задачи для блока «История» в редакторе. */
    fun observeTaskHistory(taskId: String): Flow<List<TaskHistoryEntry>>

    /** Полная запись задачи для открытия редактора. */
    suspend fun loadTaskDraft(taskId: String): TaskDraftSnapshot?

    /** Локальный кэш задач при выходе/смене сервера. */
    suspend fun clear()
}
