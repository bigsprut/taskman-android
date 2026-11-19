package io.github.bigsprut.taskman.ui.editor

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.bigsprut.taskman.data.attachments.AttachmentOutcome
import io.github.bigsprut.taskman.data.attachments.AttachmentRepository
import io.github.bigsprut.taskman.data.remote.ApiFailure
import io.github.bigsprut.taskman.data.remote.TaskmanJson
import io.github.bigsprut.taskman.data.repo.MutationOutcome
import io.github.bigsprut.taskman.data.repo.StateStore
import io.github.bigsprut.taskman.data.repo.TaskMutationRepository
import io.github.bigsprut.taskman.data.repo.TaskRepository
import io.github.bigsprut.taskman.domain.Attachment
import io.github.bigsprut.taskman.domain.Category
import io.github.bigsprut.taskman.domain.Employee
import io.github.bigsprut.taskman.domain.Tag
import io.github.bigsprut.taskman.domain.Task
import io.github.bigsprut.taskman.domain.TaskDraft
import io.github.bigsprut.taskman.domain.TaskHistoryEntry
import io.github.bigsprut.taskman.domain.TaskStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Сообщения редактора: текст подбирает UI. */
sealed interface EditorMessage {
    /** 409 при сохранении: задачу успели изменить другим клиентом. */
    data object SaveConflict : EditorMessage

    class SaveFailed(val failure: ApiFailure) : EditorMessage

    class SubtaskFailed(val failure: ApiFailure) : EditorMessage

    /** Не удалось приложить/удалить/скачать вложение (Ф3). */
    class AttachmentFailed(val failure: ApiFailure) : EditorMessage

    /** Вложение скачано, но открыть/поделиться им не удалось (нет приложения-просмотрщика). */
    data object AttachmentOpenFailed : EditorMessage

    /** Файл скачан — UI может отдать его внешнему приложению. */
    class AttachmentReady(val file: java.io.File, val attachment: Attachment, val action: AttachmentAction) : EditorMessage
}

/** Что сделать со скачанным вложением. */
enum class AttachmentAction { OPEN, SHARE }

/** Состояние листа-редактора. */
data class EditorUiState(
    val isOpen: Boolean = false,
    val taskId: String? = null,
    val draft: TaskDraft? = null,
    /** Есть ли расхождения с сохранённой версией — решает, сохранять ли при закрытии. */
    val isDirty: Boolean = false,
    val isSaving: Boolean = false,
    val isLoading: Boolean = false,
    val history: List<TaskHistoryEntry> = emptyList(),
    val children: List<Task> = emptyList(),
    val tags: List<Tag> = emptyList(),
    val categories: List<Category> = emptyList(),
    val employees: List<Employee> = emptyList(),
)

/**
 * Редактор задачи (Ф2, §5.3): черновик, история, подзадачи и автосохранение при закрытии.
 *
 * Ключевые решения:
 * - **черновик живёт отдельно от снапшота** и не перезаписывается фоновым `refresh()` —
 *   это и есть регресс из §9-Ф2 («несохранённые поля не теряются при действии с подзадачей»);
 * - **черновик сериализуется в `SavedStateHandle`** на каждое изменение, поэтому переживает
 *   поворот и убивание процесса; при восстановлении помечается как «есть несохранённое»;
 * - **действия с подзадачами идут на сервер сразу** и не трогают черновик родителя;
 * - закрытие любым путём (жест, кнопка, системный back) вызывает [close], который сохраняет
 *   черновик, если он изменился; при ошибке лист остаётся открытым с сообщением.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TaskEditorViewModel(
    private val tasks: TaskRepository,
    private val mutations: TaskMutationRepository,
    private val attachmentsRepo: AttachmentRepository,
    store: StateStore,
    private val savedState: SavedStateHandle,
) : ViewModel() {

    private val _state = MutableStateFlow(EditorUiState())
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<EditorMessage>(
        extraBufferCapacity = MESSAGES_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val messages: SharedFlow<EditorMessage> = _messages.asSharedFlow()

    /** Вложения текущей задачи (Ф3): список обновляется из Room после мутаций. */
    val attachments: StateFlow<List<Attachment>> = _state
        .map { it.taskId }
        .distinctUntilChanged()
        .flatMapLatest { taskId ->
            if (taskId == null) flowOf(emptyList()) else attachmentsRepo.observeAttachments(taskId)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

    /** Версия, с которой сравниваем черновик (для dirty-проверки). */
    private var originalDraft: TaskDraft? = null
    private var childrenJob: Job? = null
    private var historyJob: Job? = null

    init {
        viewModelScope.launch {
            store.observeTags().collect { tags -> _state.update { it.copy(tags = tags) } }
        }
        viewModelScope.launch {
            store.observeCategories().collect { categories ->
                _state.update { it.copy(categories = categories) }
            }
        }
        viewModelScope.launch {
            store.observeEmployees().collect { employees ->
                _state.update { it.copy(employees = employees) }
            }
        }
    }

    /** Открывает лист для задачи; повторный вызов для той же задачи игнорируется. */
    fun open(taskId: String) {
        if (_state.value.isOpen && _state.value.taskId == taskId) return
        _state.update {
            it.copy(
                isOpen = true,
                taskId = taskId,
                draft = null,
                isDirty = false,
                isSaving = false,
                isLoading = true,
                history = emptyList(),
                children = emptyList(),
            )
        }
        restoreOrLoad(taskId)
    }

    /**
     * Закрытие листа ЛЮБЫМ путём: если черновик изменён — сохраняем и только потом закрываем.
     * При ошибке лист остаётся открытым: несохранённые поля нельзя терять молча.
     */
    fun close() {
        val current = _state.value
        if (!current.isOpen || current.isSaving) return

        val draft = current.draft
        if (draft == null || !current.isDirty) {
            finishClose()
            return
        }

        _state.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            when (val outcome = mutations.saveDraft(draft)) {
                MutationOutcome.Success -> {
                    originalDraft = draft
                    clearPersistedDraft()
                    finishClose()
                }

                is MutationOutcome.Conflict -> {
                    _messages.emit(EditorMessage.SaveConflict)
                    _state.update { it.copy(isSaving = false) }
                }

                is MutationOutcome.Failed -> {
                    _messages.emit(EditorMessage.SaveFailed(outcome.failure))
                    _state.update { it.copy(isSaving = false) }
                }
            }
        }
    }

    fun setTitle(value: String) = updateDraft { it.copy(title = value) }

    fun setContent(value: String) = updateDraft { it.copy(content = value) }

    /**
     * Смена статуса. Выбранную пользователем дату статуса НЕ сбрасываем: сначала дата, потом
     * статус — обычный порядок «правки задним числом», и терять ввод молча нельзя.
     * Если дата не выбрана, поле остаётся `null`, и дату определит сервер.
     */
    fun setStatus(status: TaskStatus) = updateDraft { it.copy(status = status) }

    fun setStatusDate(unixSeconds: Double?) = updateDraft { it.copy(statusDateTs = unixSeconds) }

    fun setCategory(categoryId: String?) = updateDraft {
        it.copy(categoryId = if (it.categoryId == categoryId) null else categoryId)
    }

    fun toggleTag(tagId: String) = updateDraft { draft ->
        draft.copy(
            tagIds = if (tagId in draft.tagIds) draft.tagIds - tagId else draft.tagIds + tagId,
        )
    }

    /** Сотрудник в контракте — имя строкой (`task.source`), поэтому храним имя. */
    fun setEmployee(name: String?) = updateDraft {
        it.copy(source = if (it.source == name) null else name)
    }

    fun setCreatedAt(unixSeconds: Double?) = updateDraft { it.copy(createdAt = unixSeconds) }

    // --- Подзадачи: действия идут на сервер сразу и не трогают черновик родителя ---

    fun addSubtask(title: String) {
        val parentId = _state.value.taskId ?: return
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            reportSubtaskOutcome(mutations.addSubtask(parentId, trimmed))
        }
    }

    fun renameSubtask(taskId: String, title: String) {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            reportSubtaskOutcome(mutations.renameSubtask(taskId, trimmed))
        }
    }

    fun toggleSubtask(taskId: String, completed: Boolean) {
        viewModelScope.launch {
            reportSubtaskOutcome(mutations.setCompleted(taskId, completed))
        }
    }

    fun deleteSubtask(taskId: String) {
        viewModelScope.launch {
            reportSubtaskOutcome(mutations.delete(taskId))
        }
    }

    // --- Вложения (Ф3): аплоад, удаление, открытие/шаринг ---

    fun uploadAttachment(uri: String) {
        val taskId = _state.value.taskId ?: return
        viewModelScope.launch {
            when (val outcome = attachmentsRepo.upload(taskId, uri)) {
                AttachmentOutcome.Success -> Unit
                is AttachmentOutcome.Failed -> _messages.emit(EditorMessage.AttachmentFailed(outcome.failure))
            }
        }
    }

    fun deleteAttachment(attachmentId: String) {
        viewModelScope.launch {
            when (val outcome = attachmentsRepo.delete(attachmentId)) {
                AttachmentOutcome.Success -> Unit
                is AttachmentOutcome.Failed -> _messages.emit(EditorMessage.AttachmentFailed(outcome.failure))
            }
        }
    }

    /** URL превью с токеном в query (ТЗ §4) — для Coil. */
    fun attachmentUrl(attachmentId: String): String? = attachmentsRepo.url(attachmentId)

    /**
     * Скачивает вложение и отдаёт файл UI: открытие внешним просмотрщиком и «Поделиться»
     * требуют `FileProvider`-uri и `Context`, поэтому сам Intent запускает слой UI.
     */
    fun downloadAttachment(attachment: Attachment, action: AttachmentAction) {
        viewModelScope.launch {
            val file = attachmentsRepo.download(attachment)
            if (file == null) {
                _messages.emit(EditorMessage.AttachmentOpenFailed)
            } else {
                _messages.emit(EditorMessage.AttachmentReady(file, attachment, action))
            }
        }
    }

    /** Сообщить UI, что открыть файл не удалось (нет просмотрщика/отказ системы). */
    fun reportAttachmentOpenFailed() {
        _messages.tryEmit(EditorMessage.AttachmentOpenFailed)
    }

    private suspend fun reportSubtaskOutcome(outcome: MutationOutcome) {
        when (outcome) {
            MutationOutcome.Success -> Unit
            is MutationOutcome.Conflict -> _messages.emit(EditorMessage.SaveConflict)
            is MutationOutcome.Failed -> _messages.emit(EditorMessage.SubtaskFailed(outcome.failure))
        }
    }

    private fun restoreOrLoad(taskId: String) {
        val persisted = savedState.get<String>(draftKey)
        val persistedTaskId = savedState.get<String>(draftTaskIdKey)

        viewModelScope.launch {
            val fromDb = tasks.loadDraft(taskId)
            val restored = if (persisted != null && persistedTaskId == taskId) {
                runCatching { TaskmanJson.decodeFromString<TaskDraft>(persisted) }.getOrNull()
            } else {
                null
            }
            val current = restored ?: fromDb
            if (current == null) {
                _state.update { it.copy(isOpen = false, isLoading = false) }
                return@launch
            }

            originalDraft = fromDb
            _state.update {
                it.copy(
                    draft = current,
                    isLoading = false,
                    // Восстановленный после убийства процесса черновик требует сохранения.
                    isDirty = fromDb != null && current != fromDb,
                )
            }
            observeHistory(taskId)
            observeChildren(taskId)
        }
    }

    private fun observeHistory(taskId: String) {
        historyJob?.cancel()
        historyJob = viewModelScope.launch {
            tasks.observeTaskHistory(taskId).collect { history ->
                _state.update { it.copy(history = history) }
            }
        }
    }

    private fun observeChildren(taskId: String) {
        childrenJob?.cancel()
        childrenJob = viewModelScope.launch {
            tasks.observeTasks().collect { all ->
                _state.update { it.copy(children = all.filter { task -> task.parentId == taskId }) }
            }
        }
    }

    private fun updateDraft(transform: (TaskDraft) -> TaskDraft) {
        val state = _state.value
        val draft = state.draft ?: return
        val updated = transform(draft)
        val original = originalDraft
        _state.update {
            it.copy(
                draft = updated,
                isDirty = if (original != null) updated != original else true,
            )
        }
        persistDraft(updated)
    }

    /** Черновик переживает убивание процесса: JSON в SavedStateHandle на каждое изменение. */
    private fun persistDraft(draft: TaskDraft) {
        savedState[draftKey] = TaskmanJson.encodeToString(draft)
        savedState[draftTaskIdKey] = draft.id
    }

    private fun clearPersistedDraft() {
        savedState.remove<String>(draftKey)
        savedState.remove<String>(draftTaskIdKey)
    }

    private fun finishClose() {
        childrenJob?.cancel()
        historyJob?.cancel()
        originalDraft = null
        _state.update {
            EditorUiState(
                tags = it.tags,
                categories = it.categories,
                employees = it.employees,
            )
        }
    }

    private companion object {
        const val MESSAGES_BUFFER = 8
        const val STOP_TIMEOUT_MILLIS = 5_000L
        const val draftKey = "editor_draft_json"
        const val draftTaskIdKey = "editor_draft_task_id"
    }
}
