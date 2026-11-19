package io.github.bigsprut.taskman.net

import com.google.common.truth.Truth.assertThat
import io.github.bigsprut.taskman.data.attachments.AttachmentStore
import io.github.bigsprut.taskman.data.remote.ApiFailure
import io.github.bigsprut.taskman.data.remote.ConnectionHolder
import io.github.bigsprut.taskman.data.remote.NetworkFactory
import io.github.bigsprut.taskman.data.remote.TaskmanApi
import io.github.bigsprut.taskman.data.repo.StateSnapshot
import io.github.bigsprut.taskman.data.export.ExportSnapshot
import io.github.bigsprut.taskman.data.repo.StateStore
import io.github.bigsprut.taskman.data.repo.SyncOutcome
import io.github.bigsprut.taskman.data.repo.TaskDraftSnapshot
import io.github.bigsprut.taskman.data.repo.TaskRepository
import io.github.bigsprut.taskman.domain.Attachment
import io.github.bigsprut.taskman.domain.Category
import io.github.bigsprut.taskman.domain.Employee
import io.github.bigsprut.taskman.domain.Tag
import io.github.bigsprut.taskman.domain.Task
import io.github.bigsprut.taskman.domain.TaskHistoryEntry
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Контрактные тесты сетевого слоя на настоящем HTTP (ТЗ §4, §6).
 *
 * Сервер — JDK `com.sun.net.httpserver` на loopback и эфемерном порту, поэтому тесты
 * не ходят в интернет и не занимают фиксированные порты. Проверяется то, что реально
 * уходит по проводу: заголовки `Token`/`X-Device-Id`, подстановка base URL, разжатие gzip,
 * кэш `ETag`/`304` и коды 401/403 в терминах [SyncOutcome].
 *
 * Кэш OkHttp у каждого теста свой временный каталог: иначе запись `ETag` протекла бы
 * в следующий тест и число обращений к серверу стало бы непредсказуемым.
 */
class NetworkContractTest {

    /** (а) Заголовки контракта доходят до сервера: имена `Token`/`X-Device-Id` и значения. */
    @Test
    fun headersReachServer() {
        HttpTestServer().use { server ->
            server.respond(200, STATE_JSON)
            val holder = ConnectionHolder()
            holder.set(server.baseUrl(), TEST_TOKEN, TEST_DEVICE_ID)
            val api = NetworkFactory(freshCacheDirectory(), holder).api

            val response = runBlocking { api.getState() }

            assertThat(response.isSuccessful).isTrue()
            assertThat(response.code()).isEqualTo(200)

            val request = server.requests.single()
            assertThat(request.method).isEqualTo("GET")
            assertThat(request.path).isEqualTo("/api/state")
            // Имена HTTP-заголовков регистронезависимы, а com.sun.net.httpserver ещё и
            // нормализует написание (X-Device-Id → X-device-id), поэтому проверяем имя
            // без учёта регистра и значение. Значимое здесь — что имя именно Token
            // (а не X-Token) и что X-Device-Id доехал.
            assertThat(request.headers["Token"]).isEqualTo(TEST_TOKEN)
            assertThat(request.headers["token"]).isEqualTo(TEST_TOKEN)
            assertThat(request.headers["X-Device-Id"]).isEqualTo(TEST_DEVICE_ID)
            assertThat(request.headers["x-device-id"]).isEqualTo(TEST_DEVICE_ID)
            assertThat(request.headers["X-Token"]).isNull()
        }
    }

    /** (б) Префикс пути в base URL не теряется: сервер видит `/taskman/api/state`. */
    @Test
    fun baseUrlPathPrefixIsPreserved() {
        HttpTestServer().use { server ->
            server.respond(200, STATE_JSON)
            val holder = ConnectionHolder()
            holder.set(server.baseUrl("/taskman"), TEST_TOKEN, TEST_DEVICE_ID)
            val api = NetworkFactory(freshCacheDirectory(), holder).api

            val response = runBlocking { api.getState() }

            assertThat(response.isSuccessful).isTrue()
            val request = server.requests.single()
            assertThat(request.path).isEqualTo("/taskman/api/state")
            // Адрес подставлен целиком: тот же порт, тот же заголовок.
            assertThat(request.headers["Token"]).isEqualTo(TEST_TOKEN)
        }
    }

    /** (в) gzip: тело разжимается OkHttp и парсится в модель. */
    @Test
    fun gzipResponseIsParsed() {
        HttpTestServer().use { server ->
            server.gzipResponse(200, STATE_JSON)
            val api = newApi(server)

            val response = runBlocking { api.getState() }

            assertThat(response.code()).isEqualTo(200)
            assertThat(response.isSuccessful).isTrue()
            assertThat(response.body()).isNotNull()
            val state = response.body()!!
            assertThat(state.tasks).isNotEmpty()
            assertThat(state.tasks.single().title).isEqualTo("Задача")
            assertThat(state.serverTime).isEqualTo(1765843200.0)
            // OkHttp сам просит gzip: без этого прозрачного разжатия не было бы.
            assertThat(server.requests.single().headers["Accept-Encoding"]).contains("gzip")
        }
    }

    /** (г) 403 `{"error":"device"}` — это ожидание подтверждения устройства, не ошибка сети. */
    @Test
    fun device403GivesDeviceNotApproved() {
        HttpTestServer().use { server ->
            server.respond(403, """{"error":"device"}""")
            val store = FakeStateStore()
            val repository = newRepository(server, store)

            val outcome = runBlocking { repository.refresh() }

            assertThat(outcome).isEqualTo(SyncOutcome.DeviceNotApproved)
            assertThat(repository.syncStatus.value.lastFailure).isEqualTo(ApiFailure.DeviceNotApproved)
            assertThat(store.snapshot).isNull()
            assertThat(server.requests.single().path).isEqualTo("/api/state")
        }
    }

    /** (г) 401 — токен не принят, отдельный исход для навигации на экран входа. */
    @Test
    fun unauthorized401GivesUnauthorized() {
        HttpTestServer().use { server ->
            server.respond(401, """{"error":"unauthorized"}""")
            val store = FakeStateStore()
            val repository = newRepository(server, store)

            val outcome = runBlocking { repository.refresh() }

            assertThat(outcome).isEqualTo(SyncOutcome.Unauthorized)
            assertThat(repository.syncStatus.value.lastFailure).isEqualTo(ApiFailure.Unauthorized)
            assertThat(store.snapshot).isNull()
        }
    }

    /** Успех через репозиторий: тело разобрано, снапшот отдан в хранилище. */
    @Test
    fun successStoresSnapshot() {
        HttpTestServer().use { server ->
            server.respond(200, STATE_JSON)
            val store = FakeStateStore()
            val repository = newRepository(server, store)

            val outcome = runBlocking { repository.refresh() }

            assertThat(outcome).isEqualTo(SyncOutcome.Success(1))
            val saved = store.snapshot
            assertThat(saved).isNotNull()
            val snapshot = saved!!
            assertThat(snapshot.tasks).hasSize(1)
            assertThat(snapshot.tasks.single().id).isEqualTo("t1")
            assertThat(snapshot.tasks.single().deleted).isFalse()
        }
    }

    /** (д) ETag/304: второй запрос несёт `If-None-Match`, тело берётся из кэша OkHttp. */
    @Test
    fun etag304ServesBodyFromCache() {
        HttpTestServer().use { server ->
            server.respond(200, STATE_JSON, mapOf("ETag" to ETAG, "Cache-Control" to "no-cache"))
            server.respond(304, headers = mapOf("ETag" to ETAG))
            val api = newApi(server)

            val first = runBlocking { api.getState() }
            val second = runBlocking { api.getState() }

            assertThat(first.code()).isEqualTo(200)
            assertThat(first.body()).isNotNull()
            // 304 без тела: Retrofit получает успешный ответ с тем же телом из кэша.
            assertThat(second.code()).isEqualTo(200)
            assertThat(second.isSuccessful).isTrue()
            assertThat(second.body()).isNotNull()
            assertThat(second.body()!!.tasks.single().id).isEqualTo("t1")
            assertThat(first.body()!!.tasks.single().id).isEqualTo("t1")

            // Ровно два обращения, и второе — условное.
            assertThat(server.requests).hasSize(2)
            assertThat(server.requests[0].headers["If-None-Match"]).isNull()
            assertThat(server.requests[1].headers["If-None-Match"]).isEqualTo(ETAG)
            assertThat(server.requests[1].path).isEqualTo("/api/state")
        }
    }

    /** Клиент с подставленным адресом сервера; кэш — свежий каталог на вызов. */
    private fun newApi(server: HttpTestServer, pathPrefix: String = ""): TaskmanApi {
        val holder = ConnectionHolder()
        holder.set(server.baseUrl(pathPrefix), TEST_TOKEN, TEST_DEVICE_ID)
        return NetworkFactory(freshCacheDirectory(), holder).api
    }

    /** Репозиторий поверх настоящего HTTP и подставного хранилища. */
    private fun newRepository(server: HttpTestServer, store: FakeStateStore): TaskRepository {
        val holder = ConnectionHolder()
        holder.set(server.baseUrl(), TEST_TOKEN, TEST_DEVICE_ID)
        return TaskRepository(
            api = NetworkFactory(freshCacheDirectory(), holder).api,
            stateStore = store,
            now = { 1L },
        )
    }

    private fun freshCacheDirectory(): File =
        Files.createTempDirectory("taskman-http-cache").toFile()
}

/**
 * Подставное [StateStore]: помнит последний снапшот, задач наружу не отдаёт.
 *
 * Своя реализация в этом пакете — чтобы тесты сети не зависели от тестовых дублёров
 * других наборов и проверяли только то, что делает репозиторий с ответом сервера.
 *
 * [AttachmentStore] реализуется для полноты: с Ф3 хранилище приложения отдаёт и вложения,
 * а сетевые тесты их не читают — потоки пусты, поиск всегда `null`.
 */
private class FakeStateStore : StateStore, AttachmentStore {

    @Volatile
    var snapshot: StateSnapshot? = null
        private set

    private val tasks = MutableStateFlow<List<Task>>(emptyList())

    override suspend fun replaceSnapshot(snapshot: StateSnapshot) {
        this.snapshot = snapshot
    }

    override fun observeTasks(): Flow<List<Task>> = tasks

    // Каталоги фильтров этим тестам не нужны: сетевой слой их не читает.
    override fun observeTags(): Flow<List<Tag>> = emptyFlow()

    override fun observeCategories(): Flow<List<Category>> = emptyFlow()

    // Справочник сотрудников и история задачи нужны только редактору (Ф2), сеть их не читает.
    override fun observeEmployees(): Flow<List<Employee>> = emptyFlow()

    override fun observeTaskHistory(taskId: String): Flow<List<TaskHistoryEntry>> = emptyFlow()

    // Вложения этим тестам не нужны: сетевой слой их не читает.
    override fun observeAttachments(taskId: String): Flow<List<Attachment>> = emptyFlow()

    override suspend fun findAttachment(attachmentId: String): Attachment? = null

    // Черновик редактора собирается репозиторием из этой же записи; сетевым тестам не нужен.
    override suspend fun loadTaskDraft(taskId: String): TaskDraftSnapshot? = null

    override fun observeDefaultCategoryId(): Flow<String?> = emptyFlow()

    override suspend fun loadExportSnapshot(): ExportSnapshot = ExportSnapshot()

    override suspend fun clear() {
        snapshot = null
    }
}

private const val TEST_TOKEN = "tok-123"
private const val TEST_DEVICE_ID = "dev-uuid"

/** ETag в кавычках — как его отдают серверы и ждёт `If-None-Match`. */
private const val ETAG = "\"abc\""

/**
 * Минимальное тело `/api/state` (ТЗ §6): одна задача, все секции на месте,
 * времена — unix-float секунды.
 */
private val STATE_JSON = """
    {"lists":[],"categories":[],"employees":[],"tags":[],
     "tasks":[{"id":"t1","list_id":"l1","title":"Задача","status":"created","deleted":0,
               "tags":[],"attachments":[],"history":[]}],
     "settings":{},"server_time":1765843200.0}
""".trimIndent()
