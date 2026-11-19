package io.github.bigsprut.taskman.data.repo

import com.google.common.truth.Truth.assertThat
import io.github.bigsprut.taskman.data.remote.ApiFailure
import io.github.bigsprut.taskman.domain.TaskStatus
import io.github.bigsprut.taskman.testutil.FakeStateStore
import io.github.bigsprut.taskman.testutil.FakeTaskmanApi
import io.github.bigsprut.taskman.testutil.errorResponse
import io.github.bigsprut.taskman.testutil.stateResponse
import io.github.bigsprut.taskman.testutil.successResponse
import io.github.bigsprut.taskman.testutil.task
import io.github.bigsprut.taskman.testutil.taskDto
import java.io.IOException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import org.junit.Test

/**
 * Исходы `TaskRepository.refresh()` и поведение статуса синхронизации (ТЗ §6).
 */
class TaskRepositoryTest {

    private val api = FakeTaskmanApi()
    private val store = FakeStateStore()
    private val repository = TaskRepository(api = api, stateStore = store, now = { NOW_MILLIS })

    @Test
    fun `успешный refresh сохраняет снапшот и время успеха`() = runTest {
        api.outcome = {
            successResponse(stateResponse(tasks = listOf(taskDto(id = "a"), taskDto(id = "b"))))
        }

        val outcome = repository.refresh()

        assertThat(outcome).isEqualTo(SyncOutcome.Success(taskCount = 2))
        assertThat(api.calls).isEqualTo(1)
        assertThat(store.stored).isNotNull()
        assertThat(store.stored!!.tasks).hasSize(2)
        assertThat(repository.syncStatus.value.hasAttempted).isTrue()
        assertThat(repository.syncStatus.value.isRefreshing).isFalse()
        assertThat(repository.syncStatus.value.lastFailure).isNull()
        assertThat(repository.syncStatus.value.lastSuccessAtMillis).isEqualTo(NOW_MILLIS)
    }

    @Test
    fun `401 даёт Unauthorized и не сохраняет снапшот`() = runTest {
        api.outcome = { errorResponse(401, """{"error":"unauthorized"}""") }

        val outcome = repository.refresh()

        assertThat(outcome).isEqualTo(SyncOutcome.Unauthorized)
        assertThat(store.stored).isNull()
        assertThat(repository.syncStatus.value.lastFailure).isEqualTo(ApiFailure.Unauthorized)
        assertThat(repository.syncStatus.value.lastSuccessAtMillis).isNull()
    }

    @Test
    fun `403 device даёт DeviceNotApproved`() = runTest {
        api.outcome = { errorResponse(403, """{"error":"device"}""") }

        val outcome = repository.refresh()

        assertThat(outcome).isEqualTo(SyncOutcome.DeviceNotApproved)
        assertThat(store.stored).isNull()
        assertThat(repository.syncStatus.value.lastFailure)
            .isEqualTo(ApiFailure.DeviceNotApproved)
    }

    @Test
    fun `500 даёт Failed с кодом и телом ошибки`() = runTest {
        api.outcome = { errorResponse(500, """{"error":"boom"}""") }

        val outcome = repository.refresh()

        assertThat(outcome).isInstanceOf(SyncOutcome.Failed::class.java)
        val failure = (outcome as SyncOutcome.Failed).failure
        assertThat(failure).isInstanceOf(ApiFailure.Http::class.java)
        val http = failure as ApiFailure.Http
        assertThat(http.code).isEqualTo(500)
        assertThat(http.serverError).isEqualTo("boom")
        assertThat(store.stored).isNull()
        assertThat(repository.syncStatus.value.lastFailure).isSameInstanceAs(failure)
    }

    @Test
    fun `IOException даёт сетевую ошибку`() = runTest {
        val boom = IOException("сеть недоступна")
        api.outcome = { throw boom }

        val outcome = repository.refresh()

        assertThat(outcome).isInstanceOf(SyncOutcome.Failed::class.java)
        val failure = (outcome as SyncOutcome.Failed).failure
        assertThat(failure).isInstanceOf(ApiFailure.Network::class.java)
        assertThat((failure as ApiFailure.Network).cause).isSameInstanceAs(boom)
        assertThat(store.stored).isNull()
    }

    @Test
    fun `неразбираемое тело даёт Malformed`() = runTest {
        val boom = SerializationException("битый JSON")
        api.outcome = { throw boom }

        val outcome = repository.refresh()

        assertThat(outcome).isInstanceOf(SyncOutcome.Failed::class.java)
        val failure = (outcome as SyncOutcome.Failed).failure
        assertThat(failure).isInstanceOf(ApiFailure.Malformed::class.java)
        assertThat((failure as ApiFailure.Malformed).cause).isSameInstanceAs(boom)
        assertThat(store.stored).isNull()
        assertThat(repository.syncStatus.value.lastFailure).isSameInstanceAs(failure)
    }

    @Test
    fun `observeTasks отдаёт задачи в порядке статусов`() = runTest {
        store.tasksFlow.value = listOf(
            task("canceled", TaskStatus.CANCELED, statusDateTs = 500.0),
            task("completed", TaskStatus.COMPLETED, statusDateTs = 400.0),
            task("created-old", TaskStatus.CREATED, statusDateTs = 100.0),
            task("inprogress", TaskStatus.IN_PROGRESS, statusDateTs = 50.0),
            task("created-new", TaskStatus.CREATED, statusDateTs = 200.0),
        )

        val tasks = repository.observeTasks().first()

        assertThat(tasks.map { it.id })
            .containsExactly("inprogress", "created-new", "created-old", "completed", "canceled")
            .inOrder()
    }

    @Test
    fun `успешный refresh после ошибки очищает lastFailure`() = runTest {
        api.outcome = { errorResponse(500, """{"error":"boom"}""") }
        repository.refresh()
        assertThat(repository.syncStatus.value.lastFailure)
            .isInstanceOf(ApiFailure.Http::class.java)

        api.outcome = { successResponse(stateResponse(tasks = listOf(taskDto(id = "a")))) }
        val outcome = repository.refresh()

        assertThat(outcome).isEqualTo(SyncOutcome.Success(taskCount = 1))
        assertThat(repository.syncStatus.value.lastFailure).isNull()
        assertThat(repository.syncStatus.value.lastSuccessAtMillis).isEqualTo(NOW_MILLIS)
        assertThat(api.calls).isEqualTo(2)
    }

    @Test
    fun `clearLocalData очищает кэш и сбрасывает syncStatus`() = runTest {
        api.outcome = { successResponse(stateResponse(tasks = listOf(taskDto(id = "a")))) }
        repository.refresh()
        assertThat(store.stored).isNotNull()

        repository.clearLocalData()

        assertThat(store.clearCalls).isEqualTo(1)
        assertThat(store.stored).isNull()
        assertThat(store.tasksFlow.value).isEmpty()
        assertThat(repository.syncStatus.value).isEqualTo(SyncStatus())
    }

    @Test
    fun `неполный снапшот без server_time считается Malformed и не стирает кэш`() = runTest {
        // 200 с `{}` (прокси, баг сервера) не должен заменить снапшот пустотой.
        store.stored = StateSnapshot(tasks = listOf(previousSnapshotTask()))
        api.outcome = {
            successResponse(stateResponse(tasks = emptyList(), serverTime = null))
        }

        val outcome = repository.refresh()

        assertThat(outcome).isInstanceOf(SyncOutcome.Failed::class.java)
        assertThat((outcome as SyncOutcome.Failed).failure)
            .isInstanceOf(ApiFailure.Malformed::class.java)
        assertThat(store.stored!!.tasks).hasSize(1)
    }

    @Test
    fun `сбой локального хранилища даёт Internal, а не Malformed`() = runTest {
        val storage = IllegalStateException("диск недоступен")
        api.outcome = { successResponse(stateResponse(tasks = listOf(taskDto(id = "a")))) }
        store.failOnReplace = storage

        val outcome = repository.refresh()

        assertThat(outcome).isInstanceOf(SyncOutcome.Failed::class.java)
        val failure = (outcome as SyncOutcome.Failed).failure
        assertThat(failure).isInstanceOf(ApiFailure.Internal::class.java)
        assertThat((failure as ApiFailure.Internal).cause).isSameInstanceAs(storage)
    }

    private fun previousSnapshotTask() = io.github.bigsprut.taskman.data.local.entity.TaskEntity(
        id = "cached",
        listId = "list",
        parentId = null,
        categoryId = null,
        title = "Сохранённая задача",
        content = "",
        status = "created",
        source = null,
        createdAt = 1.0,
        updatedAt = 1.0,
        completedAt = null,
        statusDateTs = 1.0,
        deleted = false,
    )

    private companion object {
        /** Фиксированное «сейчас», чтобы не зависеть от системных часов. */
        const val NOW_MILLIS = 1_700_000_000_000L
    }
}
