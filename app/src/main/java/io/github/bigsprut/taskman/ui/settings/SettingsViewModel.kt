package io.github.bigsprut.taskman.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.bigsprut.taskman.data.remote.ApiFailure
import io.github.bigsprut.taskman.data.repo.EmployeeRepository
import io.github.bigsprut.taskman.data.repo.MutationOutcome
import io.github.bigsprut.taskman.data.repo.StateStore
import io.github.bigsprut.taskman.domain.Employee
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Сообщения экрана настроек — текст подбирает UI. */
sealed interface SettingsMessage {
    class EmployeeFailed(val failure: ApiFailure) : SettingsMessage
}

/**
 * Справочник сотрудников (Ф3, §5.3): CRUD на экране настроек.
 *
 * Список берётся из локального снапшота (сервер — источник правды), а изменения идут
 * отдельными мутациями с последующим `refresh()`.
 */
class SettingsViewModel(
    private val employees: EmployeeRepository,
    store: StateStore,
) : ViewModel() {

    val employeeList: StateFlow<List<Employee>> = store.observeEmployees()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

    private val _messages = MutableSharedFlow<SettingsMessage>(
        extraBufferCapacity = MESSAGES_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val messages: SharedFlow<SettingsMessage> = _messages.asSharedFlow()

    fun addEmployee(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch { report(employees.create(name)) }
    }

    fun renameEmployee(employeeId: String, name: String) {
        if (name.isBlank()) return
        viewModelScope.launch { report(employees.rename(employeeId, name)) }
    }

    fun deleteEmployee(employeeId: String) {
        viewModelScope.launch { report(employees.delete(employeeId)) }
    }

    private suspend fun report(outcome: MutationOutcome) {
        when (outcome) {
            MutationOutcome.Success, MutationOutcome.Queued -> Unit
            is MutationOutcome.Conflict -> _messages.emit(
                SettingsMessage.EmployeeFailed(ApiFailure.Conflict(outcome.actualUpdatedAt)),
            )
            is MutationOutcome.Failed -> _messages.emit(SettingsMessage.EmployeeFailed(outcome.failure))
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
        const val MESSAGES_BUFFER = 8
    }
}
