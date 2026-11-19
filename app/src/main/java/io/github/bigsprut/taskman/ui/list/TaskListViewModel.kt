package io.github.bigsprut.taskman.ui.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.bigsprut.taskman.data.repo.SyncStatus
import io.github.bigsprut.taskman.data.repo.TaskRepository
import io.github.bigsprut.taskman.domain.Task
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Список задач (Ф0, только чтение).
 *
 * Данные берутся из Room — источник правды для UI; сеть лишь обновляет кэш. Поэтому список
 * остаётся на экране при потере связи, а состояния «нет данных» и «нет сети» различимы
 * по [syncStatus] (§5.2).
 */
class TaskListViewModel(
    private val repository: TaskRepository,
) : ViewModel() {

    val tasks: StateFlow<List<Task>> = repository.observeTasks()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = emptyList(),
        )

    val syncStatus: StateFlow<SyncStatus> = repository.syncStatus

    fun refresh() {
        viewModelScope.launch { repository.refresh() }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
