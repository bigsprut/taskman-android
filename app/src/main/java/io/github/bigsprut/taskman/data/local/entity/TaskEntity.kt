package io.github.bigsprut.taskman.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Локализованный снапшот задачи: состояние сервера, пригодное для чтения офлайн (Ф0). */
@Entity(
    tableName = "tasks",
    indices = [
        Index("parentId"),
        Index("categoryId"),
        Index("status"),
    ],
)
data class TaskEntity(
    @PrimaryKey val id: String,
    val listId: String,
    val parentId: String?,
    val categoryId: String?,
    val title: String,
    val content: String,
    /** Как пришло с сервера (`created`/`inprogress`/…); разбор — в [io.github.bigsprut.taskman.domain.TaskStatus]. */
    val status: String,
    val source: String?,
    val createdAt: Double?,
    val updatedAt: Double?,
    val completedAt: Double?,
    /**
     * Производное поле: unix-секунды последней смены статуса.
     * Считается при маппинге и хранится, чтобы сортировка не ходила в подзапросы.
     */
    val statusDateTs: Double,
    val deleted: Boolean,
)

@Entity(tableName = "task_lists")
data class TaskListEntity(
    @PrimaryKey val id: String,
    val title: String,
    val position: Int,
)

@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey val id: String,
    val title: String,
    val color: String?,
    val position: Int,
)

@Entity(tableName = "tags")
data class TagEntity(
    @PrimaryKey val id: String,
    val title: String,
    val color: String?,
    val position: Int,
)

@Entity(tableName = "employees")
data class EmployeeEntity(
    @PrimaryKey val id: String,
    val name: String,
)

/** Единственная строка (id = 1): глобальные настройки инстанса с сервера. */
@Entity(tableName = "server_settings")
data class ServerSettingsEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val defaultCategoryId: String?,
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}
