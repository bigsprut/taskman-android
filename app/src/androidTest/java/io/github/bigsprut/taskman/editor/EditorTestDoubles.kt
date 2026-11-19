package io.github.bigsprut.taskman.editor

import io.github.bigsprut.taskman.data.local.entity.TaskEntity
import io.github.bigsprut.taskman.data.attachments.AttachmentStore
import io.github.bigsprut.taskman.data.export.ExportSnapshot
import io.github.bigsprut.taskman.data.local.PendingMutationDao
import io.github.bigsprut.taskman.data.local.entity.PendingMutationEntity
import io.github.bigsprut.taskman.data.repo.LocalTaskWriter
import io.github.bigsprut.taskman.data.repo.OfflineMutationQueue
import io.github.bigsprut.taskman.data.attachments.LocalFile
import io.github.bigsprut.taskman.data.attachments.LocalFileHandle
import io.github.bigsprut.taskman.data.attachments.LocalFileSource
import io.github.bigsprut.taskman.data.remote.TaskmanApi
import io.github.bigsprut.taskman.data.remote.dto.AttachmentUploadResponse
import io.github.bigsprut.taskman.data.remote.dto.CategoryInDto
import io.github.bigsprut.taskman.data.remote.dto.CategoryResponse
import io.github.bigsprut.taskman.data.remote.dto.DeleteCatalogResponse
import io.github.bigsprut.taskman.data.remote.dto.SettingsResponse
import io.github.bigsprut.taskman.data.remote.dto.TagInDto
import io.github.bigsprut.taskman.data.remote.dto.TagResponse
import io.github.bigsprut.taskman.data.remote.dto.DeleteAttachmentResponse
import io.github.bigsprut.taskman.data.remote.dto.DeleteEmployeeResponse
import io.github.bigsprut.taskman.data.remote.dto.DeleteTaskResponse
import io.github.bigsprut.taskman.data.remote.dto.EmployeeInDto
import io.github.bigsprut.taskman.data.remote.dto.EmployeeResponse
import io.github.bigsprut.taskman.data.remote.dto.SavedTaskDto
import io.github.bigsprut.taskman.data.remote.dto.StateResponse
import io.github.bigsprut.taskman.data.remote.dto.TaskInDto
import io.github.bigsprut.taskman.data.remote.dto.UpsertTasksResponse
import io.github.bigsprut.taskman.data.repo.StateSnapshot
import io.github.bigsprut.taskman.data.repo.StateStore
import io.github.bigsprut.taskman.data.repo.TaskDraftSnapshot
import io.github.bigsprut.taskman.data.repo.TaskSnapshotReader
import io.github.bigsprut.taskman.domain.Attachment
import io.github.bigsprut.taskman.domain.Category
import io.github.bigsprut.taskman.domain.Employee
import io.github.bigsprut.taskman.domain.Tag
import io.github.bigsprut.taskman.domain.Task
import io.github.bigsprut.taskman.domain.TaskHistoryEntry
import io.github.bigsprut.taskman.domain.TaskStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import retrofit2.Response

/**
 * Дублёры для instrumented-теста редактора.
 *
 * Держим их локально в `androidTest`: JVM-дублёры юнит-тестов лежат в другом source set и
 * недоступны инструментированным тестам.
 */
internal class FakeApi : TaskmanApi {

    var upsertCalls: Int = 0
    var lastUpserted: List<TaskInDto> = emptyList()

    override suspend fun getState(): Response<StateResponse> =
        Response.success(StateResponse(serverTime = SERVER_TIME))

    override suspend fun upsertTasks(tasks: List<TaskInDto>): Response<UpsertTasksResponse> {
        upsertCalls += 1
        lastUpserted = tasks
        val saved = tasks.map { SavedTaskDto(id = it.id ?: "generated", updatedAt = SERVER_TIME) }
        return Response.success(UpsertTasksResponse(saved = saved, serverTime = SERVER_TIME))
    }

    override suspend fun deleteTask(id: String): Response<DeleteTaskResponse> =
        Response.success(DeleteTaskResponse(deleted = id))

    override suspend fun uploadAttachment(
        taskId: String,
        file: okhttp3.MultipartBody.Part,
    ): Response<AttachmentUploadResponse> =
        Response.success(AttachmentUploadResponse(id = "att-1", name = "file", size = 0))

    override suspend fun deleteAttachment(id: String): Response<DeleteAttachmentResponse> =
        Response.success(DeleteAttachmentResponse(deleted = id))

    override suspend fun createEmployee(body: EmployeeInDto): Response<EmployeeResponse> =
        Response.success(EmployeeResponse(id = "emp-1", name = body.name))

    override suspend fun updateEmployee(
        id: String,
        body: EmployeeInDto,
    ): Response<EmployeeResponse> = Response.success(EmployeeResponse(id = id, name = body.name))

    override suspend fun deleteEmployee(id: String): Response<DeleteEmployeeResponse> =
        Response.success(DeleteEmployeeResponse(deleted = id))

    override suspend fun createTag(body: TagInDto): Response<TagResponse> =
        Response.success(TagResponse(id = "tag-1", title = body.title.orEmpty(), color = body.color))

    override suspend fun updateTag(id: String, body: TagInDto): Response<TagResponse> =
        Response.success(TagResponse(id = id, title = body.title.orEmpty(), color = body.color))

    override suspend fun deleteTag(id: String): Response<DeleteCatalogResponse> =
        Response.success(DeleteCatalogResponse(deleted = id))

    override suspend fun createCategory(body: CategoryInDto): Response<CategoryResponse> =
        Response.success(
            CategoryResponse(id = "cat-1", title = body.title.orEmpty(), color = body.color),
        )

    override suspend fun updateCategory(
        id: String,
        body: CategoryInDto,
    ): Response<CategoryResponse> =
        Response.success(CategoryResponse(id = id, title = body.title.orEmpty(), color = body.color))

    override suspend fun deleteCategory(id: String): Response<DeleteCatalogResponse> =
        Response.success(DeleteCatalogResponse(deleted = id))

    override suspend fun updateSettings(body: kotlinx.serialization.json.JsonObject): Response<SettingsResponse> =
        Response.success(SettingsResponse(defaultCategory = null))

    companion object {
        const val SERVER_TIME = 1_765_843_200.0
    }
}

internal class FakeStore : StateStore, TaskSnapshotReader, AttachmentStore {

    val tasksFlow = MutableStateFlow<List<Task>>(emptyList())
    private val entities = mutableMapOf<String, TaskEntity>()
    private val tagsByTask = mutableMapOf<String, List<String>>()

    fun putTask(entity: TaskEntity, tagIds: List<String> = emptyList()) {
        entities[entity.id] = entity
        tagsByTask[entity.id] = tagIds
        tasksFlow.value = tasksFlow.value + Task(
            id = entity.id,
            title = entity.title,
            status = TaskStatus.fromWireValue(entity.status),
            statusDateTs = entity.statusDateTs,
            parentId = entity.parentId,
            categoryId = entity.categoryId,
            categoryTitle = null,
            categoryColor = null,
            source = entity.source,
            tagIds = tagIds,
        )
    }

    override suspend fun replaceSnapshot(snapshot: StateSnapshot) {
        // После мутации в UI-тесте снапшот не важен: проверяем сохранность черновика.
    }

    override fun observeTasks(): Flow<List<Task>> = tasksFlow

    override fun observeTags(): Flow<List<Tag>> = MutableStateFlow(emptyList())

    override fun observeCategories(): Flow<List<Category>> = MutableStateFlow(emptyList())

    override fun observeEmployees(): Flow<List<Employee>> = MutableStateFlow(emptyList())

    override fun observeTaskHistory(taskId: String): Flow<List<TaskHistoryEntry>> =
        MutableStateFlow(emptyList())

    override suspend fun loadTaskDraft(taskId: String): TaskDraftSnapshot? {
        val entity = entities[taskId] ?: return null
        return TaskDraftSnapshot(entity = entity, tagIds = tagsByTask[taskId].orEmpty())
    }

    override fun observeAttachments(taskId: String): Flow<List<Attachment>> =
        MutableStateFlow(emptyList())

    override suspend fun findAttachment(attachmentId: String): Attachment? = null

    override fun observeDefaultCategoryId(): Flow<String?> = MutableStateFlow(null)

    override suspend fun loadExportSnapshot(): ExportSnapshot = ExportSnapshot()

    override suspend fun clear() {
        entities.clear()
        tasksFlow.value = emptyList()
    }

    override suspend fun taskEntity(id: String): TaskEntity? = entities[id]

    override suspend fun tagIds(taskId: String): List<String> = tagsByTask[taskId].orEmpty()
}
/** Пустой источник файлов: в UI-тесте вложения не читаются. */
internal class FakeLocalFileSource : LocalFileSource {
    override suspend fun open(uri: String): LocalFileHandle? = null
}
/** Очередь мутаций в памяти для UI-теста (Ф4). */
internal class FakePendingMutationDao : PendingMutationDao {

    private val records = linkedMapOf<String, PendingMutationEntity>()
    private val count = MutableStateFlow(0)

    override suspend fun upsert(record: PendingMutationEntity) {
        records[record.taskId] = record
        count.value = records.size
    }

    override suspend fun pending(): List<PendingMutationEntity> =
        records.values.sortedBy { it.createdAt }

    override suspend fun find(taskId: String): PendingMutationEntity? = records[taskId]

    override fun observeCount(): Flow<Int> = count

    override suspend fun remove(taskId: String) {
        records.remove(taskId)
        count.value = records.size
    }

    override suspend fun clear() {
        records.clear()
        count.value = 0
    }
}

/** Локальный писатель для UI-теста: запоминает применения, ничего не пишет в Room. */
internal class FakeLocalTaskWriter : LocalTaskWriter {

    var upserts: Int = 0
    var deletes: Int = 0

    override suspend fun applyUpsert(entity: TaskEntity) {
        upserts += 1
    }

    override suspend fun applyDelete(taskId: String) {
        deletes += 1
    }
}