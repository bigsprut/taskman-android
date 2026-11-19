package io.github.bigsprut.taskman.data.repo

import androidx.room.withTransaction
import io.github.bigsprut.taskman.data.local.TaskmanDatabase
import io.github.bigsprut.taskman.data.export.ExportSnapshot
import io.github.bigsprut.taskman.data.local.entity.AttachmentEntity
import io.github.bigsprut.taskman.data.local.entity.CategoryEntity
import io.github.bigsprut.taskman.data.local.entity.EmployeeEntity
import io.github.bigsprut.taskman.data.local.entity.ServerSettingsEntity
import io.github.bigsprut.taskman.data.local.entity.TagEntity
import io.github.bigsprut.taskman.data.local.entity.TaskEntity
import io.github.bigsprut.taskman.data.local.entity.TaskWithCategory
import io.github.bigsprut.taskman.domain.Attachment
import io.github.bigsprut.taskman.domain.Category
import io.github.bigsprut.taskman.domain.Employee
import io.github.bigsprut.taskman.domain.Tag
import io.github.bigsprut.taskman.domain.Task
import io.github.bigsprut.taskman.domain.TaskHistoryEntry
import io.github.bigsprut.taskman.domain.TaskStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * [StateStore] поверх Room.
 *
 * Замена снапшота — одна транзакция: иначе при ошибке посреди записи список показал бы
 * смесь старого и нового состояния сервера.
 */
class DatabaseStateStore(
    private val database: TaskmanDatabase,
) : StateStore, TaskSnapshotReader, io.github.bigsprut.taskman.data.attachments.AttachmentStore {

    override suspend fun replaceSnapshot(snapshot: StateSnapshot) {
        database.withTransaction {
            val dao = database.stateDao()

            dao.deleteAllTasks()
            dao.deleteAllLists()
            dao.deleteAllCategories()
            dao.deleteAllTags()
            dao.deleteAllEmployees()
            dao.deleteAllAttachments()
            dao.deleteAllTaskTags()
            dao.deleteAllHistory()

            dao.upsertLists(snapshot.lists)
            dao.upsertCategories(snapshot.categories)
            dao.upsertEmployees(snapshot.employees)
            dao.upsertTags(snapshot.tags)
            dao.upsertTasks(snapshot.tasks)
            dao.upsertAttachments(snapshot.attachments)
            dao.upsertTaskTags(snapshot.taskTags)
            dao.insertHistory(snapshot.history)
            dao.upsertServerSettings(ServerSettingsEntity(defaultCategoryId = snapshot.defaultCategoryId))
        }
    }

    /**
     * Задачи + их теги: два потока Room склеиваются, чтобы доменная модель знала `tagIds`
     * (фильтр по тегам и бейджи, §5.3-Ф1) без запроса на каждую строку.
     */
    override fun observeTasks(): Flow<List<Task>> = combine(
        database.stateDao().observeTasks(),
        database.stateDao().observeTaskTags(),
    ) { rows, links ->
        val tagsByTask: Map<String, List<String>> = links.groupBy({ it.taskId }, { it.tagId })
        rows.map { row -> row.toDomain(tagsByTask[row.id].orEmpty()) }
    }

    override fun observeTags(): Flow<List<Tag>> =
        database.stateDao().observeTagCatalog().map { tags -> tags.map(TagEntity::toDomain) }

    override fun observeCategories(): Flow<List<Category>> =
        database.stateDao().observeCategoryCatalog().map { categories ->
            categories.map(CategoryEntity::toDomain)
        }

    override fun observeEmployees(): Flow<List<Employee>> =
        database.stateDao().observeEmployeeCatalog().map { employees ->
            employees.map(EmployeeEntity::toDomain)
        }

    override fun observeTaskHistory(taskId: String): Flow<List<TaskHistoryEntry>> =
        database.stateDao().observeTaskHistory(taskId).map { entries ->
            entries.map { entry ->
                TaskHistoryEntry(status = TaskStatus.fromWireValue(entry.status), ts = entry.ts)
            }
        }

    override suspend fun loadTaskDraft(taskId: String): TaskDraftSnapshot? {
        val entity = database.stateDao().findTask(taskId) ?: return null
        return TaskDraftSnapshot(entity = entity, tagIds = database.stateDao().tagsOf(taskId))
    }

    override suspend fun clear() {
        database.withTransaction {
            val dao = database.stateDao()
            dao.deleteAllTasks()
            dao.deleteAllLists()
            dao.deleteAllCategories()
            dao.deleteAllTags()
            dao.deleteAllEmployees()
            dao.deleteAllAttachments()
            dao.deleteAllTaskTags()
            dao.deleteAllHistory()
            dao.deleteAllServerSettings()
        }
    }

    override fun observeAttachments(taskId: String): Flow<List<Attachment>> =
        database.stateDao().observeAttachments(taskId).map { attachments ->
            attachments.map(AttachmentEntity::toDomain)
        }

    override fun observeDefaultCategoryId(): Flow<String?> =
        database.stateDao().observeSettings().map { settings -> settings?.defaultCategoryId }

    override suspend fun loadExportSnapshot(): ExportSnapshot {
        val dao = database.stateDao()
        return ExportSnapshot(
            tasks = dao.allTasksForExport(),
            tags = dao.allTagsForExport(),
            categories = dao.allCategoriesForExport(),
            attachments = dao.allAttachmentsForExport(),
            taskTags = dao.allTaskTags(),
        )
    }

    override suspend fun findAttachment(attachmentId: String): Attachment? =
        database.stateDao().findAttachment(attachmentId)?.toDomain()

    override suspend fun taskEntity(id: String): TaskEntity? = database.stateDao().findTask(id)

    override suspend fun tagIds(taskId: String): List<String> = database.stateDao().tagsOf(taskId)
}

/** Строка списка → доменная задача. */
internal fun TaskWithCategory.toDomain(tagIds: List<String>): Task = Task(
    id = id,
    title = title,
    status = TaskStatus.fromWireValue(status),
    statusDateTs = statusDateTs,
    parentId = parentId,
    categoryId = categoryId,
    categoryTitle = categoryTitle?.takeIf { it.isNotBlank() },
    categoryColor = categoryColor,
    source = source?.takeIf { it.isNotBlank() },
    tagIds = tagIds,
    attachmentCount = attachmentCount,
)

internal fun TagEntity.toDomain(): Tag = Tag(id = id, title = title, color = color)

internal fun CategoryEntity.toDomain(): Category = Category(id = id, title = title, color = color)

internal fun EmployeeEntity.toDomain(): Employee = Employee(id = id, name = name)
internal fun AttachmentEntity.toDomain(): Attachment = Attachment(
    id = id,
    taskId = taskId,
    origName = origName,
    mime = mime,
    size = size,
    createdAt = createdAt,
)