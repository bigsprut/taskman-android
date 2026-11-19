package io.github.bigsprut.taskman.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import coil3.compose.AsyncImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.bigsprut.taskman.R
import io.github.bigsprut.taskman.domain.Task
import io.github.bigsprut.taskman.domain.TaskDraft
import io.github.bigsprut.taskman.domain.TaskStatus
import io.github.bigsprut.taskman.ui.common.CategoryDot
import io.github.bigsprut.taskman.ui.common.labelRes
import io.github.bigsprut.taskman.ui.markdown.MarkdownText
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Лист-редактор задачи (Ф2, §5.3): все поля, markdown-превью, история статусов, подзадачи
 * и автосохранение при закрытии **любым** путём.
 *
 * Закрытие перехватывается: `onDismissRequest` (жест/внешний тап) и системный back ведут в
 * `viewModel::close`, а лист скрывается только после успешного сохранения (`isOpen == false`).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskEditorSheet(
    viewModel: TaskEditorViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    if (!state.isOpen) return
    val attachments by viewModel.attachments.collectAsStateWithLifecycle()

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val draft = state.draft
    // Выбор сотрудника — отдельный лист с поиском: справочник может быть большим (§5.3-Ф2).
    var showEmployeePicker by rememberSaveable { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = viewModel::close,
        sheetState = sheetState,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight(EDITOR_HEIGHT_FRACTION)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .imePadding(),
        ) {
            EditorHeader(
                isSaving = state.isSaving,
                isDirty = state.isDirty,
                onClose = viewModel::close,
            )

            if (state.isLoading || draft == null) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator()
                    Text(
                        text = stringResource(R.string.editor_loading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                return@Column
            }

            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    bottom = 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item("title") {
                    OutlinedTextField(
                        value = draft.title,
                        onValueChange = viewModel::setTitle,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(EditorTestTags.TITLE),
                        label = { Text(stringResource(R.string.editor_field_title)) },
                        singleLine = true,
                    )
                }

                item("content") {
                    ContentSection(
                        content = draft.content,
                        onContentChange = viewModel::setContent,
                    )
                }

                item("status") {
                    StatusSection(
                        draft = draft,
                        onStatusChange = viewModel::setStatus,
                        onStatusDateChange = viewModel::setStatusDate,
                    )
                }

                item("history") {
                    HistorySection(state.history)
                }

                item("category") {
                    SelectionSection(
                        title = stringResource(R.string.editor_field_category),
                        options = state.categories.map { it.id to it.title },
                        selectedIds = draft.categoryId?.let(::setOf) ?: emptySet(),
                        onToggle = { id ->
                            viewModel.setCategory(id)
                        },
                        leadingDotColor = { id ->
                            state.categories.firstOrNull { it.id == id }?.color
                        },
                    )
                }

                item("tags") {
                    SelectionSection(
                        title = stringResource(R.string.editor_field_tags),
                        options = state.tags.map { it.id to it.title },
                        selectedIds = draft.tagIds,
                        onToggle = viewModel::toggleTag,
                        leadingDotColor = { id ->
                            state.tags.firstOrNull { it.id == id }?.color
                        },
                    )
                }

                item("employee") {
                    EmployeePickerField(
                        title = stringResource(R.string.editor_field_employee),
                        selectedName = state.draft?.source,
                        onOpen = { showEmployeePicker = true },
                    )
                }

                item("created") {
                    CreatedDateSection(
                        createdAt = draft.createdAt,
                        onCreatedAtChange = viewModel::setCreatedAt,
                    )
                }

                item("attachments-header") {
                    HorizontalDivider()
                    Text(
                        text = stringResource(R.string.editor_attachments),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }

                if (attachments.isEmpty()) {
                    item("attachments-empty") {
                        Text(
                            text = stringResource(R.string.editor_attachment_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                items(attachments, key = { attachment -> "att-`${attachment.id}" }) { attachment ->
                    AttachmentRow(
                        attachment = attachment,
                        previewUrl = viewModel.attachmentUrl(attachment.id),
                        onOpen = { viewModel.downloadAttachment(attachment, AttachmentAction.OPEN) },
                        onShare = { viewModel.downloadAttachment(attachment, AttachmentAction.SHARE) },
                        onDelete = { viewModel.deleteAttachment(attachment.id) },
                    )
                }

                item("attachments-add") {
                    AttachmentsAddRow(onPick = viewModel::uploadAttachment)
                }

                item("subtasks-header") {
                    HorizontalDivider()
                    Text(
                        text = stringResource(R.string.editor_subtasks),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }

                items(state.children, key = { child -> "child-${child.id}" }) { child ->
                    SubtaskRow(
                        task = child,
                        onRename = viewModel::renameSubtask,
                        onToggle = viewModel::toggleSubtask,
                        onDelete = viewModel::deleteSubtask,
                    )
                }

                item("subtask-add") {
                    SubtaskAddRow(onAdd = viewModel::addSubtask)
                }
            }
        }
    }

    if (showEmployeePicker) {
        EmployeePickerSheet(
            employees = state.employees.map { it.name },
            selectedName = state.draft?.source,
            onSelect = { name ->
                viewModel.setEmployee(name)
                showEmployeePicker = false
            },
            onDismiss = { showEmployeePicker = false },
        )
    }
}

@Composable
private fun EditorHeader(
    isSaving: Boolean,
    isDirty: Boolean,
    onClose: () -> Unit,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.editor_title_edit),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            if (isSaving) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.editor_saving),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onClose, enabled = !isSaving) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.cd_editor_close),
                )
            }
        }
        if (isDirty && !isSaving) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ContentSection(content: String, onContentChange: (String) -> Unit) {
    var preview by rememberSaveable { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.editor_field_content),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            FilterChip(
                selected = !preview,
                onClick = { preview = false },
                label = { Text(stringResource(R.string.editor_content_edit)) },
            )
            Spacer(Modifier.width(8.dp))
            FilterChip(
                selected = preview,
                onClick = { preview = true },
                label = { Text(stringResource(R.string.editor_content_preview)) },
            )
        }

        if (preview) {
            // Никакого verticalScroll внутри LazyColumn: бесконечные ограничения по высоте
            // роняют измерение. Скроллит сам список редактора.
            Box(modifier = Modifier.fillMaxWidth()) {
                if (content.isBlank()) {
                    Text(
                        text = stringResource(R.string.editor_history_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    MarkdownText(markdown = content)
                }
            }
        } else {
            OutlinedTextField(
                value = content,
                onValueChange = onContentChange,
                modifier = Modifier.fillMaxWidth().height(160.dp),
                label = { Text(stringResource(R.string.editor_field_content)) },
                // Ввод — plain text (ТЗ §5.3-Ф2): markdown-разметка видна как есть.
                maxLines = 10,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatusSection(
    draft: TaskDraft,
    onStatusChange: (TaskStatus) -> Unit,
    onStatusDateChange: (Double?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.editor_field_status),
            style = MaterialTheme.typography.titleSmall,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TaskStatus.entries.forEach { status ->
                FilterChip(
                    selected = draft.status == status,
                    onClick = { onStatusChange(status) },
                    label = { Text(stringResource(status.labelRes())) },
                )
            }
        }
        DateRow(
            label = stringResource(R.string.editor_status_date),
            unixSeconds = draft.statusDateTs,
            onPick = onStatusDateChange,
        )
    }
}

@Composable
private fun HistorySection(history: List<io.github.bigsprut.taskman.domain.TaskHistoryEntry>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(R.string.editor_history),
            style = MaterialTheme.typography.titleSmall,
        )
        if (history.isEmpty()) {
            Text(
                text = stringResource(R.string.editor_history_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            history.forEach { entry ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(entry.status.labelRes()),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text = formatDate(entry.ts),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SelectionSection(
    title: String,
    options: List<Pair<String, String>>,
    selectedIds: Set<String>,
    onToggle: (String) -> Unit,
    leadingDotColor: (String) -> String?,
) {
    if (options.isEmpty()) return

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = title, style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (id, label) ->
                FilterChip(
                    selected = id in selectedIds,
                    onClick = { onToggle(id) },
                    label = { Text(label) },
                    leadingIcon = leadingDotColor(id)?.let { color ->
                        { CategoryDot(colorHex = color) }
                    },
                )
            }
        }
    }
}

@Composable
private fun CreatedDateSection(createdAt: Double?, onCreatedAtChange: (Double?) -> Unit) {
    DateRow(
        label = stringResource(R.string.editor_field_created),
        unixSeconds = createdAt,
        onPick = onCreatedAtChange,
    )
}

/** Строка с датой: показать, выбрать через `DatePickerDialog`, убрать. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateRow(
    label: String,
    unixSeconds: Double?,
    onPick: (Double?) -> Unit,
) {
    var dialogOpen by rememberSaveable { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(onClick = { dialogOpen = true }) {
            Icon(
                imageVector = Icons.Filled.DateRange,
                contentDescription = stringResource(R.string.editor_pick_date),
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = unixSeconds?.let(::formatDate)
                    ?: stringResource(R.string.editor_status_date_unset),
                style = MaterialTheme.typography.labelMedium,
            )
        }
        if (unixSeconds != null) {
            IconButton(onClick = { onPick(null) }) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.editor_clear_date),
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }

    if (dialogOpen) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = unixSeconds?.let { (it * MILLIS_PER_SECOND).toLong() },
        )
        DatePickerDialog(
            onDismissRequest = { dialogOpen = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        val selected = pickerState.selectedDateMillis
                        onPick(selected?.let { it / MILLIS_PER_SECOND.toDouble() })
                        dialogOpen = false
                    },
                ) {
                    Text(stringResource(R.string.editor_done))
                }
            },
            dismissButton = {
                TextButton(onClick = { dialogOpen = false }) {
                    Text(stringResource(R.string.editor_cancel))
                }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }
}

@Composable
private fun SubtaskRow(
    task: Task,
    onRename: (String, String) -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onDelete: (String) -> Unit,
) {
    var title by rememberSaveable(task.id) { mutableStateOf(task.title) }
    var edited by rememberSaveable(task.id) { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    // Значение с сервера подхватываем, только пока пользователь не начал править название.
    LaunchedEffect(task.title, edited) {
        if (!edited) title = task.title
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = task.status == TaskStatus.COMPLETED,
            onCheckedChange = { checked -> onToggle(task.id, checked) },
        )
        OutlinedTextField(
            value = title,
            onValueChange = {
                title = it
                edited = true
            },
            modifier = Modifier
                .weight(1f)
                .onFocusChanged { focusState ->
                    if (!focusState.isFocused && edited) {
                        edited = false
                        onRename(task.id, title)
                    }
                },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = {
                    edited = false
                    focusManager.clearFocus()
                    onRename(task.id, title)
                },
            ),
        )
        IconButton(onClick = { onDelete(task.id) }) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = stringResource(R.string.editor_subtask_delete),
            )
        }
    }
}

@Composable
private fun SubtaskAddRow(onAdd: (String) -> Unit) {
    var title by rememberSaveable { mutableStateOf("") }
    val focusManager = LocalFocusManager.current

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            modifier = Modifier
                .weight(1f)
                .testTag(EditorTestTags.NEW_SUBTASK),
            label = { Text(stringResource(R.string.editor_subtask_new)) },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = {
                    if (title.isNotBlank()) {
                        onAdd(title)
                        title = ""
                    }
                    focusManager.clearFocus()
                },
            ),
        )
        Button(
            onClick = {
                if (title.isNotBlank()) {
                    onAdd(title)
                    title = ""
                }
            },
            enabled = title.isNotBlank(),
        ) {
            Text(stringResource(R.string.editor_subtask_add))
        }
    }
}

/** Форматирование unix-секунд в локальную дату/время для UI. */
private fun formatDate(unixSeconds: Double): String = DateTimeFormatter
    .ofPattern("dd.MM.yyyy HH:mm", Locale.getDefault())
    .withZone(ZoneId.systemDefault())
    .format(Instant.ofEpochSecond(unixSeconds.toLong()))

private const val MILLIS_PER_SECOND = 1_000L
private const val EDITOR_HEIGHT_FRACTION = 0.92f
/** Строка вложения: превью картинки (Coil по ссылке с токеном), имя и действия. */
@Composable
private fun AttachmentRow(
    attachment: io.github.bigsprut.taskman.domain.Attachment,
    previewUrl: String?,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    val openLabel = stringResource(R.string.editor_attachment_open)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .semantics { contentDescription = openLabel }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (attachment.isImage && previewUrl != null) {
            AsyncImage(
                model = previewUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(48.dp)
                    .clip(MaterialTheme.shapes.small),
            )
        } else {
            Icon(
                painter = painterResource(R.drawable.ic_attachment),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = attachment.origName,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = formatFileSize(attachment.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        IconButton(onClick = onShare) {
            Icon(
                imageVector = Icons.Filled.Share,
                contentDescription = stringResource(R.string.editor_attachment_share),
                modifier = Modifier.size(20.dp),
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = stringResource(R.string.editor_attachment_delete),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** Кнопки добавления вложения: photo picker и выбор файла (§5.3-Ф3). */
@Composable
private fun AttachmentsAddRow(onPick: (String) -> Unit) {
    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) onPick(uri.toString())
    }
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) onPick(uri.toString())
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = {
                photoPicker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            },
        ) {
            Text(stringResource(R.string.editor_attachment_add_photo))
        }
        OutlinedButton(onClick = { filePicker.launch(arrayOf("*/*")) }) {
            Text(stringResource(R.string.editor_attachment_add_file))
        }
    }
}

/** Размер файла для строки вложения. */
internal fun formatFileSize(bytes: Long): String {
    if (bytes < KIB) return "$bytes Б"
    val kib = bytes / KIB.toDouble()
    if (kib < KIB) return String.format(Locale.getDefault(), "%.1f КБ", kib)
    val mib = kib / KIB
    return String.format(Locale.getDefault(), "%.1f МБ", mib)
}

private const val KIB = 1024L

/** Стабильные теги для UI-теста регресса (§9-Ф2). */
object EditorTestTags {
    const val TITLE = "editor-title"
    const val NEW_SUBTASK = "editor-new-subtask"
}
/** Поле «Сотрудник»: показывает выбор и открывает лист-список (не чипы, §5.3-Ф2). */
@Composable
private fun EmployeePickerField(
    title: String,
    selectedName: String?,
    onOpen: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
        )
        OutlinedCard(
            modifier = Modifier.fillMaxWidth(),
            onClick = onOpen,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = selectedName ?: stringResource(R.string.editor_employee_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (selectedName == null) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = stringResource(R.string.editor_employee_pick),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Лист выбора сотрудника с поиском (§5.3-Ф2, решение владельца 20.11).
 *
 * Справочник может быть большим, поэтому: поиск сверху, прокручиваемый список, отдельный
 * пункт «Без сотрудника» и явная отметка выбранного. Фильтр — [filterEmployees].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EmployeePickerSheet(
    employees: List<String>,
    selectedName: String?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = remember(employees, query) { filterEmployees(employees, query) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.editor_employee_pick),
                style = MaterialTheme.typography.titleMedium,
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.editor_employee_search)) },
                singleLine = true,
                leadingIcon = {
                    Icon(Icons.Filled.Search, contentDescription = null)
                },
            )
            LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                item("employee-none") {
                    EmployeeOptionRow(
                        name = stringResource(R.string.editor_employee_none),
                        selected = selectedName == null,
                        onClick = { onSelect(null) },
                    )
                }
                items(filtered, key = { name -> "employee-$name" }) { name ->
                    EmployeeOptionRow(
                        name = name,
                        selected = name == selectedName,
                        onClick = { onSelect(name) },
                    )
                }
                if (filtered.isEmpty()) {
                    item("employee-empty") {
                        Text(
                            text = stringResource(R.string.editor_employee_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmployeeOptionRow(
    name: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}