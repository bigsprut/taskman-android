package io.github.bigsprut.taskman.ui

import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.bigsprut.taskman.di.AppContainer
import io.github.bigsprut.taskman.ui.list.TaskListViewModel
import io.github.bigsprut.taskman.ui.session.SessionViewModel

/**
 * Фабрики ViewModel для ручного DI.
 *
 * `ViewModelProvider.Factory` вместо Hilt: граф зависимостей маленький и явный, а
 * ViewModel-и получают только то, что им нужно. Списку нужен `SavedStateHandle` —
 * в нём переживает фильтр.
 */
object TaskmanViewModels {

    fun session(container: AppContainer, allowCleartextBaseUrl: Boolean) = viewModelFactory {
        initializer {
            SessionViewModel(
                connections = container.connectionRepository,
                taskRepository = container.taskRepository,
                allowCleartextBaseUrl = allowCleartextBaseUrl,
            )
        }
    }

    fun taskList(container: AppContainer) = viewModelFactory {
        initializer {
            TaskListViewModel(
                repository = container.taskRepository,
                mutations = container.taskMutationRepository,
                store = container.catalogStore,
                savedState = createSavedStateHandle(),
            )
        }
    }
}
