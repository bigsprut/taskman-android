package io.github.bigsprut.taskman.net

import com.google.common.truth.Truth.assertThat
import io.github.bigsprut.taskman.data.remote.ConnectionHolder
import io.github.bigsprut.taskman.data.remote.NetworkFactory
import io.github.bigsprut.taskman.data.remote.TaskmanApi
import io.github.bigsprut.taskman.data.remote.TaskmanJson
import io.github.bigsprut.taskman.data.repo.MutationOutcome
import io.github.bigsprut.taskman.data.repo.OfflineMutationQueue
import io.github.bigsprut.taskman.data.repo.TaskMutationRepository
import io.github.bigsprut.taskman.data.repo.TaskPayload
import io.github.bigsprut.taskman.data.repo.TaskRepository
import io.github.bigsprut.taskman.testutil.FakeLocalTaskWriter
import io.github.bigsprut.taskman.testutil.FakePendingMutationDao
import io.github.bigsprut.taskman.testutil.FakeStateStore
import io.github.bigsprut.taskman.testutil.taskEntity
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
 * Контракт мутаций на настоящем HTTP (ТЗ §4, §6): `POST /api/tasks` и `DELETE /api/tasks/{id}`.
 *
 * Проверяется то, что реально уходит по проводу: заголовки `Token`/`X-Device-Id`, полное
 * JSON-тело задачи, разбор ответа upsert и обработка 409 через [TaskMutationRepository].
 * Сервер — JDK `com.sun.net.httpserver` ([HttpTestServer]) на loopback и эфемерном порту,
 * поэтому ни фиксированных портов, ни выхода в интернет нет.
 */
class MutationContractTest {

    @Test
    fun `POST api tasks доходит с заголовками и полным JSON задачи`() {
        HttpTestServer().use { server ->
            server.respond(200, UPSERT_JSON)
            val api = newApi(server)
            val entity = fullEntity()

            val response = runBlocking { api.upsertTasks(listOf(TaskPayload.fromEntity(entity, TAG_IDS))) }

            assertThat(response.isSuccessful).isTrue()
            assertThat(response.code()).isEqualTo(200)
            val body = response.body()
            assertThat(body).isNotNull()
            assertThat(body!!.saved).hasSize(1)
            assertThat(body.saved.single().id).isEqualTo(TASK_ID)
            assertThat(body.saved.single().updatedAt).isEqualTo(SERVER_TIME)
            assertThat(body.serverTime).isEqualTo(SERVER_TIME)

            val request = server.requests.single()
            assertThat(request.method).isEqualTo("POST")
            assertThat(request.path).isEqualTo("/api/tasks")
            // Имя `Token`, а не `X-Token`: это часть контракта (§4).
            assertThat(request.headers["Token"]).isEqualTo(TEST_TOKEN)
            assertThat(request.headers["X-Device-Id"]).isEqualTo(TEST_DEVICE_ID)

            // Тело upsert — массив задач (UPSERT умеет пачкой); в этом сценарии задача одна.
            val payloads = TaskmanJson.parseToJsonElement(request.body).jsonArray
            assertThat(payloads).hasSize(1)
            val payload = payloads.single().jsonObject
            // `explicitNulls = false`: null-поля сериализатор опускает, поэтому в теле нет
            // `status_date` — клиент его и не должен слать (иначе сдвигается история, §4).
            assertThat(payload.keys).containsAtLeast(
                "id",
                "list_id",
                "parent_id",
                "category_id",
                "title",
                "content",
                "status",
                "source",
                "tags",
                "created_at",
                "completed_at",
                "updated_at",
            )
            assertThat(payload).doesNotContainKey("status_date")
            assertThat(payload.string("id")).isEqualTo(TASK_ID)
            assertThat(payload.string("list_id")).isEqualTo("list-1")
            assertThat(payload.string("parent_id")).isEqualTo("parent-1")
            assertThat(payload.string("category_id")).isEqualTo("cat-1")
            assertThat(payload.string("title")).isEqualTo("Купить хлеб")
            assertThat(payload.string("content")).isEqualTo("две булки")
            assertThat(payload.string("status")).isEqualTo("completed")
            assertThat(payload.string("source")).isEqualTo("Иван")
            assertThat(payload["tags"]!!.jsonArray.map { it.jsonPrimitive.content })
                .containsExactly("tag-a", "tag-b")
                .inOrder()
            // Времена — unix-float секунды; сравниваем как числа, а не как текст литерала.
            assertThat(payload["created_at"]!!.jsonPrimitive.double).isEqualTo(1_765_800_000.0)
            assertThat(payload["completed_at"]!!.jsonPrimitive.double).isEqualTo(1_765_840_000.0)
            assertThat(payload["updated_at"]!!.jsonPrimitive.double).isEqualTo(SERVER_VERSION)
        }
    }

    @Test
    fun `409 при upsert даёт Conflict и рефетч состояния`() {
        HttpTestServer().use { server ->
            server.respond(409, """{"error":"conflict","actual_updated_at":1765843500.0}""")
            server.respond(200, STATE_JSON)
            val store = FakeStateStore().apply { putTask(fullEntity(), TAG_IDS) }
            val api = newApi(server)
            val repository = TaskRepository(api = api, stateStore = store, now = { 1L })
            val mutations = newMutations(api, store, repository)

            val outcome = runBlocking { mutations.setCompleted(TASK_ID, completed = true) }

            assertThat(outcome).isInstanceOf(MutationOutcome.Conflict::class.java)
            assertThat((outcome as MutationOutcome.Conflict).actualUpdatedAt).isEqualTo(1_765_843_500.0)

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

    @Test
    fun `DELETE api tasks id доходит до сервера и обновляет состояние`() {
        HttpTestServer().use { server ->
            server.respond(200, """{"deleted":"task-1"}""")
            server.respond(200, STATE_JSON)
            val store = FakeStateStore()
            val api = newApi(server)
            val repository = TaskRepository(api = api, stateStore = store, now = { 1L })
            val mutations = newMutations(api, store, repository)

            val outcome = runBlocking { mutations.delete(TASK_ID) }

            assertThat(outcome).isEqualTo(MutationOutcome.Success)
            val requests = server.requests
            assertThat(requests).hasSize(2)
            assertThat(requests[0].method).isEqualTo("DELETE")
            assertThat(requests[0].path).isEqualTo("/api/tasks/$TASK_ID")
            assertThat(requests[0].headers["Token"]).isEqualTo(TEST_TOKEN)
            assertThat(requests[0].headers["X-Device-Id"]).isEqualTo(TEST_DEVICE_ID)
            // Успех тоже заканчивается рефетчем: даты и историю считает сервер.
            assertThat(requests[1].method).isEqualTo("GET")
            assertThat(requests[1].path).isEqualTo("/api/state")
            assertThat(store.stored).isNotNull()
        }
    }

    /**
     * Репозиторий мутаций с очередью в памяти.
     *
     * Контракт проверяется по проводу, поэтому Room заменён подставным писателем: очередь
     * накапливает запись, а `flush` внутри `submit` тут же отправляет её тем же Retrofit-клиентом.
     */
    private fun newMutations(
        api: TaskmanApi,
        store: FakeStateStore,
        tasks: TaskRepository,
    ): TaskMutationRepository = TaskMutationRepository(
        api = api,
        reader = store,
        tasks = tasks,
        queue = OfflineMutationQueue(FakePendingMutationDao()),
        writer = FakeLocalTaskWriter(),
    )

    /** Задача со всеми заполненными полями: так payload проверяется на полноту. */
    private fun fullEntity() = taskEntity(
        id = TASK_ID,
        listId = "list-1",
        parentId = "parent-1",
        categoryId = "cat-1",
        title = "Купить хлеб",
        content = "две булки",
        status = "completed",
        source = "Иван",
        createdAt = 1_765_800_000.0,
        updatedAt = SERVER_VERSION,
        completedAt = 1_765_840_000.0,
    )

    /** Клиент с подставленным адресом сервера; кэш — свежий каталог на вызов. */
    private fun newApi(server: HttpTestServer): TaskmanApi {
        val holder = ConnectionHolder()
        holder.set(server.baseUrl(), TEST_TOKEN, TEST_DEVICE_ID)
        return NetworkFactory(freshCacheDirectory(), holder).api
    }

    private fun freshCacheDirectory(): File =
        Files.createTempDirectory("taskman-mutation-cache").toFile()

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

    private companion object {
        const val TASK_ID = "task-1"
        const val TEST_TOKEN = "tok-123"
        const val TEST_DEVICE_ID = "dev-uuid"
        const val SERVER_VERSION = 1_765_843_200.0
        const val SERVER_TIME = 1_765_843_300.0
        val TAG_IDS = listOf("tag-a", "tag-b")
    }
}

/**
 * Ответ `POST /api/tasks`: сохранённая задача и серверное время (ТЗ §6).
 */
private val UPSERT_JSON = """
    {"saved":[{"id":"task-1","updated_at":1765843300.0}],"server_time":1765843300.0}
""".trimIndent()

/**
 * Минимальное тело `/api/state` для рефетча после мутации: все секции на месте,
 * времена — unix-float секунды.
 */
private val STATE_JSON = """
    {"lists":[],"categories":[],"employees":[],"tags":[],
     "tasks":[{"id":"t1","list_id":"l1","title":"Задача","status":"created","deleted":0,
               "tags":[],"attachments":[],"history":[]}],
     "settings":{},"server_time":1765843200.0}
""".trimIndent()
