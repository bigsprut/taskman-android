package io.github.bigsprut.taskman.testutil

import io.github.bigsprut.taskman.data.local.entity.TaskEntity
import io.github.bigsprut.taskman.data.remote.TaskmanApi
import io.github.bigsprut.taskman.data.remote.dto.AttachmentDto
import io.github.bigsprut.taskman.data.remote.dto.DeleteTaskResponse
import io.github.bigsprut.taskman.data.remote.dto.SavedTaskDto
import io.github.bigsprut.taskman.data.remote.dto.ServerSettingsDto
import io.github.bigsprut.taskman.data.remote.dto.StateResponse
import io.github.bigsprut.taskman.data.remote.dto.StatusHistoryDto
import io.github.bigsprut.taskman.data.remote.dto.TaskDto
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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Response

/**
 * Подставной API: ответы задаются лямбдами, число вызовов считается по методам, последние
 * отправленные данные остаются для проверок.
 *
 * Три метода контракта (ТЗ §6) разделены намеренно: тест мутации не должен зависеть от того,
 * как настроен ответ `/api/state`, и наоборот.
 */
class FakeTaskmanApi : TaskmanApi {

    /** Ответ `GET /api/state`. */
    var outcome: () -> Response<StateResponse> = { successResponse(stateResponse()) }

    /** Сколько раз запрашивали снапшот (в тестах мутаций — сколько раз был `refresh()`). */
    var calls: Int = 0

    /** Ответ `POST /api/tasks`; по умолчанию считаем, что всё отправленное сохранено. */
    var upsertOutcome: () -> Response<UpsertTasksResponse> = {
        successResponse(
            UpsertTasksResponse(
                saved = lastUpserted.orEmpty().map { task ->
                    SavedTaskDto(id = task.id.orEmpty(), updatedAt = task.updatedAt)
                },
                serverTime = DEFAULT_SERVER_TIME,
            ),
        )
    }

    /** Сколько раз вызывали UPSERT. */
    var upsertCalls: Int = 0

    /** Тело последнего UPSERT: `null`, если UPSERT ещё не вызывали. */
    var lastUpserted: List<TaskInDto>? = null

    /** Ответ `DELETE /api/tasks/{id}`; по умолчанию — успешное удаление последнего id. */
    var deleteOutcome: () -> Response<DeleteTaskResponse> = {
        successResponse(DeleteTaskResponse(deleted = lastDeletedId.orEmpty()))
    }

    /** Сколько раз вызывали DELETE. */
    var deleteCalls: Int = 0

    /** id последнего DELETE: `null`, если DELETE ещё не вызывали. */
    var lastDeletedId: String? = null

    override suspend fun getState(): Response<StateResponse> {
        calls += 1
        return outcome()
    }

    override suspend fun upsertTasks(tasks: List<TaskInDto>): Response<UpsertTasksResponse> {
        upsertCalls += 1
        lastUpserted = tasks
        return upsertOutcome()
    }

    override suspend fun deleteTask(id: String): Response<DeleteTaskResponse> {
        deleteCalls += 1
        lastDeletedId = id
        return deleteOutcome()
    }
}

/**
 * Подставное хранилище: запоминает снапшоты, а задачи/теги/категории для UI отдаёт из
 * [tasksFlow], [tagsFlow], [categoriesFlow].
 *
 * Дополнительно реализует [TaskSnapshotReader] — так тесты мутаций работают с тем же объектом,
 * что и репозиторий. Порядок задач не сортируется специально: сортировка — обязанность
 * репозитория. Локальные сущности для мутаций ([entities], [tagsByTask]) заполняются явно
 * через [putTask], а не из [replaceSnapshot]: так тест видит ровно тот снапшот, который сам
 * подготовил (маппинг ответа сервера покрыт `StateMapperTest`).
 *
 * Редактору (Ф2) нужны ещё справочник сотрудников ([employeesFlow]) и история статусов
 * задачи ([historyByTask]): оба отдаются потоками, как и в Room-реализации.
 */
class FakeStateStore(initialTasks: List<Task> = emptyList()) : StateStore, TaskSnapshotReader {

    var stored: StateSnapshot? = null
    var clearCalls: Int = 0
    val snapshots = mutableListOf<StateSnapshot>()
    val tasksFlow = MutableStateFlow(initialTasks)
    val tagsFlow = MutableStateFlow<List<Tag>>(emptyList())
    val categoriesFlow = MutableStateFlow<List<Category>>(emptyList())

    /** Справочник сотрудников для поля «сотрудник» в редакторе (§5.3-Ф2). */
    val employeesFlow = MutableStateFlow<List<Employee>>(emptyList())

    /**
     * История статусов по задачам. Поток создаётся лениво: подписка на историю неизвестной
     * задачи (редактор подписывается сразу при открытии) должна дать пустой список, а не
     * упасть, — как и `observeTaskHistory` у Room.
     */
    val historyByTask = mutableMapOf<String, MutableStateFlow<List<TaskHistoryEntry>>>()

    /** Локальный снапшот для мутаций: полные сущности задач по id. */
    val entities = mutableMapOf<String, TaskEntity>()

    /** Локальный снапшот для мутаций: теги задачи по её id. */
    val tagsByTask = mutableMapOf<String, List<String>>()

    /** Исключение, которое бросить при записи снапшота (эмуляция сбоя локального хранилища). */
    var failOnReplace: Throwable? = null

    override suspend fun replaceSnapshot(snapshot: StateSnapshot) {
        failOnReplace?.let { throw it }
        stored = snapshot
        snapshots += snapshot
    }

    override fun observeTasks(): Flow<List<Task>> = tasksFlow

    override fun observeTags(): Flow<List<Tag>> = tagsFlow

    override fun observeCategories(): Flow<List<Category>> = categoriesFlow

    override fun observeEmployees(): Flow<List<Employee>> = employeesFlow

    override fun observeTaskHistory(taskId: String): Flow<List<TaskHistoryEntry>> =
        historyByTask.getOrPut(taskId) { MutableStateFlow(emptyList()) }

    /**
     * Полная запись задачи для редактора: сущность из [entities] и её теги.
     *
     * Источник тот же, что у мутаций ([putTask]), поэтому «что видит редактор» и «из чего
     * собирается payload» в тесте не могут разойтись.
     */
    override suspend fun loadTaskDraft(taskId: String): TaskDraftSnapshot? =
        entities[taskId]?.let { entity ->
            TaskDraftSnapshot(entity = entity, tagIds = tagsByTask[taskId].orEmpty())
        }

    override suspend fun clear() {
        clearCalls += 1
        stored = null
        tasksFlow.value = emptyList()
        tagsFlow.value = emptyList()
        categoriesFlow.value = emptyList()
        employeesFlow.value = emptyList()
        entities.clear()
        tagsByTask.clear()
        historyByTask.clear()
    }

    override suspend fun taskEntity(id: String): TaskEntity? = entities[id]

    override suspend fun tagIds(taskId: String): List<String> = tagsByTask[taskId].orEmpty()

    /** Кладёт задачу во «внутренний» снапшот вместе с её тегами — источник для мутаций. */
    fun putTask(entity: TaskEntity, tagIds: List<String> = emptyList()) {
        entities[entity.id] = entity
        tagsByTask[entity.id] = tagIds
    }

    /** Кладёт историю статусов задачи — источник для блока «История» в редакторе. */
    fun putHistory(taskId: String, entries: List<TaskHistoryEntry>) {
        historyByTask.getOrPut(taskId) { MutableStateFlow(emptyList()) }.value = entries
    }
}

/** Успешный ответ `GET /api/state`. */
fun successResponse(response: StateResponse): Response<StateResponse> =
    Response.success(response)

/** Успешный ответ `POST /api/tasks`. */
fun successResponse(response: UpsertTasksResponse): Response<UpsertTasksResponse> =
    Response.success(response)

/** Успешный ответ `DELETE /api/tasks/{id}`. */
fun successResponse(response: DeleteTaskResponse): Response<DeleteTaskResponse> =
    Response.success(response)

/** Неуспешный ответ с JSON-телом ошибки. */
fun errorResponse(code: Int, json: String = """{"error":"device"}"""): Response<StateResponse> =
    Response.error<StateResponse>(code, jsonBody(json))

/** Неуспешный ответ произвольного типа: мутации отвечают своими DTO, а ошибка у них общая. */
fun <T> errorResponseOf(code: Int, json: String = """{"error":"device"}"""): Response<T> =
    Response.error<T>(code, jsonBody(json))

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

/**
 * Доменная задача с минимальным набором значимых полей.
 *
 * Категория и теги нужны фильтрам Ф1, счётчик вложений — бейджу строки списка.
 */
fun task(
    id: String,
    status: TaskStatus,
    statusDateTs: Double = 0.0,
    parentId: String? = null,
    categoryId: String? = null,
    tagIds: List<String> = emptyList(),
    attachmentCount: Int = 0,
): Task = Task(
    id = id,
    title = "Задача $id",
    status = status,
    statusDateTs = statusDateTs,
    parentId = parentId,
    categoryId = categoryId,
    categoryTitle = null,
    categoryColor = null,
    source = null,
    tagIds = tagIds,
    attachmentCount = attachmentCount,
)

/**
 * Полная локальная сущность задачи: из неё собирается payload мутации (§4) и проверяется
 * «все поля на месте». Значения по умолчанию непустые там, где это важно для CAS.
 */
fun taskEntity(
    id: String = "task-1",
    listId: String = "list-1",
    parentId: String? = null,
    categoryId: String? = null,
    title: String = "Задача",
    content: String = "",
    status: String = "created",
    source: String? = null,
    createdAt: Double? = 100.0,
    updatedAt: Double? = 200.0,
    completedAt: Double? = null,
    statusDateTs: Double = 200.0,
    deleted: Boolean = false,
): TaskEntity = TaskEntity(
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
    statusDateTs = statusDateTs,
    deleted = deleted,
)
