package io.github.bigsprut.taskman.testutil

import io.github.bigsprut.taskman.data.remote.TaskmanApi
import io.github.bigsprut.taskman.data.remote.dto.AttachmentDto
import io.github.bigsprut.taskman.data.remote.dto.ServerSettingsDto
import io.github.bigsprut.taskman.data.remote.dto.StateResponse
import io.github.bigsprut.taskman.data.remote.dto.StatusHistoryDto
import io.github.bigsprut.taskman.data.remote.dto.TaskDto
import io.github.bigsprut.taskman.data.repo.StateSnapshot
import io.github.bigsprut.taskman.data.repo.StateStore
import io.github.bigsprut.taskman.domain.Task
import io.github.bigsprut.taskman.domain.TaskStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Response

/**
 * Подставной API: ответ задаётся лямбдой [outcome], число вызовов считается в [calls].
 */
class FakeTaskmanApi : TaskmanApi {

    var outcome: () -> Response<StateResponse> = { successResponse(stateResponse()) }
    var calls: Int = 0

    override suspend fun getState(): Response<StateResponse> {
        calls += 1
        return outcome()
    }
}

/**
 * Подставное хранилище: запоминает снапшоты, а задачи для UI отдаёт из [tasksFlow].
 *
 * Порядок задач не сортируется специально: сортировка — обязанность репозитория.
 */
class FakeStateStore(initialTasks: List<Task> = emptyList()) : StateStore {

    var stored: StateSnapshot? = null
    var clearCalls: Int = 0
    val snapshots = mutableListOf<StateSnapshot>()
    val tasksFlow = MutableStateFlow(initialTasks)

    /** Исключение, которое бросить при записи снапшота (эмуляция сбоя локального хранилища). */
    var failOnReplace: Throwable? = null

    override suspend fun replaceSnapshot(snapshot: StateSnapshot) {
        failOnReplace?.let { throw it }
        stored = snapshot
        snapshots += snapshot
    }

    override fun observeTasks(): Flow<List<Task>> = tasksFlow

    override suspend fun clear() {
        clearCalls += 1
        stored = null
        tasksFlow.value = emptyList()
    }
}

/** Успешный ответ `GET /api/state`. */
fun successResponse(response: StateResponse): Response<StateResponse> =
    Response.success(response)

/** Неуспешный ответ с JSON-телом ошибки. */
fun errorResponse(code: Int, json: String = """{"error":"device"}"""): Response<StateResponse> =
    Response.error<StateResponse>(code, jsonBody(json))

/** Тело ответа с JSON-типом. */
fun jsonBody(json: String): ResponseBody = json.toResponseBody("application/json".toMediaType())

/** DTO задачи с нейтральными значениями по умолчанию. */
fun taskDto(
    id: String = "task-1",
    listId: String = "list-1",
    parentId: String? = null,
    categoryId: String? = null,
    title: String = "Задача",
    content: String = "",
    status: String = "created",
    source: String? = null,
    createdAt: Double? = null,
    updatedAt: Double? = null,
    completedAt: Double? = null,
    deleted: Int = 0,
    tags: List<String> = emptyList(),
    attachments: List<AttachmentDto> = emptyList(),
    history: List<StatusHistoryDto> = emptyList(),
): TaskDto = TaskDto(
    id = id,
    listId = listId,
    parentId = parentId,
    categoryId = categoryId,
    title = title,
    content = content,
    status = status,
    source = source,
    createdAt = createdAt,
    updatedAt = updatedAt,
    completedAt = completedAt,
    deleted = deleted,
    tags = tags,
    attachments = attachments,
    history = history,
)

/** DTO вложения: `taskId = null` проверяет подстановку id задачи при маппинге. */
fun attachmentDto(
    id: String = "attachment-1",
    taskId: String? = null,
    origName: String = "file.txt",
    mime: String? = null,
    size: Long = 0,
    createdAt: Double? = null,
    deleted: Int = 0,
): AttachmentDto = AttachmentDto(
    id = id,
    taskId = taskId,
    origName = origName,
    mime = mime,
    size = size,
    createdAt = createdAt,
    deleted = deleted,
)

/** Запись истории статусов. */
fun historyDto(status: String = "created", ts: Double = 0.0): StatusHistoryDto =
    StatusHistoryDto(status = status, ts = ts)

/**
 * Ответ `/api/state` из задач и настроек.
 *
 * `serverTime` по умолчанию заполнен: настоящий сервер присылает его всегда (§6), и клиент
 * использует это как признак полноценного снапшота — без него ответ считается неполным.
 */
fun stateResponse(
    tasks: List<TaskDto> = emptyList(),
    settings: ServerSettingsDto = ServerSettingsDto(),
    serverTime: Double? = DEFAULT_SERVER_TIME,
): StateResponse = StateResponse(
    tasks = tasks,
    settings = settings,
    serverTime = serverTime,
)

/** Фиксированное «серверное время» тестов. */
const val DEFAULT_SERVER_TIME: Double = 1_765_843_200.0

/** Доменная задача с минимальным набором значимых полей. */
fun task(
    id: String,
    status: TaskStatus,
    statusDateTs: Double = 0.0,
    parentId: String? = null,
): Task = Task(
    id = id,
    title = "Задача $id",
    status = status,
    statusDateTs = statusDateTs,
    parentId = parentId,
    categoryTitle = null,
    categoryColor = null,
    source = null,
)
