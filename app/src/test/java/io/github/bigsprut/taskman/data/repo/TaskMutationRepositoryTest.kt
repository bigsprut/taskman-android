package io.github.bigsprut.taskman.data.repo

import com.google.common.truth.Truth.assertThat
import io.github.bigsprut.taskman.data.remote.ApiFailure
import io.github.bigsprut.taskman.testutil.FakeStateStore
import io.github.bigsprut.taskman.testutil.FakeTaskmanApi
import io.github.bigsprut.taskman.testutil.errorResponseOf
import io.github.bigsprut.taskman.testutil.taskEntity
import java.io.IOException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Мутации задачи: чекбокс «Выполнена ↔ Создана» и удаление (ТЗ §5.3-Ф1, §4).
 *
 * Проверяется не только исход, но и то, что уходит на сервер: payload собирается из полной
 * локальной сущности, `updated_at` — из снапшота (CAS), а после успеха/конфликта состояние
 * перечитывается `/api/state`. Отказы авторизации уходят в поток `authFailures`, чтобы
 * приложение увело пользователя на вход, а не показывало «не удалось».
 */
class TaskMutationRepositoryTest {

    private val api = FakeTaskmanApi()
    private val store = FakeStateStore()
    private val repository = TaskRepository(api = api, stateStore = store, now = { NOW_MILLIS })
    private val mutations = TaskMutationRepository(api = api, reader = store, tasks = repository)

    /** Версия задачи на момент последнего `/api/state` — её и должен унести payload. */
    private val serverVersion = 1_765_843_200.0

    private val entity = taskEntity(
        id = TASK_ID,
        listId = "list-1",
        title = "Помыть посуду",
        status = "created",
        source = "Иван",
        createdAt = 1_765_800_000.0,
        updatedAt = serverVersion,
    )

    @Test
    fun `setCompleted отправляет один payload с новым статусом и версией из снапшота`() = runTest {
        store.putTask(entity, tagIds = listOf("tag-a"))

        val outcome = mutations.setCompleted(TASK_ID, completed = true)

        assertThat(outcome).isEqualTo(MutationOutcome.Success)
        assertThat(api.upsertCalls).isEqualTo(1)
        val payload = api.lastUpserted!!.single()
        assertThat(payload.id).isEqualTo(TASK_ID)
        assertThat(payload.status).isEqualTo("completed")
        assertThat(payload.updatedAt).isEqualTo(serverVersion)
        assertThat(payload.tags).containsExactly("tag-a")
        assertThat(payload.title).isEqualTo("Помыть посуду")

        // После успеха сервер — источник правды: состояние перечитано и сохранено в кэш.
        assertThat(api.calls).isEqualTo(1)
        assertThat(store.stored).isNotNull()
    }

    @Test
    fun `снятие чекбокса возвращает задачу в created`() = runTest {
        store.putTask(entity)

        val outcome = mutations.setCompleted(TASK_ID, completed = false)

        assertThat(outcome).isEqualTo(MutationOutcome.Success)
        val payload = api.lastUpserted!!.single()
        assertThat(payload.status).isEqualTo("created")
        assertThat(payload.completedAt).isNull()
    }

    @Test
    fun `409 даёт Conflict с версией сервера и перечитывает снапшот`() = runTest {
        store.putTask(entity)
        api.upsertOutcome = {
            errorResponseOf(409, """{"error":"conflict","actual_updated_at":1765843500.0}""")
        }

        val outcome = mutations.setCompleted(TASK_ID, completed = true)

        assertThat(outcome).isInstanceOf(MutationOutcome.Conflict::class.java)
        assertThat((outcome as MutationOutcome.Conflict).actualUpdatedAt).isEqualTo(1_765_843_500.0)
        // Без рефетча UI показывал бы устаревшую версию задачи, поэтому refresh обязателен.
        assertThat(api.calls).isEqualTo(1)
        assertThat(store.stored).isNotNull()
    }

    @Test
    fun `401 даёт Unauthorized и событие в authFailures`() = runTest {
        store.putTask(entity)
        api.upsertOutcome = { errorResponseOf(401, """{"error":"unauthorized"}""") }
        // Коллектор встаёт до мутации: SharedFlow без replay не отдаёт событие задним числом.
        // UNDISPATCHED запускает корутину сразу до первого приостановления — то есть подписка
        // успевает произойти синхронно, без обращения к экспериментальному runCurrent.
        val event = async(start = CoroutineStart.UNDISPATCHED) { repository.authFailures.first() }

        val outcome = mutations.setCompleted(TASK_ID, completed = true)

        assertThat(outcome).isInstanceOf(MutationOutcome.Failed::class.java)
        assertThat((outcome as MutationOutcome.Failed).failure).isEqualTo(ApiFailure.Unauthorized)
        assertThat(event.await()).isEqualTo(ApiFailure.Unauthorized)
    }

    @Test
    fun `403 device даёт DeviceNotApproved и событие в authFailures`() = runTest {
        store.putTask(entity)
        api.upsertOutcome = { errorResponseOf(403, """{"error":"device"}""") }
        val event = async(start = CoroutineStart.UNDISPATCHED) { repository.authFailures.first() }

        val outcome = mutations.setCompleted(TASK_ID, completed = true)

        assertThat(outcome).isInstanceOf(MutationOutcome.Failed::class.java)
        assertThat((outcome as MutationOutcome.Failed).failure)
            .isEqualTo(ApiFailure.DeviceNotApproved)
        assertThat(event.await()).isEqualTo(ApiFailure.DeviceNotApproved)
    }

    @Test
    fun `500 даёт Failed с кодом ответа`() = runTest {
        store.putTask(entity)
        api.upsertOutcome = { errorResponseOf(500, """{"error":"boom"}""") }

        val outcome = mutations.setCompleted(TASK_ID, completed = true)

        assertThat(outcome).isInstanceOf(MutationOutcome.Failed::class.java)
        val failure = (outcome as MutationOutcome.Failed).failure
        assertThat(failure).isInstanceOf(ApiFailure.Http::class.java)
        assertThat((failure as ApiFailure.Http).code).isEqualTo(500)
        assertThat(failure.serverError).isEqualTo("boom")
    }

    @Test
    fun `IOException даёт Failed Network`() = runTest {
        store.putTask(entity)
        val boom = IOException("сеть недоступна")
        api.upsertOutcome = { throw boom }

        val outcome = mutations.setCompleted(TASK_ID, completed = true)

        assertThat(outcome).isInstanceOf(MutationOutcome.Failed::class.java)
        val failure = (outcome as MutationOutcome.Failed).failure
        assertThat(failure).isInstanceOf(ApiFailure.Network::class.java)
        assertThat((failure as ApiFailure.Network).cause).isSameInstanceAs(boom)
    }

    @Test
    fun `delete отправляет DELETE именно с этим id и перечитывает снапшот`() = runTest {
        val outcome = mutations.delete(TASK_ID)

        assertThat(outcome).isEqualTo(MutationOutcome.Success)
        assertThat(api.deleteCalls).isEqualTo(1)
        assertThat(api.lastDeletedId).isEqualTo(TASK_ID)
        // Удаление не собирает payload задачи — уходит только путь.
        assertThat(api.upsertCalls).isEqualTo(0)
        assertThat(api.calls).isEqualTo(1)
        assertThat(store.stored).isNotNull()
    }

    @Test
    fun `404 при удалении даёт Failed с кодом ответа`() = runTest {
        api.deleteOutcome = { errorResponseOf(404, """{"error":"not_found"}""") }

        val outcome = mutations.delete(TASK_ID)

        assertThat(outcome).isInstanceOf(MutationOutcome.Failed::class.java)
        val failure = (outcome as MutationOutcome.Failed).failure
        assertThat(failure).isInstanceOf(ApiFailure.Http::class.java)
        assertThat((failure as ApiFailure.Http).code).isEqualTo(404)
    }

    @Test
    fun `задача отсутствует в локальном снапшоте — Internal и без запроса`() = runTest {
        // Мутация с неизвестным id — ошибка клиента, а не сетевой сбой: payload собрать не из чего.
        val outcome = mutations.setCompleted("missing", completed = true)

        assertThat(outcome).isInstanceOf(MutationOutcome.Failed::class.java)
        val failure = (outcome as MutationOutcome.Failed).failure
        assertThat(failure).isInstanceOf(ApiFailure.Internal::class.java)
        assertThat(api.upsertCalls).isEqualTo(0)
    }

    private companion object {
        const val TASK_ID = "task-1"

        /** Фиксированное «сейчас», чтобы не зависеть от системных часов. */
        const val NOW_MILLIS = 1_700_000_000_000L
    }
}
