package io.github.bigsprut.taskman.data.repo

import com.google.common.truth.Truth.assertThat
import io.github.bigsprut.taskman.data.local.entity.PendingMutationEntity
import io.github.bigsprut.taskman.data.remote.ApiFailure
import io.github.bigsprut.taskman.data.remote.TaskmanJson
import io.github.bigsprut.taskman.data.remote.dto.TaskInDto
import io.github.bigsprut.taskman.data.remote.dto.UpsertTasksResponse
import io.github.bigsprut.taskman.domain.TaskDraft
import io.github.bigsprut.taskman.domain.TaskStatus
import io.github.bigsprut.taskman.testutil.FakeLocalTaskWriter
import io.github.bigsprut.taskman.testutil.FakePendingMutationDao
import io.github.bigsprut.taskman.testutil.FakeStateStore
import io.github.bigsprut.taskman.testutil.FakeTaskmanApi
import io.github.bigsprut.taskman.testutil.errorResponseOf
import io.github.bigsprut.taskman.testutil.successResponse
import io.github.bigsprut.taskman.testutil.taskEntity
import java.io.IOException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Мутации задачи с офлайн-очередью (Ф4, ТЗ §5.3-Ф4, §9-Ф4).
 *
 * Главное правило фазы: **в очередь попадают только сетевые сбои** (`IOException`). Сервер-отказ
 * (4xx) снимает запись и отдаётся в UI точной причиной; 409 — снимает, перечитывает состояние и
 * сообщает конфликт; 401/403 `device` — оставляет запись до входа/подтверждения устройства.
 *
 * Отдельно проверяется оптимистичность: правка применяется к локальному хранилищу
 * ([FakeLocalTaskWriter]) **до** попытки отправки, поэтому офлайн-режим показывает результат
 * сразу, а Room остаётся источником правды для UI.
 *
 * Сервер и хранилище подставные: проверяется логика диспетчера, а не Room или Retrofit.
 */
class TaskMutationQueueTest {

    private val api = FakeTaskmanApi()
    private val store = FakeStateStore()
    private val tasks = TaskRepository(api = api, stateStore = store, now = { NOW_MILLIS })
    private val dao = FakePendingMutationDao()
    private val queue = OfflineMutationQueue(dao)
    private val writer = FakeLocalTaskWriter()

    /** Сколько раз просили фоновую отправку: страховка WorkManager'ом (§5.3-Ф4). */
    private var backgroundSyncCalls: Int = 0

    /** Подменяемые часы в миллисекундах: между правками можно «перевести время» вперёд. */
    private var nowMillis: Long = NOW_MILLIS

    private val mutations = TaskMutationRepository(
        api = api,
        reader = store,
        tasks = tasks,
        queue = queue,
        writer = writer,
        now = { nowMillis },
        requestBackgroundSync = { backgroundSyncCalls += 1 },
    )

    /** Версия задачи на момент последнего `/api/state` — её и должен унести payload. */
    private val serverVersion = 1_765_843_200.0

    private val entity = taskEntity(
        id = TASK_ID,
        listId = "list-1",
        title = "Помыть посуду",
        status = "created",
        createdAt = 1_765_800_000.0,
        updatedAt = serverVersion,
    )

    private val secondEntity = taskEntity(
        id = SECOND_ID,
        listId = "list-1",
        title = "Вторая задача",
        status = "created",
        createdAt = 1_765_800_000.0,
        updatedAt = serverVersion,
    )

    @Test
    fun `setCompleted без сети копит правку в очередь и применяет её локально`() = runTest {
        store.putTask(entity, tagIds = listOf("tag-a"))
        api.upsertOutcome = { throw IOException("нет сети") }

        val outcome = mutations.setCompleted(TASK_ID, completed = true)

        assertThat(outcome).isEqualTo(MutationOutcome.Queued)
        // Локально правка уже применена: Room — источник правды для UI, чекбокс не откатывается.
        assertThat(writer.upsertCalls).isEqualTo(1)
        val local = writer.lastUpsert!!
        assertThat(local.status).isEqualTo("completed")
        assertThat(local.completedAt).isEqualTo(NOW_SECONDS)
        assertThat(local.statusDateTs).isEqualTo(NOW_SECONDS)

        val record = queue.pending().single()
        assertThat(record.taskId).isEqualTo(TASK_ID)
        assertThat(record.kind).isEqualTo(PendingMutationEntity.KIND_UPSERT)
        assertThat(record.baseUpdatedAt).isEqualTo(serverVersion)
        assertThat(record.createdAt).isEqualTo(NOW_SECONDS)

        // Версия в payload — та же, что зафиксирована в baseUpdatedAt (CAS, §4).
        val payload = record.payload()
        assertThat(payload.id).isEqualTo(TASK_ID)
        assertThat(payload.status).isEqualTo("completed")
        assertThat(payload.tags).containsExactly("tag-a")
        assertThat(payload.updatedAt).isEqualTo(serverVersion)

        // Баннер «есть неотправленные изменения» растёт сразу...
        assertThat(mutations.observePendingCount().first()).isEqualTo(1)
        // ...и запрошена фоновая отправка: очередь дошлётся, даже если приложение закроют.
        assertThat(backgroundSyncCalls).isEqualTo(1)
        // Сети не было — рефетча тоже нет.
        assertThat(api.calls).isEqualTo(0)
    }

    @Test
    fun `delete без сети скрывает задачу локально и копит delete`() = runTest {
        store.putTask(entity)
        api.deleteOutcome = { throw IOException("нет сети") }

        val outcome = mutations.delete(TASK_ID)

        assertThat(outcome).isEqualTo(MutationOutcome.Queued)
        // Локально поддерево сразу помечено удалённым: задача исчезает из списка без сети.
        assertThat(writer.deleteCalls).isEqualTo(1)
        assertThat(writer.lastDeletedId).isEqualTo(TASK_ID)
        // Удаление не upsert'ит задачу — на сервер уйдёт DELETE, а не запись со статусом.
        assertThat(writer.upsertCalls).isEqualTo(0)

        val record = queue.pending().single()
        assertThat(record.taskId).isEqualTo(TASK_ID)
        assertThat(record.kind).isEqualTo(PendingMutationEntity.KIND_DELETE)
        assertThat(record.payloadJson).isNull()
        assertThat(api.calls).isEqualTo(0)
    }

    @Test
    fun `сервер-отказ 500 не копится в очереди и даёт точный код`() = runTest {
        store.putTask(entity)
        api.upsertOutcome = { errorResponseOf(500, """{"error":"boom"}""") }

        val outcome = mutations.setCompleted(TASK_ID, completed = true)

        assertThat(outcome).isInstanceOf(MutationOutcome.Failed::class.java)
        val failure = (outcome as MutationOutcome.Failed).failure
        assertThat(failure).isInstanceOf(ApiFailure.Http::class.java)
        assertThat((failure as ApiFailure.Http).code).isEqualTo(500)
        assertThat(failure.serverError).isEqualTo("boom")
        // Сервер-отказ не копится (§5.3-Ф4): запись снята, иначе она отправлялась бы бесконечно.
        assertThat(queue.pending()).isEmpty()
        // Отказ — повод перечитать состояние, а не жить с оптимистичной правкой.
        assertThat(api.calls).isEqualTo(1)
        assertThat(store.stored).isNotNull()
    }

    @Test
    fun `409 снимает запись, перечитывает состояние и отдаёт актуальную версию`() = runTest {
        store.putTask(entity)
        api.upsertOutcome = {
            errorResponseOf(409, """{"error":"conflict","actual_updated_at":1765843500.0}""")
        }

        val outcome = mutations.setCompleted(TASK_ID, completed = true)

        assertThat(outcome).isInstanceOf(MutationOutcome.Conflict::class.java)
        assertThat((outcome as MutationOutcome.Conflict).actualUpdatedAt)
            .isEqualTo(CONFLICT_VERSION)
        // Конфликт не лечится повтором: запись снята, состояние перечитано (§4).
        assertThat(queue.pending()).isEmpty()
        assertThat(api.calls).isEqualTo(1)
        assertThat(store.stored).isNotNull()
    }

    @Test
    fun `404 при удалении не копится в очереди`() = runTest {
        store.putTask(entity)
        api.deleteOutcome = { errorResponseOf(404, """{"error":"not_found"}""") }

        val outcome = mutations.delete(TASK_ID)

        assertThat(outcome).isInstanceOf(MutationOutcome.Failed::class.java)
        val failure = (outcome as MutationOutcome.Failed).failure
        assertThat(failure).isInstanceOf(ApiFailure.Http::class.java)
        assertThat((failure as ApiFailure.Http).code).isEqualTo(404)
        // 4xx сервер-отказ тоже не копится: иначе очередь застряла бы навсегда.
        assertThat(queue.pending()).isEmpty()
    }

    @Test
    fun `401 оставляет запись в очереди и сообщает об отказе авторизации`() = runTest {
        store.putTask(entity)
        api.upsertOutcome = { errorResponseOf(401, """{"error":"unauthorized"}""") }
        // Коллектор встаёт до мутации: SharedFlow без replay не отдаёт событие задним числом.
        val event = async(start = CoroutineStart.UNDISPATCHED) { tasks.authFailures.first() }

        val outcome = mutations.setCompleted(TASK_ID, completed = true)

        assertThat(outcome).isInstanceOf(MutationOutcome.Failed::class.java)
        assertThat((outcome as MutationOutcome.Failed).failure).isEqualTo(ApiFailure.Unauthorized)
        // После входа запись отправится: терять правку из-за истёкшего токена нельзя.
        assertThat(queue.pending().single().taskId).isEqualTo(TASK_ID)
        assertThat(event.await()).isEqualTo(ApiFailure.Unauthorized)
    }

    @Test
    fun `сеть есть — правка успешна, очередь пуста и состояние перечитано`() = runTest {
        store.putTask(entity)

        val outcome = mutations.setCompleted(TASK_ID, completed = true)

        assertThat(outcome).isEqualTo(MutationOutcome.Success)
        assertThat(writer.upsertCalls).isEqualTo(1)
        assertThat(queue.pending()).isEmpty()
        assertThat(api.upsertCalls).isEqualTo(1)
        assertThat(api.lastUpserted!!.single().status).isEqualTo("completed")
        // После успеха сервер — источник правды: даты и историю считает он (§4).
        assertThat(api.calls).isEqualTo(1)
        assertThat(store.stored).isNotNull()
    }

    @Test
    fun `две задачи из очереди отправляются по порядку и обе снимаются`() = runTest {
        store.putTask(entity)
        store.putTask(secondEntity)
        api.upsertOutcome = { throw IOException("нет сети") }

        assertThat(mutations.setCompleted(TASK_ID, completed = true))
            .isEqualTo(MutationOutcome.Queued)
        nowMillis += STEP_MILLIS
        assertThat(mutations.setCompleted(SECOND_ID, completed = true))
            .isEqualTo(MutationOutcome.Queued)
        assertThat(queue.pending().map { it.taskId })
            .containsExactly(TASK_ID, SECOND_ID)
            .inOrder()

        // Сеть вернулась. Порядок отправки пишем сами: подставной API хранит только последний payload.
        val sent = mutableListOf<String>()
        api.upsertOutcome = {
            sent += api.lastUpserted!!.single().id.orEmpty()
            successResponse(UpsertTasksResponse())
        }

        val outcome = mutations.flush()

        assertThat(outcome).isEqualTo(MutationOutcome.Success)
        // Порядок — по createdAt: сервер получает правки в том порядке, в каком их сделали.
        assertThat(sent).containsExactly(TASK_ID, SECOND_ID).inOrder()
        assertThat(queue.pending()).isEmpty()
        assertThat(api.calls).isEqualTo(1)
    }

    @Test
    fun `после Queued возврат сети отправляет запись с версией из baseUpdatedAt`() = runTest {
        store.putTask(entity)
        val onlineOutcome = api.upsertOutcome
        api.upsertOutcome = { throw IOException("нет сети") }

        assertThat(mutations.setCompleted(TASK_ID, completed = true))
            .isEqualTo(MutationOutcome.Queued)
        val queued = queue.pending().single()

        // Снапшот успел обновиться (например, пришёл ответ фонового запроса): версия задачи
        // в Room теперь другая. Payload обязан нести baseUpdatedAt, иначе сервер ответит ложным 409.
        store.putTask(entity.copy(updatedAt = serverVersion + NEWER_VERSION_DELTA))
        api.upsertOutcome = onlineOutcome

        val outcome = mutations.flush()

        assertThat(outcome).isEqualTo(MutationOutcome.Success)
        assertThat(queue.pending()).isEmpty()
        val sentPayload = api.lastUpserted!!.single()
        assertThat(sentPayload.updatedAt).isEqualTo(queued.baseUpdatedAt)
        assertThat(sentPayload.status).isEqualTo("completed")
        assertThat(api.calls).isEqualTo(1)
    }

    @Test
    fun `saveDraft без сети сохраняет черновик локально и копит upsert`() = runTest {
        store.putTask(entity)
        api.upsertOutcome = { throw IOException("нет сети") }
        val draft = TaskDraft(
            id = TASK_ID,
            listId = "list-1",
            parentId = null,
            title = "Купить молоко",
            content = "**две** булки",
            status = TaskStatus.IN_PROGRESS,
            categoryId = "cat-1",
            tagIds = setOf("tag-a"),
            source = "Иван",
            createdAt = 1_700_000_000.0,
            updatedAt = serverVersion,
            statusDateTs = CHOSEN_STATUS_DATE,
        )

        val outcome = mutations.saveDraft(draft)

        assertThat(outcome).isEqualTo(MutationOutcome.Queued)
        val local = writer.lastUpsert!!
        assertThat(local.title).isEqualTo("Купить молоко")
        assertThat(local.content).isEqualTo("**две** булки")
        assertThat(local.status).isEqualTo("inprogress")
        assertThat(local.categoryId).isEqualTo("cat-1")
        // Поля, которых в черновике нет, берутся из прежней сущности — иначе сервер обнулит их.
        assertThat(local.createdAt).isEqualTo(1_700_000_000.0)
        // Выбранную «задним числом» дату статуса локальная сущность тоже помнит (§5.3-Ф2).
        assertThat(local.statusDateTs).isEqualTo(CHOSEN_STATUS_DATE)

        val payload = queue.pending().single().payload()
        assertThat(payload.title).isEqualTo("Купить молоко")
        assertThat(payload.tags).containsExactly("tag-a")
        assertThat(payload.statusDate).isEqualTo(CHOSEN_STATUS_DATE)
        assertThat(payload.updatedAt).isEqualTo(serverVersion)
        assertThat(api.calls).isEqualTo(0)
    }

    @Test
    fun `renameSubtask без сети копит upsert с новым названием`() = runTest {
        val child = taskEntity(id = CHILD_ID, parentId = TASK_ID, title = "Старое имя")
        store.putTask(child)
        api.upsertOutcome = { throw IOException("нет сети") }

        val outcome = mutations.renameSubtask(CHILD_ID, "Новое имя")

        assertThat(outcome).isEqualTo(MutationOutcome.Queued)
        // Переименование подзадачи — обычный upsert полного объекта, поэтому копится.
        assertThat(writer.lastUpsert!!.title).isEqualTo("Новое имя")
        val record = queue.pending().single()
        assertThat(record.taskId).isEqualTo(CHILD_ID)
        assertThat(record.payload().title).isEqualTo("Новое имя")
        assertThat(record.payload().parentId).isEqualTo(TASK_ID)
    }

    @Test
    fun `addSubtask без сети не копится в очереди`() = runTest {
        store.putTask(entity)
        val boom = IOException("нет сети")
        api.upsertOutcome = { throw boom }

        val outcome = mutations.addSubtask(TASK_ID, "Купить хлеб")

        assertThat(outcome).isInstanceOf(MutationOutcome.Failed::class.java)
        val failure = (outcome as MutationOutcome.Failed).failure
        assertThat(failure).isInstanceOf(ApiFailure.Network::class.java)
        assertThat((failure as ApiFailure.Network).cause).isSameInstanceAs(boom)
        // id новой подзадачи выдаёт сервер — локально его не придумать, значит и в очередь
        // такую мутацию поставить не с чем (§5.3-Ф4, ограничение фазы).
        assertThat(queue.pending()).isEmpty()
        assertThat(writer.upsertCalls).isEqualTo(0)
        assertThat(api.calls).isEqualTo(0)
    }

    @Test
    fun `офлайн-правка и удаление одной задачи схлопываются в одну запись delete`() = runTest {
        store.putTask(entity)
        api.upsertOutcome = { throw IOException("нет сети") }
        api.deleteOutcome = { throw IOException("нет сети") }

        assertThat(mutations.setCompleted(TASK_ID, completed = true))
            .isEqualTo(MutationOutcome.Queued)
        nowMillis += STEP_MILLIS
        assertThat(mutations.delete(TASK_ID)).isEqualTo(MutationOutcome.Queued)

        val record = queue.pending().single()
        assertThat(record.kind).isEqualTo(PendingMutationEntity.KIND_DELETE)
        // Время первой правки сохранено: очередь не «омолаживается» повторами.
        assertThat(record.createdAt).isEqualTo(NOW_SECONDS)
        // Обе правки применены локально: сначала новый статус, затем скрытие поддерева.
        assertThat(writer.upserts).hasSize(1)
        assertThat(writer.deleteCalls).isEqualTo(1)
    }

    /** Payload записи очереди в виде DTO: то же, что уйдёт на сервер при отправке. */
    private fun PendingMutationEntity.payload(): TaskInDto =
        TaskmanJson.decodeFromString(payloadJson!!)

    private companion object {
        const val TASK_ID = "task-1"
        const val SECOND_ID = "task-2"
        const val CHILD_ID = "task-3"

        /** «Сейчас» в миллисекундах: ровно NOW_SECONDS в секундах, без дробей. */
        const val NOW_MILLIS = 1_700_000_000_000L
        const val NOW_SECONDS = 1_700_000_000.0
        const val STEP_MILLIS = 1_000L

        /** Версия, которую сервер вернул в 409 (`actual_updated_at`, §4). */
        const val CONFLICT_VERSION = 1_765_843_500.0

        /** Насколько «новее» версия в обновлённом снапшоте — для проверки CAS из очереди. */
        const val NEWER_VERSION_DELTA = 100.0

        /** Дата статуса, выбранная пользователем «задним числом» (unix-секунды). */
        const val CHOSEN_STATUS_DATE = 1_795_120_000.0
    }
}
