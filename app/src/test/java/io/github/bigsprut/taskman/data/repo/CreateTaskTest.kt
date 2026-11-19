package io.github.bigsprut.taskman.data.repo

import com.google.common.truth.Truth.assertThat
import io.github.bigsprut.taskman.data.remote.ApiFailure
import io.github.bigsprut.taskman.testutil.FakeLocalTaskWriter
import io.github.bigsprut.taskman.testutil.FakePendingMutationDao
import io.github.bigsprut.taskman.testutil.FakeStateStore
import io.github.bigsprut.taskman.testutil.FakeTaskmanApi
import io.github.bigsprut.taskman.testutil.errorResponseOf
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Явное создание задачи из списка (§5.3-Ф2: кнопка «+»).
 *
 * Два уровня проверки:
 * 1. [TaskPayload.newTask] — форма тела `POST /api/tasks`: `id` и `category_id` не задаём
 *    (id выдаст сервер, категорию применит по умолчанию из настроек), даты не выдумываем;
 * 2. [TaskMutationRepository.createTask] — диспетчеризация: пустое название и отсутствие
 *    списка не доходят до сервера, успех перечитывает снапшот, а **в очередь** создание
 *    не попадает — буферизовать нечего, пока сервер не выдал id.
 *
 * Фикстура — как в `TaskMutationQueueTest`: подставные API, хранилище, очередь и писатель,
 * поэтому проверяется логика, а не Room/Retrofit.
 */
class CreateTaskTest {

    private val api = FakeTaskmanApi()
    private val store = FakeStateStore()
    private val tasks = TaskRepository(api = api, stateStore = store)
    private val dao = FakePendingMutationDao()
    private val queue = OfflineMutationQueue(dao)
    private val writer = FakeLocalTaskWriter()

    private val mutations = TaskMutationRepository(
        api = api,
        reader = store,
        tasks = tasks,
        queue = queue,
        writer = writer,
    )

    // --- TaskPayload.newTask: форма тела запроса ---

    @Test
    fun `newTask собирает полный payload новой корневой задачи`() {
        val payload = TaskPayload.newTask(listId = "list-1", title = "  Помыть посуду  ")

        // id нет — его выдаст сервер; иначе upsert попытался бы обновить чужую задачу.
        assertThat(payload.id).isNull()
        assertThat(payload.listId).isEqualTo("list-1")
        assertThat(payload.parentId).isNull()
        // Категорию не задаём: сервер применит категорию по умолчанию из настроек (§4).
        assertThat(payload.categoryId).isNull()
        assertThat(payload.title).isEqualTo("Помыть посуду")
        assertThat(payload.content).isEqualTo("")
        assertThat(payload.status).isEqualTo("created")
        assertThat(payload.source).isNull()
        assertThat(payload.tags).isEmpty()
        // Новая задача: даты и версию для CAS выставляет сервер, клиент их не придумывает.
        assertThat(payload.createdAt).isNull()
        assertThat(payload.completedAt).isNull()
        assertThat(payload.statusDate).isNull()
        assertThat(payload.updatedAt).isNull()
    }

    @Test
    fun `newTask передаёт тот список, в котором создают задачу`() {
        assertThat(TaskPayload.newTask(listId = "list-2", title = "Задача").listId)
            .isEqualTo("list-2")
        // Краевые пробелы и переводы строк в названии обрезаются: сервер получает чистый текст.
        assertThat(TaskPayload.newTask(listId = "list-1", title = "\tНовая задача \n").title)
            .isEqualTo("Новая задача")
    }

    // --- createTask: диспетчеризация ---

    @Test
    fun `createTask отправляет один payload с пустым id и перечитывает снапшот`() = runTest {
        val outcome = mutations.createTask(" Новая ")

        assertThat(outcome).isEqualTo(MutationOutcome.Success)
        assertThat(api.upsertCalls).isEqualTo(1)
        // Ровно одна задача в теле: создание — не пакетная правка.
        val sent = api.lastUpserted!!.single()
        assertThat(sent.id).isNull()
        assertThat(sent.title).isEqualTo("Новая")
        assertThat(sent.listId).isEqualTo("list-1")
        // Очередь пуста: id выдаёт сервер, поэтому создание не буферизуется (§5.3-Ф4).
        assertThat(queue.pending()).isEmpty()
        // Успех — рефетч: id, даты и версию задачи считает сервер (§4).
        assertThat(api.calls).isEqualTo(1)
        assertThat(store.stored).isNotNull()
    }

    @Test
    fun `createTask не пишет новую задачу в локальный снапшот`() = runTest {
        mutations.createTask("Новая")

        // Локально создавать нечего: настоящий id и даты придут с сервера, а «пустая» строка
        // в списке разошлась бы с реальностью и не удалялась бы.
        assertThat(writer.upsertCalls).isEqualTo(0)
        assertThat(writer.upserts).isEmpty()
    }

    @Test
    fun `createTask с пустым названием не обращается к серверу`() = runTest {
        val outcome = mutations.createTask("")

        assertThat((outcome as MutationOutcome.Failed).failure).isEqualTo(ApiFailure.InvalidInput)
        assertThat(api.upsertCalls).isEqualTo(0)
        assertThat(queue.pending()).isEmpty()
    }

    @Test
    fun `createTask с названием из пробелов не обращается к серверу`() = runTest {
        val outcome = mutations.createTask("   \t ")

        assertThat((outcome as MutationOutcome.Failed).failure).isEqualTo(ApiFailure.InvalidInput)
        // Пробельное название после trim пустое — пустую задачу на сервер не отправляем.
        assertThat(api.upsertCalls).isEqualTo(0)
        assertThat(api.calls).isEqualTo(0)
        assertThat(queue.pending()).isEmpty()
    }

    @Test
    fun `createTask без списка в снапшоте возвращает внутреннюю ошибку`() = runTest {
        store.firstListId = null

        val outcome = mutations.createTask("Новая")

        // Без list_id контракт не выполнить: показываем внутреннюю ошибку, а не шлём пустой список.
        assertThat((outcome as MutationOutcome.Failed).failure)
            .isInstanceOf(ApiFailure.Internal::class.java)
        assertThat(api.upsertCalls).isEqualTo(0)
        assertThat(queue.pending()).isEmpty()
    }

    @Test
    fun `createTask без сети не копится в очереди`() = runTest {
        val boom = IOException("нет сети")
        api.upsertOutcome = { throw boom }

        val outcome = mutations.createTask("Новая")

        val failure = (outcome as MutationOutcome.Failed).failure
        assertThat(failure).isInstanceOf(ApiFailure.Network::class.java)
        assertThat((failure as ApiFailure.Network).cause).isSameInstanceAs(boom)
        // id ещё нет, значит и очередь поставить не на что: пользователь повторит создание сам.
        assertThat(queue.pending()).isEmpty()
        assertThat(api.calls).isEqualTo(0)
    }

    @Test
    fun `createTask на 500 отдаёт точный код и текст сервера`() = runTest {
        api.upsertOutcome = { errorResponseOf(500, """{"error":"boom"}""") }

        val outcome = mutations.createTask("Новая")

        val failure = (outcome as MutationOutcome.Failed).failure
        assertThat(failure).isInstanceOf(ApiFailure.Http::class.java)
        assertThat((failure as ApiFailure.Http).code).isEqualTo(500)
        // Сообщение точное: пользователь должен видеть, что ответил сервер, а не «(null)».
        assertThat(failure.serverError).isEqualTo("boom")
        assertThat(queue.pending()).isEmpty()
    }
}
