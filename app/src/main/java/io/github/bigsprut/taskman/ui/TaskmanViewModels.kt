package io.github.bigsprut.taskman.ui

import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.bigsprut.taskman.di.AppContainer
import io.github.bigsprut.taskman.ui.editor.TaskEditorViewModel
import io.github.bigsprut.taskman.ui.list.TaskListViewModel
import io.github.bigsprut.taskman.ui.session.SessionViewModel
import io.github.bigsprut.taskman.ui.settings.SettingsViewModel

/**
 * Фабрики ViewModel для ручного DI.
 *
 * `ViewModelProvider.Factory` вместо Hilt: граф зависимостей маленький и явный, а
 * ViewModel-и получают только то, что им нужно. Списку и редактору нужен `SavedStateHandle` —
 * в нём переживают фильтр и черновик задачи.
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
                attachments = container.attachmentRepository,
                shareInbox = container.shareInbox,
                settings = container.settingsStore,
                store = container.catalogStore,
                savedState = createSavedStateHandle(),
            )
        }
    }

    fun taskEditor(container: AppContainer) = viewModelFactory {
        initializer {
            TaskEditorViewModel(
                tasks = container.taskRepository,
                mutations = container.taskMutationRepository,
                attachmentsRepo = container.attachmentRepository,
                store = container.catalogStore,
                savedState = createSavedStateHandle(),
            )
        }
    }

    fun settings(container: AppContainer) = viewModelFactory {
        initializer {
            SettingsViewModel(
                employees = container.employeeRepository,
                catalog = container.catalogRepository,
                store = container.catalogStore,
                settings = container.settingsStore,
            )
        }
    }
}
