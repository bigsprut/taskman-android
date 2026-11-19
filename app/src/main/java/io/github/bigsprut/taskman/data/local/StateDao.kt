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

    /**
     * Задачи для списка: категория и число неудалённых вложений подтягиваются в том же
     * запросе, чтобы строка не делала N+1 обращений к базе.
     */
    @Query(
        """
        SELECT
            t.id AS id,
            t.title AS title,
            t.status AS status,
            t.statusDateTs AS statusDateTs,
            t.parentId AS parentId,
            t.categoryId AS categoryId,
            t.source AS source,
            c.title AS categoryTitle,
            c.color AS categoryColor,
            (
                SELECT COUNT(*) FROM attachments AS a
                WHERE a.taskId = t.id AND a.deleted = 0
            ) AS attachmentCount
        FROM tasks AS t
        LEFT JOIN categories AS c ON c.id = t.categoryId
        WHERE t.deleted = 0
        """,
    )
    fun observeTasks(): Flow<List<TaskWithCategory>>

    /** Связи «задача ↔ тег» — второй поток, склеивается с задачами в [DatabaseStateStore]. */
    @Query("SELECT * FROM task_tags")
    fun observeTaskTags(): Flow<List<TaskTagEntity>>

    @Query("SELECT COUNT(*) FROM tasks WHERE deleted = 0")
    suspend fun countVisibleTasks(): Int

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun findTask(id: String): TaskEntity?

    @Query("SELECT tagId FROM task_tags WHERE taskId = :taskId")
    suspend fun tagsOf(taskId: String): List<String>

    @Query("SELECT * FROM tasks WHERE parentId = :parentId AND deleted = 0")
    suspend fun childrenOf(parentId: String): List<TaskEntity>

    /** Каталог тегов для чипов фильтра — отдельным потоком, с инвалидацией при замене снапшота. */
    @Query("SELECT * FROM tags ORDER BY position")
    fun observeTagCatalog(): Flow<List<TagEntity>>

    /** Каталог категорий для чипов фильтра. */
    @Query("SELECT * FROM categories ORDER BY position")
    fun observeCategoryCatalog(): Flow<List<CategoryEntity>>

    /** Справочник сотрудников для редактора задачи (§5.3-Ф2). */
    @Query("SELECT * FROM employees ORDER BY name")
    fun observeEmployeeCatalog(): Flow<List<EmployeeEntity>>

    /** Метаданные вложений задачи (Ф3): превью и список в редакторе. */
    @Query("SELECT * FROM attachments WHERE taskId = :taskId AND deleted = 0 ORDER BY createdAt")
    fun observeAttachments(taskId: String): Flow<List<AttachmentEntity>>

    @Query("SELECT * FROM attachments WHERE id = :id")
    suspend fun findAttachment(id: String): AttachmentEntity?

    /** История статусов задачи — блок «История» в редакторе. */
    @Query("SELECT * FROM task_history WHERE taskId = :taskId ORDER BY ts DESC")
    fun observeTaskHistory(taskId: String): Flow<List<TaskStatusHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTasks(tasks: List<TaskEntity>)

    /** Оптимистичная локальная правка одной задачи (Ф4): применяется до ответа сервера. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTask(task: TaskEntity)

    /** Локальное soft-delete задачи вместе с подзадачами (Ф4): как это сделает сервер. */
    @Query("UPDATE tasks SET deleted = 1 WHERE id = :taskId OR parentId = :taskId")
    suspend fun markDeletedSubtree(taskId: String)

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
