package io.github.bigsprut.taskman.data.repo

import androidx.room.withTransaction
import io.github.bigsprut.taskman.data.local.TaskmanDatabase
import io.github.bigsprut.taskman.data.local.entity.ServerSettingsEntity
import io.github.bigsprut.taskman.data.local.entity.TaskWithCategory
import io.github.bigsprut.taskman.domain.Task
import io.github.bigsprut.taskman.domain.TaskStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * [StateStore] поверх Room.
 *
 * Замена снапшота — одна транзакция: иначе при ошибке посреди записи список показал бы
 * смесь старого и нового состояния сервера.
 */
class DatabaseStateStore(
    private val database: TaskmanDatabase,
) : StateStore {

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

    override fun observeTasks(): Flow<List<Task>> =
        database.stateDao().observeTasks().map { rows -> rows.map(TaskWithCategory::toDomain) }

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
}

/** Строка списка → доменная задача. */
internal fun TaskWithCategory.toDomain(): Task = Task(
    id = id,
    title = title,
    status = TaskStatus.fromWireValue(status),
    statusDateTs = statusDateTs,
    parentId = parentId,
    categoryTitle = categoryTitle?.takeIf { it.isNotBlank() },
    categoryColor = categoryColor,
    source = source?.takeIf { it.isNotBlank() },
)
