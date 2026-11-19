package io.github.bigsprut.taskman.data.repo

import io.github.bigsprut.taskman.data.remote.ApiFailure
import io.github.bigsprut.taskman.data.remote.TaskmanApi
import io.github.bigsprut.taskman.data.remote.dto.EmployeeInDto
import java.io.IOException
import kotlinx.coroutines.CancellationException
import retrofit2.Response

/**
 * Справочник сотрудников (Ф3, §5.3): CRUD на экране настроек.
 *
 * После каждой мутации состояние перечитывается (`/api/state`): сервер — источник правды,
 * а сотрудники в задачах хранятся именем строкой, поэтому переименование видно только после
 * свежего снапшота.
 */
class EmployeeRepository(
    private val api: TaskmanApi,
    private val tasks: TaskRepository,
) {

    suspend fun create(name: String): MutationOutcome {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return MutationOutcome.Failed(ApiFailure.InvalidInput)
        return execute { api.createEmployee(EmployeeInDto(name = trimmed)) }
    }

    suspend fun rename(employeeId: String, name: String): MutationOutcome {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return MutationOutcome.Failed(ApiFailure.InvalidInput)
        return execute { api.updateEmployee(employeeId, EmployeeInDto(name = trimmed)) }
    }

    suspend fun delete(employeeId: String): MutationOutcome = execute { api.deleteEmployee(employeeId) }

    private suspend fun execute(call: suspend () -> Response<*>): MutationOutcome = try {
        val response = call()
        if (response.isSuccessful) {
            tasks.refresh()
            MutationOutcome.Success
        } else {
            when (val failure = ApiFailure.fromHttpResponse(response.code(), response.errorBody())) {
                ApiFailure.Unauthorized, ApiFailure.DeviceNotApproved -> {
                    tasks.reportAuthFailure(failure)
                    MutationOutcome.Failed(failure)
                }

                else -> MutationOutcome.Failed(failure)
            }
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (io: IOException) {
        MutationOutcome.Failed(ApiFailure.Network(io))
    } catch (error: Exception) {
        MutationOutcome.Failed(ApiFailure.Internal(error))
    }
}
