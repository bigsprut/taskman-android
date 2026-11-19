package io.github.bigsprut.taskman.editor

import io.github.bigsprut.taskman.data.local.entity.TaskEntity
import io.github.bigsprut.taskman.data.remote.TaskmanApi
import io.github.bigsprut.taskman.data.remote.dto.DeleteTaskResponse
import io.github.bigsprut.taskman.data.remote.dto.SavedTaskDto
import io.github.bigsprut.taskman.data.remote.dto.StateResponse
import io.github.bigsprut.taskman.data.remote.dto.TaskInDto
import io.github.bigsprut.taskman.data.remote.dto.UpsertTasksResponse
import io.github.bigsprut.taskman.data.repo.StateSnapshot
import io.github.bigsprut.taskman.data.repo.StateStore
import io.github.bigsprut.taskman.data.repo.TaskDraftSnapshot
import io.github.bigsprut.taskman.data.repo.TaskSnapshotReader
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

    companion object {
        const val SERVER_TIME = 1_765_843_200.0
    }
}

internal class FakeStore : StateStore, TaskSnapshotReader {

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

    override suspend fun clear() {
        entities.clear()
        tasksFlow.value = emptyList()
    }

    override suspend fun taskEntity(id: String): TaskEntity? = entities[id]

    override suspend fun tagIds(taskId: String): List<String> = tagsByTask[taskId].orEmpty()
}
