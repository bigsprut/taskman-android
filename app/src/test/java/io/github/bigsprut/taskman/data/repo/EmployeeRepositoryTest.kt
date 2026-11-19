package io.github.bigsprut.taskman.data.repo

import com.google.common.truth.Truth.assertThat
import io.github.bigsprut.taskman.data.remote.ApiFailure
import io.github.bigsprut.taskman.testutil.FakeStateStore
import io.github.bigsprut.taskman.testutil.FakeTaskmanApi
import io.github.bigsprut.taskman.testutil.errorResponseOf
import java.io.IOException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * CRUD сотрудников (ТЗ §5.3-Ф3, §6): создание, переименование, удаление.
 *
 * Сотрудники в задачах хранятся именем строкой, поэтому после каждой успешной мутации
 * состояние перечитывается (`/api/state`) — иначе переименование не было бы видно в списках.
 * Пустое имя на сервер не отправляется вообще: это ошибка ввода, а не отказ сети.
 */
class EmployeeRepositoryTest {

    private val api = FakeTaskmanApi()
    private val store = FakeStateStore()
    private val taskRepository = TaskRepository(api = api, stateStore = store, now = { NOW_MILLIS })
    private val repository = EmployeeRepository(api = api, tasks = taskRepository)

    @Test
    fun `create обрезает пробелы и перечитывает снапшот`() = runTest {
        val outcome = repository.create(" Иван ")

        assertThat(outcome).isEqualTo(MutationOutcome.Success)
        assertThat(api.createEmployeeCalls).isEqualTo(1)
        // Обрезка — обязанность клиента: « Иван » на сервере стал бы отдельным сотрудником.
        assertThat(api.lastCreatedEmployeeBody!!.name).isEqualTo("Иван")
        assertThat(api.lastEmployeeBody!!.name).isEqualTo("Иван")
        assertThat(api.calls).isEqualTo(1)
        assertThat(store.stored).isNotNull()
    }

    @Test
    fun `create не отправляет пустое или пробельное имя`() = runTest {
        val empty = repository.create("")
        val blank = repository.create("   ")

        assertThat(failureOf(empty)).isEqualTo(ApiFailure.InvalidInput)
        assertThat(failureOf(blank)).isEqualTo(ApiFailure.InvalidInput)
        assertThat(api.createEmployeeCalls).isEqualTo(0)
        // Состояние не перечитывается: запроса не было.
        assertThat(api.calls).isEqualTo(0)
    }

    @Test
    fun `rename отправляет id сотрудника и новое имя`() = runTest {
        val outcome = repository.rename(EMPLOYEE_ID, " Пётр ")

        assertThat(outcome).isEqualTo(MutationOutcome.Success)
        assertThat(api.updateEmployeeCalls).isEqualTo(1)
        assertThat(api.lastUpdatedEmployeeId).isEqualTo(EMPLOYEE_ID)
        assertThat(api.lastUpdatedEmployeeBody!!.name).isEqualTo("Пётр")
        assertThat(api.calls).isEqualTo(1)
        assertThat(store.stored).isNotNull()
    }

    @Test
    fun `rename не отправляет пустое имя`() = runTest {
        val outcome = repository.rename(EMPLOYEE_ID, "  ")

        assertThat(failureOf(outcome)).isEqualTo(ApiFailure.InvalidInput)
        assertThat(api.updateEmployeeCalls).isEqualTo(0)
        assertThat(api.calls).isEqualTo(0)
    }

    @Test
    fun `delete отправляет DELETE с id сотрудника и перечитывает снапшот`() = runTest {
        val outcome = repository.delete(EMPLOYEE_ID)

        assertThat(outcome).isEqualTo(MutationOutcome.Success)
        assertThat(api.deleteEmployeeCalls).isEqualTo(1)
        assertThat(api.lastDeletedEmployeeId).isEqualTo(EMPLOYEE_ID)
        assertThat(api.calls).isEqualTo(1)
        assertThat(store.stored).isNotNull()
    }

    @Test
    fun `401 даёт Unauthorized и событие в authFailures`() = runTest {
        api.createEmployeeOutcome = { errorResponseOf(401, """{"error":"unauthorized"}""") }
        // Коллектор встаёт до мутации: SharedFlow без replay не отдаёт событие задним числом.
        // UNDISPATCHED запускает корутину сразу до первого приостановления — подписка успевает
        // произойти синхронно.
        val event = async(start = CoroutineStart.UNDISPATCHED) {
            taskRepository.authFailures.first()
        }

        val outcome = repository.create("Иван")

        assertThat(failureOf(outcome)).isEqualTo(ApiFailure.Unauthorized)
        assertThat(event.await()).isEqualTo(ApiFailure.Unauthorized)
        // Отказ авторизации не повод перечитывать состояние — приложение уводит на вход.
        assertThat(api.calls).isEqualTo(0)
    }

    @Test
    fun `IOException даёт сетевую ошибку`() = runTest {
        val boom = IOException("сеть недоступна")
        api.deleteEmployeeOutcome = { throw boom }

        val outcome = repository.delete(EMPLOYEE_ID)

        val failure = failureOf(outcome)
        assertThat(failure).isInstanceOf(ApiFailure.Network::class.java)
        assertThat((failure as ApiFailure.Network).cause).isSameInstanceAs(boom)
        assertThat(api.calls).isEqualTo(0)
    }

    @Test
    fun `500 даёт Http с кодом и текстом ошибки`() = runTest {
        api.updateEmployeeOutcome = { errorResponseOf(500, """{"error":"boom"}""") }

        val failure = failureOf(repository.rename(EMPLOYEE_ID, "Пётр"))

        assertThat(failure).isInstanceOf(ApiFailure.Http::class.java)
        val http = failure as ApiFailure.Http
        assertThat(http.code).isEqualTo(500)
        assertThat(http.serverError).isEqualTo("boom")
        assertThat(api.calls).isEqualTo(0)
    }

    /** Достаёт причину отказа: у `MutationOutcome.Failed` это единственное полезное поле. */
    private fun failureOf(outcome: MutationOutcome): ApiFailure {
        assertThat(outcome).isInstanceOf(MutationOutcome.Failed::class.java)
        return (outcome as MutationOutcome.Failed).failure
    }

    private companion object {
        const val EMPLOYEE_ID = "emp-1"

        /** Фиксированное «сейчас», чтобы не зависеть от системных часов. */
        const val NOW_MILLIS = 1_700_000_000_000L
    }
}
