package io.github.bigsprut.taskman.data.repo

import com.google.common.truth.Truth.assertThat
import io.github.bigsprut.taskman.data.local.entity.PendingMutationEntity
import io.github.bigsprut.taskman.data.remote.ApiFailure
import io.github.bigsprut.taskman.data.remote.dto.DeleteTaskResponse
import io.github.bigsprut.taskman.data.remote.dto.UpsertTasksResponse
import io.github.bigsprut.taskman.testutil.FakeLocalTaskWriter
import io.github.bigsprut.taskman.testutil.FakePendingMutationDao
import io.github.bigsprut.taskman.testutil.FakeStateStore
import io.github.bigsprut.taskman.testutil.FakeTaskmanApi
import io.github.bigsprut.taskman.testutil.errorResponseOf
import io.github.bigsprut.taskman.testutil.successResponse
import io.github.bigsprut.taskman.testutil.taskEntity
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Отправка накопленной очереди (`flush`) — отдельно от постановки в неё (Ф4, §5.3-Ф4).
 *
 * Проверяются правила прохода по очереди, которые не видно из `submit`:
 * - первая упавшая запись не блокирует остальные навсегда: сетевой сбой оставляет **всю** пачку,
 *   сервер-отказ и конфликт снимают **только свою** запись, остальные ждут следующей попытки;
 * - каждый сервер-отказ заканчивается рефетчем состояния;
 * - битая запись (upsert без payload) снимается и не мешает отправке остальных.
 *
 * Записи кладутся в очередь напрямую: так сценарий не зависит от `submit` и его правил.
 */
class TaskMutationFlushTest {

    private val api = FakeTaskmanApi()
    private val store = FakeStateStore()
    private val tasks = TaskRepository(api = api, stateStore = store, now = { NOW_MILLIS })
    private val dao = FakePendingMutationDao()
    private val queue = OfflineMutationQueue(dao)
    private val mutations = TaskMutationRepository(
        api = api,
        reader = store,
        tasks = tasks,
        queue = queue,
        // Локальное применение в flush не участвует: очередь уже накоплена, важно лишь снять её.
        writer = FakeLocalTaskWriter(),
        now = { NOW_MILLIS },
    )

    @Test
    fun `flush на пустой очереди — Success и ни одного сетевого вызова`() = runTest {
        val outcome = mutations.flush()

        assertThat(outcome).isEqualTo(MutationOutcome.Success)
        assertThat(api.upsertCalls).isEqualTo(0)
        assertThat(api.deleteCalls).isEqualTo(0)
        // Пустая очередь — не повод перечитывать состояние.
        assertThat(api.calls).isEqualTo(0)
    }

    @Test
    fun `первая запись уходит, вторая при IOException остаётся`() = runTest {
        enqueueUpsert("task-1", createdAt = 1.0)
        enqueueUpsert("task-2", createdAt = 2.0)
        api.upsertOutcome = {
            // Первая отправка удачна, на второй «пропала сеть».
            if (api.upsertCalls == 1) {
                successResponse(UpsertTasksResponse())
            } else {
                throw IOException("нет сети")
            }
        }

        val outcome = mutations.flush()

        assertThat(outcome).isEqualTo(MutationOutcome.Queued)
        // Успешная запись снята, недоотправленная ждёт следующего раза.
        assertThat(queue.pending().map { it.taskId }).containsExactly("task-2")
        assertThat(api.upsertCalls).isEqualTo(2)
        // Сеть пропала посреди отправки — рефетча не было.
        assertThat(api.calls).isEqualTo(0)
    }

    @Test
    fun `500 на первой снимает её и оставляет вторую`() = runTest {
        enqueueUpsert("task-1", createdAt = 1.0)
        enqueueUpsert("task-2", createdAt = 2.0)
        api.upsertOutcome = { errorResponseOf(500, """{"error":"boom"}""") }

        val outcome = mutations.flush()

        assertThat(outcome).isInstanceOf(MutationOutcome.Failed::class.java)
        val failure = (outcome as MutationOutcome.Failed).failure
        assertThat(failure).isInstanceOf(ApiFailure.Http::class.java)
        assertThat((failure as ApiFailure.Http).code).isEqualTo(500)
        // Сервер-отказ не копится: отказавшая запись снята, неотправленная вторая ждёт.
        assertThat(queue.pending().map { it.taskId }).containsExactly("task-2")
        // Дальше первой отказавшей записи проход не пошёл: вторая будет обработана позже.
        assertThat(api.upsertCalls).isEqualTo(1)
        assertThat(api.calls).isEqualTo(1)
    }

    @Test
    fun `409 на первой снимает её и перечитывает состояние`() = runTest {
        enqueueUpsert("task-1", createdAt = 1.0)
        enqueueUpsert("task-2", createdAt = 2.0)
        api.upsertOutcome = {
            errorResponseOf(409, """{"error":"conflict","actual_updated_at":1765843500.0}""")
        }

        val outcome = mutations.flush()

        assertThat(outcome).isInstanceOf(MutationOutcome.Conflict::class.java)
        assertThat((outcome as MutationOutcome.Conflict).actualUpdatedAt)
            .isEqualTo(CONFLICT_VERSION)
        // Конфликтная запись снята (повтором её не вылечить), остальная очередь ждёт.
        assertThat(queue.pending().map { it.taskId }).containsExactly("task-2")
        assertThat(api.upsertCalls).isEqualTo(1)
        assertThat(api.calls).isEqualTo(1)
        assertThat(store.stored).isNotNull()
    }

    @Test
    fun `delete из очереди отправляется DELETE и снимается`() = runTest {
        queue.enqueueDelete("task-7", nowSeconds = 1.0)

        val outcome = mutations.flush()

        assertThat(outcome).isEqualTo(MutationOutcome.Success)
        assertThat(api.deleteCalls).isEqualTo(1)
        assertThat(api.lastDeletedId).isEqualTo("task-7")
        // Удалению payload не нужен: уходит только путь.
        assertThat(api.upsertCalls).isEqualTo(0)
        assertThat(queue.pending()).isEmpty()
        assertThat(api.calls).isEqualTo(1)
    }

    @Test
    fun `смешанная очередь — upsert и delete — уходит целиком и в порядке createdAt`() = runTest {
        enqueueUpsert("task-1", createdAt = 1.0)
        queue.enqueueDelete("task-2", nowSeconds = 2.0)
        val sent = mutableListOf<String>()
        api.upsertOutcome = {
            sent += api.lastUpserted!!.single().id.orEmpty()
            successResponse(UpsertTasksResponse())
        }
        api.deleteOutcome = {
            sent += api.lastDeletedId.orEmpty()
            successResponse(DeleteTaskResponse())
        }

        val outcome = mutations.flush()

        assertThat(outcome).isEqualTo(MutationOutcome.Success)
        assertThat(api.upsertCalls).isEqualTo(1)
        assertThat(api.deleteCalls).isEqualTo(1)
        assertThat(sent).containsExactly("task-1", "task-2").inOrder()
        assertThat(queue.pending()).isEmpty()
        assertThat(api.calls).isEqualTo(1)
    }

    @Test
    fun `битая запись upsert без payload снимается и не блокирует очередь`() = runTest {
        dao.upsert(
            PendingMutationEntity(
                taskId = "task-1",
                kind = PendingMutationEntity.KIND_UPSERT,
                payloadJson = null,
                baseUpdatedAt = null,
                createdAt = 1.0,
            ),
        )
        queue.enqueueDelete("task-2", nowSeconds = 2.0)

        val outcome = mutations.flush()

        // Нечего отправлять — запись снимается, а не крутит очередь вечно.
        assertThat(outcome).isEqualTo(MutationOutcome.Success)
        assertThat(api.upsertCalls).isEqualTo(0)
        assertThat(api.deleteCalls).isEqualTo(1)
        assertThat(queue.pending()).isEmpty()
    }

    /** Кладёт в очередь upsert существующей задачи так, будто он поставлен раньше. */
    private suspend fun enqueueUpsert(taskId: String, createdAt: Double) {
        queue.enqueueUpsert(
            TaskPayload.fromEntity(taskEntity(id = taskId), tagIds = emptyList()),
            nowSeconds = createdAt,
        )
    }

    private companion object {
        /** Версия, которую сервер вернул в 409 (`actual_updated_at`, §4). */
        const val CONFLICT_VERSION = 1_765_843_500.0

        const val NOW_MILLIS = 1_700_000_000_000L
    }
}
