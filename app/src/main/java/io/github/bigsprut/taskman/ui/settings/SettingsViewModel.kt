package io.github.bigsprut.taskman.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.bigsprut.taskman.data.export.ExportSnapshot
import io.github.bigsprut.taskman.data.remote.ApiFailure
import io.github.bigsprut.taskman.data.repo.CatalogRepository
import io.github.bigsprut.taskman.data.repo.EmployeeRepository
import io.github.bigsprut.taskman.data.repo.MutationOutcome
import io.github.bigsprut.taskman.data.repo.StateStore
import io.github.bigsprut.taskman.data.settings.SettingsStore
import io.github.bigsprut.taskman.domain.CatalogColor
import io.github.bigsprut.taskman.domain.Category
import io.github.bigsprut.taskman.domain.Employee
import io.github.bigsprut.taskman.domain.Tag
import io.github.bigsprut.taskman.domain.TaskSortOrder
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
    class Failed(val failure: ApiFailure) : SettingsMessage
}

/**
 * Экран настроек (Ф3–Ф5): справочник сотрудников, теги и категории с цветами, категория
 * по умолчанию, порядок сортировки и экспорт в markdown.
 *
 * Справочники живут в снапшоте (`StateStore`), их правки идут через [CatalogRepository]
 * и [EmployeeRepository] с рефетчем — сервер источник правды по id и цветам.
 * Порядок сортировки — локальная настройка устройства ([SettingsStore]).
 */
class SettingsViewModel(
    private val employees: EmployeeRepository,
    private val catalog: CatalogRepository,
    private val store: StateStore,
    private val settings: SettingsStore,
) : ViewModel() {

    val employeeList: StateFlow<List<Employee>> = store.observeEmployees()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

    val tagList: StateFlow<List<Tag>> = store.observeTags()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

    val categoryList: StateFlow<List<Category>> = store.observeCategories()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

    val defaultCategoryId: StateFlow<String?> = store.observeDefaultCategoryId()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), null)

    val sortOrder: StateFlow<TaskSortOrder> = settings.observeSortOrder()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), TaskSortOrder.STATUS)

    private val _messages = MutableSharedFlow<SettingsMessage>(
        extraBufferCapacity = MESSAGES_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val messages: SharedFlow<SettingsMessage> = _messages.asSharedFlow()

    // --- Сотрудники (Ф3) ---

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

    // --- Теги и категории (Ф5) ---

    fun addTag(title: String, color: String?) {
        if (title.isBlank()) return
        val normalized = CatalogColor.normalize(color) ?: CatalogColor.DEFAULT
        viewModelScope.launch { report(catalog.createTag(title, normalized)) }
    }

    fun updateTag(tagId: String, title: String, color: String?) {
        if (title.isBlank()) return
        val normalized = CatalogColor.normalize(color)
        viewModelScope.launch { report(catalog.updateTag(tagId, title, normalized)) }
    }

    fun deleteTag(tagId: String) {
        viewModelScope.launch { report(catalog.deleteTag(tagId)) }
    }

    fun addCategory(title: String, color: String?) {
        if (title.isBlank()) return
        val normalized = CatalogColor.normalize(color) ?: CatalogColor.DEFAULT
        viewModelScope.launch { report(catalog.createCategory(title, normalized)) }
    }

    fun updateCategory(categoryId: String, title: String, color: String?) {
        if (title.isBlank()) return
        val normalized = CatalogColor.normalize(color)
        viewModelScope.launch { report(catalog.updateCategory(categoryId, title, normalized)) }
    }

    fun deleteCategory(categoryId: String) {
        viewModelScope.launch { report(catalog.deleteCategory(categoryId)) }
    }

    /** Категория по умолчанию для новых задач (`PATCH /api/settings`). */
    fun setDefaultCategory(categoryId: String?) {
        viewModelScope.launch { report(catalog.setDefaultCategory(categoryId)) }
    }

    // --- Локальные настройки и экспорт (Ф5) ---

    fun setSortOrder(order: TaskSortOrder) {
        viewModelScope.launch { settings.saveSortOrder(order) }
    }

    /** Отдаёт снапшот в UI: markdown собирается там, где доступны строки статусов. */
    fun loadExportSnapshot(onReady: (ExportSnapshot) -> Unit) {
        viewModelScope.launch { onReady(store.loadExportSnapshot()) }
    }

    private suspend fun report(outcome: MutationOutcome) {
        when (outcome) {
            MutationOutcome.Success, MutationOutcome.Queued -> Unit
            is MutationOutcome.Conflict -> _messages.emit(
                SettingsMessage.Failed(ApiFailure.Conflict(outcome.actualUpdatedAt)),
            )

            is MutationOutcome.Failed -> _messages.emit(SettingsMessage.Failed(outcome.failure))
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
        const val MESSAGES_BUFFER = 8
    }
}
