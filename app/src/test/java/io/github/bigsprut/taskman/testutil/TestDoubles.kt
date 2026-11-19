package io.github.bigsprut.taskman.testutil

import io.github.bigsprut.taskman.data.attachments.AttachmentStore
import io.github.bigsprut.taskman.data.attachments.LocalFile
import io.github.bigsprut.taskman.data.attachments.LocalFileHandle
import io.github.bigsprut.taskman.data.attachments.LocalFileSource
import io.github.bigsprut.taskman.data.local.PendingMutationDao
import io.github.bigsprut.taskman.data.local.entity.PendingMutationEntity
import io.github.bigsprut.taskman.data.local.entity.TaskEntity
import io.github.bigsprut.taskman.data.remote.TaskmanApi
import io.github.bigsprut.taskman.data.export.ExportSnapshot
import io.github.bigsprut.taskman.data.remote.dto.AttachmentDto
import io.github.bigsprut.taskman.data.remote.dto.CategoryInDto
import io.github.bigsprut.taskman.data.remote.dto.CategoryResponse
import io.github.bigsprut.taskman.data.remote.dto.DeleteCatalogResponse
import kotlinx.serialization.json.JsonObject
import io.github.bigsprut.taskman.data.remote.dto.SettingsResponse
import io.github.bigsprut.taskman.data.remote.dto.TagInDto
import io.github.bigsprut.taskman.data.remote.dto.TagResponse
import io.github.bigsprut.taskman.data.remote.dto.AttachmentUploadResponse
import io.github.bigsprut.taskman.data.remote.dto.DeleteAttachmentResponse
import io.github.bigsprut.taskman.data.remote.dto.DeleteEmployeeResponse
import io.github.bigsprut.taskman.data.remote.dto.DeleteTaskResponse
import io.github.bigsprut.taskman.data.remote.dto.EmployeeInDto
import io.github.bigsprut.taskman.data.remote.dto.EmployeeResponse
import io.github.bigsprut.taskman.data.remote.dto.SavedTaskDto
import io.github.bigsprut.taskman.data.remote.dto.ServerSettingsDto
import io.github.bigsprut.taskman.data.remote.dto.StateResponse
import io.github.bigsprut.taskman.data.remote.dto.StatusHistoryDto
import io.github.bigsprut.taskman.data.remote.dto.TaskDto
import io.github.bigsprut.taskman.data.remote.dto.TaskInDto
import io.github.bigsprut.taskman.data.remote.dto.UpsertTasksResponse
import io.github.bigsprut.taskman.data.repo.LocalTaskWriter
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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import retrofit2.Response

/**
 * Подставной API: ответы задаются лямбдами, число вызовов считается по методам, последние
 * отправленные данные остаются для проверок.
 *
 * Ответы разделены по методам намеренно: тест мутации не должен зависеть от того, как
 * настроен ответ `/api/state`, и наоборот. С Ф3 здесь же живут аплоад/удаление вложений
 * и CRUD сотрудников — с теми же правилами.
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

    /** Ответ `POST /api/tasks/{id}/attachments`; по умолчанию — успех с данными последней части. */
    var uploadOutcome: () -> Response<AttachmentUploadResponse> = {
        successResponse(
            AttachmentUploadResponse(
                id = NEW_ATTACHMENT_ID,
                name = lastUploadedFileName.orEmpty(),
                size = lastUploadedBytes?.size?.toLong() ?: 0L,
            ),
        )
    }

    /** Сколько раз вызывали аплоад вложения. */
    var uploadCalls: Int = 0

    /** id задачи последнего аплоада. */
    var lastUploadedTaskId: String? = null

    /** Имя поля multipart: контракт требует ровно `file` (ТЗ §4). */
    var lastUploadedFieldName: String? = null

    /** Имя файла из `Content-Disposition` последней части. */
    var lastUploadedFileName: String? = null

    /** Содержимое последней части — проверка, что на сервер ушли именно байты выбранного файла. */
    var lastUploadedBytes: ByteArray? = null

    /** Ответ `DELETE /api/attachments/{id}`; по умолчанию — удаление последнего id. */
    var deleteAttachmentOutcome: () -> Response<DeleteAttachmentResponse> = {
        successResponse(DeleteAttachmentResponse(deleted = lastDeletedAttachmentId.orEmpty()))
    }

    /** Сколько раз удаляли вложения. */
    var deleteAttachmentCalls: Int = 0

    /** id последнего удалённого вложения. */
    var lastDeletedAttachmentId: String? = null

    /** Ответ `POST /api/employees`; по умолчанию — сервер выдал id и сохранил присланное имя. */
    var createEmployeeOutcome: () -> Response<EmployeeResponse> = {
        successResponse(
            EmployeeResponse(
                id = NEW_EMPLOYEE_ID,
                name = lastCreatedEmployeeBody?.name.orEmpty(),
            ),
        )
    }

    /** Сколько раз создавали сотрудника. */
    var createEmployeeCalls: Int = 0

    /** Тело последнего `POST /api/employees`. */
    var lastCreatedEmployeeBody: EmployeeInDto? = null

    /** Ответ `PATCH /api/employees/{id}`; по умолчанию — успех с переименованным сотрудником. */
    var updateEmployeeOutcome: () -> Response<EmployeeResponse> = {
        successResponse(
            EmployeeResponse(
                id = lastUpdatedEmployeeId.orEmpty(),
                name = lastUpdatedEmployeeBody?.name.orEmpty(),
            ),
        )
    }

    /** Сколько раз переименовывали сотрудника. */
    var updateEmployeeCalls: Int = 0

    /** id последнего `PATCH /api/employees/{id}`. */
    var lastUpdatedEmployeeId: String? = null

    /** Тело последнего `PATCH /api/employees/{id}`. */
    var lastUpdatedEmployeeBody: EmployeeInDto? = null

    /** Тело последнего запроса по сотруднику — без разницы, создание это или переименование. */
    val lastEmployeeBody: EmployeeInDto?
        get() = lastUpdatedEmployeeBody ?: lastCreatedEmployeeBody

    /** Ответ `DELETE /api/employees/{id}`; по умолчанию — удаление последнего id. */
    var deleteEmployeeOutcome: () -> Response<DeleteEmployeeResponse> = {
        successResponse(DeleteEmployeeResponse(deleted = lastDeletedEmployeeId.orEmpty()))
    }

    /** Сколько раз удаляли сотрудника. */
    var deleteEmployeeCalls: Int = 0

    /** id последнего удалённого сотрудника. */
    var lastDeletedEmployeeId: String? = null

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

    override suspend fun uploadAttachment(
        taskId: String,
        file: MultipartBody.Part,
    ): Response<AttachmentUploadResponse> {
        uploadCalls += 1
        lastUploadedTaskId = taskId
        // Имя поля и имя файла живут в заголовке части — разбираем его, а не догадываемся
        // по телу: контракт (§4) проверяется именно по тому, что уйдёт по проводу.
        val disposition = file.headers?.get(HEADER_CONTENT_DISPOSITION)
        lastUploadedFieldName = disposition
            ?.let { MULTIPART_FIELD_NAME.find(it) }
            ?.groupValues
            ?.getOrNull(1)
        lastUploadedFileName = disposition
            ?.let { MULTIPART_FILE_NAME.find(it) }
            ?.groupValues
            ?.getOrNull(1)
        lastUploadedBytes = Buffer().also { file.body.writeTo(it) }.readByteArray()
        return uploadOutcome()
    }

    override suspend fun deleteAttachment(id: String): Response<DeleteAttachmentResponse> {
        deleteAttachmentCalls += 1
        lastDeletedAttachmentId = id
        return deleteAttachmentOutcome()
    }

    override suspend fun createEmployee(body: EmployeeInDto): Response<EmployeeResponse> {
        createEmployeeCalls += 1
        lastCreatedEmployeeBody = body
        return createEmployeeOutcome()
    }

    override suspend fun updateEmployee(
        id: String,
        body: EmployeeInDto,
    ): Response<EmployeeResponse> {
        updateEmployeeCalls += 1
        lastUpdatedEmployeeId = id
        lastUpdatedEmployeeBody = body
        return updateEmployeeOutcome()
    }

    override suspend fun deleteEmployee(id: String): Response<DeleteEmployeeResponse> {
        deleteEmployeeCalls += 1
        lastDeletedEmployeeId = id
        return deleteEmployeeOutcome()
    }

    // --- Ф5: теги, категории, настройки ---

    var createTagOutcome: () -> Response<TagResponse> =
        { Response.success(TagResponse(id = "tag-new", title = "Тег", color = "#4a5c92")) }
    var updateTagOutcome: () -> Response<TagResponse> =
        { Response.success(TagResponse(id = "tag-1", title = "Тег", color = "#4a5c92")) }
    var deleteTagOutcome: () -> Response<DeleteCatalogResponse> =
        { Response.success(DeleteCatalogResponse(deleted = "tag-1")) }
    var createCategoryOutcome: () -> Response<CategoryResponse> =
        { Response.success(CategoryResponse(id = "cat-new", title = "Категория", color = "#4a5c92")) }
    var updateCategoryOutcome: () -> Response<CategoryResponse> =
        { Response.success(CategoryResponse(id = "cat-1", title = "Категория", color = "#4a5c92")) }
    var deleteCategoryOutcome: () -> Response<DeleteCatalogResponse> =
        { Response.success(DeleteCatalogResponse(deleted = "cat-1")) }
    var updateSettingsOutcome: () -> Response<SettingsResponse> =
        { Response.success(SettingsResponse(defaultCategory = null)) }

    var lastCreatedTag: TagInDto? = null
    var lastUpdatedTagId: String? = null
    var lastUpdatedTag: TagInDto? = null
    var lastDeletedTagId: String? = null
    var lastCreatedCategory: CategoryInDto? = null
    var lastUpdatedCategoryId: String? = null
    var lastUpdatedCategory: CategoryInDto? = null
    var lastDeletedCategoryId: String? = null
    var lastSettingsBody: JsonObject? = null

    override suspend fun createTag(body: TagInDto): Response<TagResponse> {
        lastCreatedTag = body
        return createTagOutcome()
    }

    override suspend fun updateTag(id: String, body: TagInDto): Response<TagResponse> {
        lastUpdatedTagId = id
        lastUpdatedTag = body
        return updateTagOutcome()
    }

    override suspend fun deleteTag(id: String): Response<DeleteCatalogResponse> {
        lastDeletedTagId = id
        return deleteTagOutcome()
    }

    override suspend fun createCategory(body: CategoryInDto): Response<CategoryResponse> {
        lastCreatedCategory = body
        return createCategoryOutcome()
    }

    override suspend fun updateCategory(
        id: String,
        body: CategoryInDto,
    ): Response<CategoryResponse> {
        lastUpdatedCategoryId = id
        lastUpdatedCategory = body
        return updateCategoryOutcome()
    }

    override suspend fun deleteCategory(id: String): Response<DeleteCatalogResponse> {
        lastDeletedCategoryId = id
        return deleteCategoryOutcome()
    }

    override suspend fun updateSettings(body: JsonObject): Response<SettingsResponse> {
        lastSettingsBody = body
        return updateSettingsOutcome()
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
 *
 * С Ф3 объект годится и как [AttachmentStore]: вложения лежат по задачам ([attachmentsByTask]),
 * а [attachmentsFlow] — их объединение, чтобы тесту было видно всё, что положено.
 */
class FakeStateStore(initialTasks: List<Task> = emptyList()) :
    StateStore, TaskSnapshotReader, AttachmentStore {

    var stored: StateSnapshot? = null
    var clearCalls: Int = 0
    val snapshots = mutableListOf<StateSnapshot>()
    val tasksFlow = MutableStateFlow(initialTasks)
    val defaultCategoryFlow = MutableStateFlow<String?>(null)
    var exportSnapshot: ExportSnapshot = ExportSnapshot()
    val tagsFlow = MutableStateFlow<List<Tag>>(emptyList())
    val categoriesFlow = MutableStateFlow<List<Category>>(emptyList())

    /** Справочник сотрудников для поля «сотрудник» в редакторе (§5.3-Ф2). */
    val employeesFlow = MutableStateFlow<List<Employee>>(emptyList())

    /**
     * Вложения по задачам. Поток создаётся лениво: подписка на вложения ещё не созданной
     * задачи (UI может прийти раньше снапшота) должна дать пустой список, а не упасть, —
     * как и `observeAttachments` у Room.
     */
    val attachmentsByTask = mutableMapOf<String, MutableStateFlow<List<Attachment>>>()

    /** Все вложения одним списком — для проверок, где разбиение по задачам не важно. */
    val attachmentsFlow = MutableStateFlow<List<Attachment>>(emptyList())

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

    override fun observeAttachments(taskId: String): Flow<List<Attachment>> =
        attachmentsByTask.getOrPut(taskId) { MutableStateFlow(emptyList()) }

    override suspend fun findAttachment(attachmentId: String): Attachment? =
        (attachmentsFlow.value + attachmentsByTask.values.flatMap { it.value })
            .firstOrNull { it.id == attachmentId }

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

    override fun observeDefaultCategoryId(): Flow<String?> = defaultCategoryFlow

    override suspend fun loadExportSnapshot(): ExportSnapshot = exportSnapshot

    override suspend fun clear() {
        clearCalls += 1
        stored = null
        tasksFlow.value = emptyList()
        tagsFlow.value = emptyList()
        categoriesFlow.value = emptyList()
        employeesFlow.value = emptyList()
        attachmentsByTask.clear()
        attachmentsFlow.value = emptyList()
        entities.clear()
        tagsByTask.clear()
        historyByTask.clear()
    }

    override suspend fun taskEntity(id: String): TaskEntity? = entities[id]

    override suspend fun tagIds(taskId: String): List<String> = tagsByTask[taskId].orEmpty()

    /** Список для новых задач: по умолчанию один, тесты могут переопределить. */
    var firstListId: String? = "list-1"

    override suspend fun firstListId(): String? = firstListId

    /** Кладёт задачу во «внутренний» снапшот вместе с её тегами — источник для мутаций. */
    fun putTask(entity: TaskEntity, tagIds: List<String> = emptyList()) {
        entities[entity.id] = entity
        tagsByTask[entity.id] = tagIds
    }

    /** Кладёт историю статусов задачи — источник для блока «История» в редакторе. */
    fun putHistory(taskId: String, entries: List<TaskHistoryEntry>) {
        historyByTask.getOrPut(taskId) { MutableStateFlow(emptyList()) }.value = entries
    }

    /**
     * Кладёт вложения задачи. [attachmentsFlow] хранит объединение по всем задачам, поэтому
     * подписка на задачу и поиск по id видят один и тот же набор.
     */
    fun putAttachments(taskId: String, attachments: List<Attachment>) {
        attachmentsByTask.getOrPut(taskId) { MutableStateFlow(emptyList()) }.value = attachments
        attachmentsFlow.value = attachmentsByTask.values.flatMap { it.value }
    }
}

/** Подставной источник локальных файлов: отдаёт [file] (`null` — файл не выбран/не читается). */
class FakeLocalFileSource(var file: LocalFile? = null) : LocalFileSource {

    /** Сколько раз просили прочитать файл. */
    var readCalls: Int = 0

    /** uri последнего запроса на чтение. */
    var lastUri: String? = null

    override suspend fun open(uri: String): LocalFileHandle? {
        readCalls += 1
        lastUri = uri
        return file?.handle()
    }
}

/**
 * Подставной DAO очереди неотправленных мутаций (Ф4, §5.3).
 *
 * Устроен как таблица Room: `LinkedHashMap` по `taskId` (повторный `upsert` заменяет запись),
 * `pending()` отдаёт записи по `createdAt` — тот же порядок, что `ORDER BY createdAt` в запросе.
 * Счётчик живёт в [MutableStateFlow] и обновляется каждым изменением: тест видит 0→1→2→0
 * без задержек, как и Flow из Room.
 */
class FakePendingMutationDao : PendingMutationDao {

    /** Записи по `taskId`; повторный upsert заменяет значение, порядок вставки сохраняется. */
    private val records = LinkedHashMap<String, PendingMutationEntity>()

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
        count.value = records.size
    }
}

/**
 * Подставной локальный писатель (Ф4): запоминает, что «применилось к Room».
 *
 * Нужен, чтобы проверять оптимистичность без Android/Room: правка обязана попасть в локальное
 * хранилище до попытки отправить её на сервер.
 */
class FakeLocalTaskWriter : LocalTaskWriter {

    /** Сколько раз применяли upsert. */
    var upsertCalls: Int = 0

    /** Последняя применённая сущность — по ней проверяются поля оптимистичной правки. */
    var lastUpsert: TaskEntity? = null

    /** Все применённые upsert по порядку: видно, что и в какой последовательности записали. */
    val upserts = mutableListOf<TaskEntity>()

    /** Сколько раз применяли локальное удаление. */
    var deleteCalls: Int = 0

    /** id последнего локально удалённого поддерева. */
    var lastDeletedId: String? = null

    override suspend fun applyUpsert(entity: TaskEntity) {
        upsertCalls += 1
        lastUpsert = entity
        upserts += entity
    }

    override suspend fun applyDelete(taskId: String) {
        deleteCalls += 1
        lastDeletedId = taskId
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

/** Успешный ответ `POST /api/tasks/{id}/attachments`. */
fun successResponse(response: AttachmentUploadResponse): Response<AttachmentUploadResponse> =
    Response.success(response)

/** Успешный ответ `DELETE /api/attachments/{id}`. */
fun successResponse(response: DeleteAttachmentResponse): Response<DeleteAttachmentResponse> =
    Response.success(response)

/** Успешный ответ `POST|PATCH /api/employees`. */
fun successResponse(response: EmployeeResponse): Response<EmployeeResponse> =
    Response.success(response)

/** Успешный ответ `DELETE /api/employees/{id}`. */
fun successResponse(response: DeleteEmployeeResponse): Response<DeleteEmployeeResponse> =
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
 * Доменное вложение: метаданные файла на сервере (сам файл клиент не кэширует).
 *
 * Ссылка на файл собирается из id (`AttachmentUrls`), поэтому в тестах достаточно id и имени.
 */
fun attachment(
    id: String = "attachment-1",
    taskId: String = "task-1",
    origName: String = "file.txt",
    mime: String? = null,
    size: Long = 0,
    createdAt: Double = 0.0,
): Attachment = Attachment(
    id = id,
    taskId = taskId,
    origName = origName,
    mime = mime,
    size = size,
    createdAt = createdAt,
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

/** id, который подставной сервер выдаёт новому вложению. */
private const val NEW_ATTACHMENT_ID = "attachment-new"

/** id, который подставной сервер выдаёт новому сотруднику. */
private const val NEW_EMPLOYEE_ID = "employee-new"

private const val HEADER_CONTENT_DISPOSITION = "Content-Disposition"

/** Имя поля multipart в `Content-Disposition`: контракт требует ровно `file` (ТЗ §4). */
private val MULTIPART_FIELD_NAME = Regex("name=\"([^\"]*)\"")

/** Имя файла в `Content-Disposition`. */
private val MULTIPART_FILE_NAME = Regex("filename=\"([^\"]*)\"")
