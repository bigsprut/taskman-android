package io.github.bigsprut.taskman.ui.list

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.bigsprut.taskman.data.remote.ApiFailure
import io.github.bigsprut.taskman.data.attachments.AttachmentOutcome
import io.github.bigsprut.taskman.data.attachments.AttachmentRepository
import io.github.bigsprut.taskman.data.attachments.ShareInbox
import io.github.bigsprut.taskman.data.repo.MutationOutcome
import io.github.bigsprut.taskman.data.repo.StateStore
import io.github.bigsprut.taskman.data.settings.SettingsStore
import io.github.bigsprut.taskman.data.repo.SyncStatus
import io.github.bigsprut.taskman.data.repo.TaskMutationRepository
import io.github.bigsprut.taskman.data.repo.TaskRepository
import io.github.bigsprut.taskman.domain.Category
import io.github.bigsprut.taskman.domain.StatusFilter
import io.github.bigsprut.taskman.domain.Tag
import io.github.bigsprut.taskman.domain.Task
import io.github.bigsprut.taskman.domain.TaskCounts
import io.github.bigsprut.taskman.domain.TaskFilter
import io.github.bigsprut.taskman.domain.TaskGroups
import io.github.bigsprut.taskman.domain.TaskRowItem
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Сообщения о мутациях — текст подбирает UI (строки в ресурсах). */
sealed interface ListMessage {
    data object ToggleConflict : ListMessage
    data object DeleteConflict : ListMessage
    class ToggleFailed(val failure: ApiFailure) : ListMessage
    class DeleteFailed(val failure: ApiFailure) : ListMessage

    /** Не удалось приложить файлы, пришедшие через «Поделиться». */
    class ShareAttachFailed(val failure: ApiFailure) : ListMessage

    /** Файлы «Поделиться» успешно приложены к задаче (для снекбара). */
    data object SharedFilesAttached : ListMessage

    /** Сети нет: изменение сохранено локально и уйдёт из очереди (Ф4). */
    data object SavedLocally : ListMessage

    /** Очередь отправлена вручную кнопкой «Отправить сейчас» (Q18). */
    data object Synced : ListMessage
}

/**
 * Список задач Ф1: фильтры, счётчики, группы подзадач, чекбокс и смахивание-удаление.
 *
 * Данные берутся из Room (источник правды для UI), мутации идут на сервер, после чего
 * состояние перечитывается ([TaskMutationRepository]). Фильтр и раскрытые группы живут
 * в состоянии ViewModel, поэтому переживают поворот; фильтр дополнительно сохраняется
 * в `SavedStateHandle`.
 */
class TaskListViewModel(
    private val repository: TaskRepository,
    private val mutations: TaskMutationRepository,
    private val attachments: AttachmentRepository,
    private val shareInbox: ShareInbox,
    settings: SettingsStore,
    store: StateStore,
    private val savedState: SavedStateHandle,
) : ViewModel() {

    val tasks: StateFlow<List<Task>> = repository.observeTasks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

    val syncStatus: StateFlow<SyncStatus> = repository.syncStatus

    /** Сколько мутаций ждёт отправки — баннер «есть неотправленные изменения» (Ф4). */
    val pendingMutationCount: StateFlow<Int> = mutations.observePendingCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), 0)

    val tags: StateFlow<List<Tag>> = store.observeTags()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

    val categories: StateFlow<List<Category>> = store.observeCategories()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

    private val _filter = MutableStateFlow(savedState.readFilter())
    val filter: StateFlow<TaskFilter> = _filter.asStateFlow()

    private val _expandedParents = MutableStateFlow<Set<String>>(emptySet())
    val expandedParents: StateFlow<Set<String>> = _expandedParents.asStateFlow()

    /** Задачи, по которым идёт запрос: чекбокс временно недоступен, чтобы не долбить сервер. */
    private val _pendingTaskIds = MutableStateFlow<Set<String>>(emptySet())
    val pendingTaskIds: StateFlow<Set<String>> = _pendingTaskIds.asStateFlow()

    private val _messages = MutableSharedFlow<ListMessage>(
        extraBufferCapacity = MESSAGES_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val messages: SharedFlow<ListMessage> = _messages.asSharedFlow()

    val counts: StateFlow<TaskCounts> = tasks
        .map(TaskCounts::of)
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            TaskCounts(all = 0, active = 0, completed = 0, canceled = 0),
        )

    /** Плоские строки списка: родитель и (если раскрыт) его подзадачи. */
    /** Порядок списка — локальная настройка устройства (§5.3-Ф5). */
    private val sortOrder = settings.observeSortOrder()

    val rows: StateFlow<List<TaskRowItem>> = combine(
        combine(tasks, _filter, _expandedParents) { tasks, filter, expanded ->
            Triple(tasks, filter, expanded)
        },
        sortOrder,
    ) { (tasks, filter, expanded), order ->
        TaskGroups.toRows(
            TaskGroups.build(tasks.sortedWith(order.comparator()), filter),
            expanded,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

    fun setStatusFilter(status: StatusFilter) = updateFilter(filter.value.withStatus(status))

    fun toggleCategory(categoryId: String) = updateFilter(filter.value.toggledCategory(categoryId))

    fun toggleTag(tagId: String) = updateFilter(filter.value.toggledTag(tagId))

    fun resetFilter() = updateFilter(TaskFilter.DEFAULTS)

    /** Раскрыть/свернуть группу подзадач (§5.3-Ф1). */
    fun toggleExpanded(task: Task) {
        val taskId = task.id
        _expandedParents.update { expanded ->
            if (taskId in expanded) expanded - taskId else expanded + taskId
        }
    }

    fun refresh() {
        viewModelScope.launch { repository.refresh() }
    }

    /**
     * Чекбокс «Выполнена ↔ Создана». Строка блокируется до ответа сервера: подтверждением
     * служит перечитанное состояние, а не локальная догадка.
     */
    fun setCompleted(task: Task, completed: Boolean) {
        if (!_pendingTaskIds.compareAndAdd(task.id)) return
        viewModelScope.launch {
            try {
                when (val outcome = mutations.setCompleted(task.id, completed)) {
                    MutationOutcome.Success -> Unit
                    MutationOutcome.Queued -> _messages.emit(ListMessage.SavedLocally)
                    is MutationOutcome.Conflict -> _messages.emit(ListMessage.ToggleConflict)
                    is MutationOutcome.Failed -> _messages.emit(ListMessage.ToggleFailed(outcome.failure))
                }
            } finally {
                _pendingTaskIds.update { it - task.id }
            }
        }
    }

    /** Смахивание-удаление (диалог подтверждения показывает UI). */
    fun delete(task: Task) {
        if (!_pendingTaskIds.compareAndAdd(task.id)) return
        viewModelScope.launch {
            try {
                when (val outcome = mutations.delete(task.id)) {
                    MutationOutcome.Success -> Unit
                    MutationOutcome.Queued -> _messages.emit(ListMessage.SavedLocally)
                    is MutationOutcome.Conflict -> _messages.emit(ListMessage.DeleteConflict)
                    is MutationOutcome.Failed -> _messages.emit(ListMessage.DeleteFailed(outcome.failure))
                }
            } finally {
                _pendingTaskIds.update { it - task.id }
            }
        }
    }

    /** Файлы, пришедшие из «Поделиться в Задачи», ждут выбора задачи (§5.3-Ф3). */
    val sharedFiles: StateFlow<List<String>> = shareInbox.uris

    fun dismissSharedFiles() {
        shareInbox.clear()
    }

    /** «Отправить сейчас»: не ждём backoff воркера (Q18). */
    fun syncNow() {
        viewModelScope.launch {
            when (val outcome = mutations.syncNow()) {
                MutationOutcome.Success -> _messages.emit(ListMessage.Synced)
                MutationOutcome.Queued -> _messages.emit(ListMessage.SavedLocally)
                is MutationOutcome.Conflict -> _messages.emit(ListMessage.ToggleConflict)
                is MutationOutcome.Failed -> _messages.emit(ListMessage.ToggleFailed(outcome.failure))
            }
        }
    }

    /** Прикладывает все ожидающие файлы к выбранной задаче и очищает очередь. */
    fun attachSharedTo(task: Task) {
        val uris = shareInbox.uris.value
        if (uris.isEmpty()) return
        viewModelScope.launch {
            var failure: ApiFailure? = null
            for (uri in uris) {
                when (val outcome = attachments.upload(task.id, uri)) {
                    AttachmentOutcome.Success -> Unit
                    is AttachmentOutcome.Failed -> {
                        failure = outcome.failure
                        break
                    }
                }
            }
            val error = failure
            if (error == null) {
                shareInbox.clear()
                _messages.emit(ListMessage.SharedFilesAttached)
            } else {
                _messages.emit(ListMessage.ShareAttachFailed(error))
            }
        }
    }

    private fun updateFilter(newFilter: TaskFilter) {
        _filter.value = newFilter
        savedState[KEY_FILTER_STATUS] = newFilter.status.name
        savedState[KEY_FILTER_CATEGORIES] = ArrayList(newFilter.categoryIds)
        savedState[KEY_FILTER_TAGS] = ArrayList(newFilter.tagIds)
    }

    private fun MutableStateFlow<Set<String>>.compareAndAdd(taskId: String): Boolean {
        var added = false
        update { current ->
            if (taskId in current) {
                current
            } else {
                added = true
                current + taskId
            }
        }
        return added
    }

    private fun SavedStateHandle.readFilter(): TaskFilter {
        val status = get<String>(KEY_FILTER_STATUS)
            ?.let { raw -> StatusFilter.entries.firstOrNull { it.name == raw } }
            ?: StatusFilter.ACTIVE
        return TaskFilter(
            status = status,
            categoryIds = get<ArrayList<String>>(KEY_FILTER_CATEGORIES)?.toSet().orEmpty(),
            tagIds = get<ArrayList<String>>(KEY_FILTER_TAGS)?.toSet().orEmpty(),
        )
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
        const val MESSAGES_BUFFER = 8
        const val KEY_FILTER_STATUS = "filter_status"
        const val KEY_FILTER_CATEGORIES = "filter_categories"
        const val KEY_FILTER_TAGS = "filter_tags"
    }
}
