package io.github.bigsprut.taskman.net

import com.google.common.truth.Truth.assertThat
import io.github.bigsprut.taskman.data.remote.ConnectionHolder
import io.github.bigsprut.taskman.data.remote.NetworkFactory
import io.github.bigsprut.taskman.data.remote.TaskmanApi
import io.github.bigsprut.taskman.data.remote.TaskmanJson
import io.github.bigsprut.taskman.data.repo.MutationOutcome
import io.github.bigsprut.taskman.data.repo.OfflineMutationQueue
import io.github.bigsprut.taskman.data.repo.TaskMutationRepository
import io.github.bigsprut.taskman.data.repo.TaskRepository
import io.github.bigsprut.taskman.domain.TaskDraft
import io.github.bigsprut.taskman.domain.TaskStatus
import io.github.bigsprut.taskman.testutil.FakeLocalTaskWriter
import io.github.bigsprut.taskman.testutil.FakePendingMutationDao
import io.github.bigsprut.taskman.testutil.FakeStateStore
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/**
 * Контракт сохранения черновика редактора на настоящем HTTP (ТЗ §4, §6, §5.3-Ф2).
 *
 * Проверяется то, что реально уходит по проводу при закрытии листа: `POST /api/tasks`
 * с заголовками `Token`/`X-Device-Id`, телом-массивом из одного полного объекта задачи и
 * `status_date` **только** при выбранной дате. Отдельно — 409: конфликт не лечится повтором,
 * а заканчивается рефетчем состояния.
 *
 * Сервер — JDK `com.sun.net.httpserver` ([HttpTestServer]) на loopback и эфемерном порту:
 * ни фиксированных портов, ни выхода в интернет.
 */
class DraftSaveContractTest {

    @Test
    fun `сохранение черновика уходит в POST api tasks с заголовками и датой статуса`() {
        HttpTestServer().use { server ->
            server.respond(200, UPSERT_RESPONSE_JSON)
            server.respond(200, REFETCH_STATE_JSON)
            val api = newApi(server)
            val store = FakeStateStore()
            val mutations = mutations(api, store)

            val outcome = runBlocking {
                mutations.saveDraft(draft(statusDateTs = CHOSEN_STATUS_DATE))
            }

            assertThat(outcome).isEqualTo(MutationOutcome.Success)
            val requests = server.requests
            assertThat(requests).hasSize(2)
            val post = requests[0]
            assertThat(post.method).isEqualTo("POST")
            assertThat(post.path).isEqualTo("/api/tasks")
            // Имя `Token`, а не `X-Token`: это часть контракта (§4).
            assertThat(post.headers["Token"]).isEqualTo(TEST_TOKEN)
            assertThat(post.headers["X-Device-Id"]).isEqualTo(TEST_DEVICE_ID)

            // Тело upsert — массив задач; редактор сохраняет одну.
            val payloads = TaskmanJson.parseToJsonElement(post.body).jsonArray
            assertThat(payloads).hasSize(1)
            val payload = payloads.single().jsonObject
            assertThat(payload.string("id")).isEqualTo(TASK_ID)
            assertThat(payload.string("list_id")).isEqualTo("list-1")
            assertThat(payload.string("parent_id")).isEqualTo("parent-1")
            assertThat(payload.string("category_id")).isEqualTo("cat-1")
            assertThat(payload.string("title")).isEqualTo("Купить молоко")
            assertThat(payload.string("content")).isEqualTo("**две** булки")
            assertThat(payload.string("status")).isEqualTo(TaskStatus.IN_PROGRESS.wireValue)
            assertThat(payload.string("source")).isEqualTo("Иван")
            // Набор тегов заменяется целиком, поэтому порядок фиксирован (отсортирован).
            assertThat(payload["tags"]!!.jsonArray.map { it.jsonPrimitive.content })
                .containsExactly("tag-a", "tag-b")
                .inOrder()
            // Выбранная «задним числом» дата уходит как есть — в unix-секундах (§4).
            assertThat(payload["status_date"]!!.jsonPrimitive.double).isEqualTo(CHOSEN_STATUS_DATE)
            assertThat(payload["updated_at"]!!.jsonPrimitive.double).isEqualTo(SERVER_VERSION)
            assertThat(payload["created_at"]!!.jsonPrimitive.double).isEqualTo(1_700_000_000.0)

            // Успех тоже заканчивается рефетчем: даты и историю статусов считает сервер.
            assertThat(requests[1].method).isEqualTo("GET")
            assertThat(requests[1].path).isEqualTo("/api/state")
        }
    }

    @Test
    fun `без выбранной даты status_date в тело не попадает`() {
        HttpTestServer().use { server ->
            server.respond(200, UPSERT_RESPONSE_JSON)
            server.respond(200, REFETCH_STATE_JSON)
            val api = newApi(server)
            val mutations = mutations(api, FakeStateStore())

            val outcome = runBlocking { mutations.saveDraft(draft(statusDateTs = null)) }

            assertThat(outcome).isEqualTo(MutationOutcome.Success)
            val payload = TaskmanJson.parseToJsonElement(server.requests.first().body)
                .jsonArray
                .single()
                .jsonObject
            // `explicitNulls = false` в TaskmanJson: null-поля в JSON отсутствуют, и это
            // принципиально (§4): лишний `status_date` сдвинул бы даты в истории статусов.
            assertThat(payload).doesNotContainKey("status_date")
            assertThat(payload.string("title")).isEqualTo("Купить молоко")
        }
    }

    @Test
    fun `409 при сохранении даёт Conflict и рефетч состояния`() {
        HttpTestServer().use { server ->
            server.respond(409, """{"error":"conflict","actual_updated_at":1765843500.0}""")
            server.respond(200, REFETCH_STATE_JSON)
            val api = newApi(server)
            val store = FakeStateStore()
            val mutations = mutations(api, store)

            val outcome = runBlocking { mutations.saveDraft(draft(statusDateTs = null)) }

            assertThat(outcome).isInstanceOf(MutationOutcome.Conflict::class.java)
            assertThat((outcome as MutationOutcome.Conflict).actualUpdatedAt)
                .isEqualTo(1_765_843_500.0)

            val requests = server.requests
            assertThat(requests).hasSize(2)
            assertThat(requests[0].method).isEqualTo("POST")
            assertThat(requests[0].path).isEqualTo("/api/tasks")
            // После конфликта состояние перечитывается: иначе UI показал бы устаревшую версию.
            assertThat(requests[1].method).isEqualTo("GET")
            assertThat(requests[1].path).isEqualTo("/api/state")
            assertThat(store.stored).isNotNull()
        }
    }

    /** Черновик редактора: все поля заполнены, дата статуса — по сценарию теста. */
    private fun draft(statusDateTs: Double?): TaskDraft = TaskDraft(
        id = TASK_ID,
        listId = "list-1",
        parentId = "parent-1",
        title = "Купить молоко",
        content = "**две** булки",
        status = TaskStatus.IN_PROGRESS,
        categoryId = "cat-1",
        tagIds = linkedSetOf("tag-b", "tag-a"),
        source = "Иван",
        createdAt = 1_700_000_000.0,
        completedAt = null,
        statusDateTs = statusDateTs,
        updatedAt = SERVER_VERSION,
    )

    /** Репозиторий мутаций с очередью в памяти: контракт проверяется по проводу, без Room. */
    private fun mutations(api: TaskmanApi, store: FakeStateStore): TaskMutationRepository {
        val tasks = TaskRepository(api = api, stateStore = store)
        return TaskMutationRepository(
            api = api,
            reader = store,
            tasks = tasks,
            queue = OfflineMutationQueue(FakePendingMutationDao()),
            writer = FakeLocalTaskWriter(),
        )
    }

    /** Клиент с подставленным адресом сервера; кэш — свежий каталог на вызов. */
    private fun newApi(server: HttpTestServer): TaskmanApi {
        val holder = ConnectionHolder()
        holder.set(server.baseUrl(), TEST_TOKEN, TEST_DEVICE_ID)
        return NetworkFactory(freshCacheDirectory(), holder).api
    }

    private fun freshCacheDirectory(): File =
        Files.createTempDirectory("taskman-draft-cache").toFile()

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

    private companion object {
        const val TASK_ID = "task-1"
        const val TEST_TOKEN = "tok-123"
        const val TEST_DEVICE_ID = "dev-uuid"
        const val SERVER_VERSION = 1_765_843_200.0

        /** «Задним числом»: дата статуса, выбранная пользователем (unix-секунды). */
        const val CHOSEN_STATUS_DATE = 1_700_000_500.0
    }
}

/** Ответ `POST /api/tasks`: сохранённая задача и серверное время (ТЗ §6). */
private val UPSERT_RESPONSE_JSON = """
    {"saved":[{"id":"task-1","updated_at":1765843300.0}],"server_time":1765843300.0}
""".trimIndent()

/**
 * Минимальное тело `/api/state` для рефетча после сохранения: все секции на месте,
 * времена — unix-float секунды.
 */
private val REFETCH_STATE_JSON = """
    {"lists":[],"categories":[],"employees":[],"tags":[],
     "tasks":[{"id":"t1","list_id":"l1","title":"Задача","status":"created","deleted":0,
               "tags":[],"attachments":[],"history":[]}],
     "settings":{},"server_time":1765843200.0}
""".trimIndent()
