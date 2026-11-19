package io.github.bigsprut.taskman.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.github.bigsprut.taskman.data.local.entity.AttachmentEntity
import io.github.bigsprut.taskman.data.local.entity.CategoryEntity
import io.github.bigsprut.taskman.data.local.entity.EmployeeEntity
import io.github.bigsprut.taskman.data.local.entity.ServerSettingsEntity
import io.github.bigsprut.taskman.data.local.entity.TagEntity
import io.github.bigsprut.taskman.data.local.entity.TaskEntity
import io.github.bigsprut.taskman.data.local.entity.TaskListEntity
import io.github.bigsprut.taskman.data.local.entity.TaskStatusHistoryEntity
import io.github.bigsprut.taskman.data.local.entity.TaskTagEntity
import io.github.bigsprut.taskman.data.local.entity.TaskWithCategory
import kotlinx.coroutines.flow.Flow

/**
 * Доступ к локальному снапшоту состояния `/api/state`.
 *
 * Замена снапшота выполняется внутри одной транзакции (см. [DatabaseStateStore]): список
 * не должен на мгновение показать «половину» состояния сервера.
 */
@Dao
interface StateDao {

    @Query(
        """
        SELECT
            t.id AS id,
            t.title AS title,
            t.status AS status,
            t.statusDateTs AS statusDateTs,
            t.parentId AS parentId,
            t.source AS source,
            c.title AS categoryTitle,
            c.color AS categoryColor
        FROM tasks AS t
        LEFT JOIN categories AS c ON c.id = t.categoryId
        WHERE t.deleted = 0
        """,
    )
    fun observeTasks(): Flow<List<TaskWithCategory>>

    @Query("SELECT COUNT(*) FROM tasks WHERE deleted = 0")
    suspend fun countVisibleTasks(): Int

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun findTask(id: String): TaskEntity?

    @Query("SELECT * FROM tasks WHERE parentId = :parentId AND deleted = 0")
    suspend fun childrenOf(parentId: String): List<TaskEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTasks(tasks: List<TaskEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLists(lists: List<TaskListEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCategories(categories: List<CategoryEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTags(tags: List<TagEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEmployees(employees: List<EmployeeEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAttachments(attachments: List<AttachmentEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTaskTags(links: List<TaskTagEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistory(entries: List<TaskStatusHistoryEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertServerSettings(settings: ServerSettingsEntity)

    @Query("SELECT * FROM server_settings WHERE id = ${ServerSettingsEntity.SINGLETON_ID}")
    suspend fun serverSettings(): ServerSettingsEntity?

    @Query("DELETE FROM tasks")
    suspend fun deleteAllTasks()

    @Query("DELETE FROM task_lists")
    suspend fun deleteAllLists()

    @Query("DELETE FROM categories")
    suspend fun deleteAllCategories()

    @Query("DELETE FROM tags")
    suspend fun deleteAllTags()

    @Query("DELETE FROM employees")
    suspend fun deleteAllEmployees()

    @Query("DELETE FROM attachments")
    suspend fun deleteAllAttachments()

    @Query("DELETE FROM task_tags")
    suspend fun deleteAllTaskTags()

    @Query("DELETE FROM task_history")
    suspend fun deleteAllHistory()

    @Query("DELETE FROM server_settings")
    suspend fun deleteAllServerSettings()
}
