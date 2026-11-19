package io.github.bigsprut.taskman.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.Modifier

import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.bigsprut.taskman.R
import io.github.bigsprut.taskman.ui.common.rememberFailureTexts
import io.github.bigsprut.taskman.ui.editor.AttachmentAction
import io.github.bigsprut.taskman.ui.editor.EditorMessage
import io.github.bigsprut.taskman.ui.editor.TaskEditorSheet
import io.github.bigsprut.taskman.ui.editor.TaskEditorViewModel
import io.github.bigsprut.taskman.ui.common.userMessage
import io.github.bigsprut.taskman.ui.list.ListMessage
import io.github.bigsprut.taskman.ui.list.TaskListContent
import io.github.bigsprut.taskman.ui.list.TaskListViewModel
import io.github.bigsprut.taskman.ui.session.SessionViewModel
import io.github.bigsprut.taskman.ui.settings.SettingsContent
import io.github.bigsprut.taskman.data.export.MarkdownExport
import io.github.bigsprut.taskman.domain.TaskStatus
import io.github.bigsprut.taskman.ui.common.ExportIntents
import io.github.bigsprut.taskman.ui.common.labelRes
import io.github.bigsprut.taskman.ui.settings.SettingsMessage
import io.github.bigsprut.taskman.ui.settings.SettingsViewModel
import kotlinx.coroutines.launch

private const val TAB_TASKS = 0
private const val TAB_SETTINGS = 1

/**
 * Основной каркас: `Scaffold` + `TopAppBar` + `NavigationBar` (Список / Настройки), §5.2.
 *
 * Один `Scaffold` на оба раздела — так insets считаются один раз (`safeDrawing`), и нет
 * двойных отступов от вложенных Scaffold'ов. Снекбары показывает этот же Scaffold:
 * и предупреждение о показе кэша, и сообщения о мутациях (409, отказы сервера).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    sessionViewModel: SessionViewModel,
    taskListViewModel: TaskListViewModel,
    editorViewModel: TaskEditorViewModel,
    settingsViewModel: SettingsViewModel,
    modifier: Modifier = Modifier,
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(TAB_TASKS) }
    val scope = rememberCoroutineScope()
    // Context нужен только для запуска внешних Intent (открытие/шаринг вложений).
    val context = LocalContext.current
    val failureTexts = rememberFailureTexts()

    val syncStatus by taskListViewModel.syncStatus.collectAsStateWithLifecycle()
    val connection by sessionViewModel.connection.collectAsStateWithLifecycle()
    val rows by taskListViewModel.rows.collectAsStateWithLifecycle()
    val counts by taskListViewModel.counts.collectAsStateWithLifecycle()
    val filter by taskListViewModel.filter.collectAsStateWithLifecycle()
    val tags by taskListViewModel.tags.collectAsStateWithLifecycle()
    val categories by taskListViewModel.categories.collectAsStateWithLifecycle()
    val expandedParents by taskListViewModel.expandedParents.collectAsStateWithLifecycle()
    val pendingTaskIds by taskListViewModel.pendingTaskIds.collectAsStateWithLifecycle()
    val pendingMutationCount by taskListViewModel.pendingMutationCount.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }

    val failure = syncStatus.lastFailure
    val failureText = failure?.userMessage()
    val cachedDataWarning = stringResource(R.string.list_cached_warning)
    val copiedMessage = stringResource(R.string.settings_copied)
    val employees by settingsViewModel.employeeList.collectAsStateWithLifecycle()
    val tagCatalog by settingsViewModel.tagList.collectAsStateWithLifecycle()
    val categoryCatalog by settingsViewModel.categoryList.collectAsStateWithLifecycle()
    val defaultCategoryId by settingsViewModel.defaultCategoryId.collectAsStateWithLifecycle()
    val sortOrder by settingsViewModel.sortOrder.collectAsStateWithLifecycle()
    val exportFailedText = stringResource(R.string.export_failed)
    val exportSubject = stringResource(R.string.export_subject)
    val dateFormatter = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }
    val statusLabels = TaskStatus.entries.associate { status ->
        status.wireValue to stringResource(status.labelRes())
    }
    val exportNowLabel = dateFormatter.format(Date())
    val onExport: () -> Unit = {
        settingsViewModel.loadExportSnapshot { snapshot ->
            val markdown = MarkdownExport.build(
                snapshot = snapshot,
                generatedAtLabel = exportNowLabel,
                statusLabel = { wire -> statusLabels[wire] ?: wire },
                formatDate = { seconds -> dateFormatter.format(Date((seconds * 1000).toLong())) },
            )
            val shared = ExportIntents.shareMarkdown(
                context = context,
                text = markdown,
                subject = exportSubject,
            )
            if (!shared) {
                scope.launch { snackbarHostState.showSnackbar(exportFailedText) }
            }
        }
    }
    val employeeFailedText = stringResource(R.string.settings_employee_failed)
    val sharedFiles by taskListViewModel.sharedFiles.collectAsStateWithLifecycle()
    val shareDialogText = stringResource(R.string.share_pick_task_text, sharedFiles.size)
    val conflictToggleText = stringResource(R.string.conflict_toggle)
    val conflictDeleteText = stringResource(R.string.conflict_delete)
    val toggleFailedText = stringResource(R.string.toggle_failed)
    val deleteFailedText = stringResource(R.string.delete_failed)
    val editorConflictText = stringResource(R.string.editor_save_conflict)
    val editorSaveFailedText = stringResource(R.string.editor_save_failed)
    val editorSubtaskFailedText = stringResource(R.string.editor_subtask_failed)
    val editorAttachmentFailedText = stringResource(R.string.editor_attachment_failed)
    val attachmentOpenFailedText = stringResource(R.string.attachment_open_failed)
    val shareFailedText = stringResource(R.string.share_attach_failed)
    val shareAttachedText = stringResource(R.string.share_attached)
    val savedLocallyText = stringResource(R.string.saved_locally)
    val syncedText = stringResource(R.string.sync_done)

    // Если данные есть (из Room), ошибку синхронизации показываем снекбаром и не стираем
    // список с экрана: «нет сети» не означает «нет данных».
    LaunchedEffect(failure) {
        if (failureText != null && rows.isNotEmpty()) {
            snackbarHostState.showSnackbar("$cachedDataWarning · $failureText")
        }
    }

    // Результаты мутаций: конфликт версий (409) и отказы сервера — точной причиной в снекбар.
    LaunchedEffect(taskListViewModel) {
        taskListViewModel.messages.collect { message ->
            val text = when (message) {
                ListMessage.ToggleConflict -> conflictToggleText
                ListMessage.DeleteConflict -> conflictDeleteText
                is ListMessage.ToggleFailed ->
                    "$toggleFailedText: ${failureTexts.describe(message.failure)}"
                is ListMessage.DeleteFailed ->
                    "$deleteFailedText: ${failureTexts.describe(message.failure)}"
                is ListMessage.ShareAttachFailed ->
                    "$shareFailedText: ${failureTexts.describe(message.failure)}"
                ListMessage.SharedFilesAttached -> shareAttachedText
                ListMessage.SavedLocally -> savedLocallyText
                ListMessage.Synced -> syncedText
            }
            snackbarHostState.showSnackbar(text)
        }
    }

    // Сообщения редактора: конфликт версий и отказы сервера — в тот же снекбар-хост.
    LaunchedEffect(editorViewModel) {
        editorViewModel.messages.collect { message ->
            val text = when (message) {
                EditorMessage.SaveConflict -> editorConflictText
                is EditorMessage.SaveFailed ->
                    "$editorSaveFailedText: ${failureTexts.describe(message.failure)}"
                is EditorMessage.SubtaskFailed ->
                    "$editorSubtaskFailedText: ${failureTexts.describe(message.failure)}"
                is EditorMessage.AttachmentFailed ->
                    "$editorAttachmentFailedText: ${failureTexts.describe(message.failure)}"
                EditorMessage.AttachmentOpenFailed -> attachmentOpenFailedText
                EditorMessage.SavedLocally -> savedLocallyText
                is EditorMessage.AttachmentReady -> {
                    // Открытие и шаринг требуют Context: сначала показать, потом (если не вышло)
                    // сообщить. Ошибка запуска UI показывает через reportAttachmentOpenFailed.
                    val launched = io.github.bigsprut.taskman.ui.common.AttachmentIntents.launch(
                        context = context,
                        file = message.file,
                        mimeType = message.attachment.mime,
                        action = if (message.action == AttachmentAction.OPEN) {
                            io.github.bigsprut.taskman.ui.common.AttachmentIntents.Action.OPEN
                        } else {
                            io.github.bigsprut.taskman.ui.common.AttachmentIntents.Action.SHARE
                        },
                    )
                    if (!launched) editorViewModel.reportAttachmentOpenFailed()
                    return@collect
                }
            }
            snackbarHostState.showSnackbar(text)
        }
    }

    // Сообщения справочника сотрудников (Ф3).
    LaunchedEffect(settingsViewModel) {
        settingsViewModel.messages.collect { message ->
            when (message) {
                is SettingsMessage.Failed -> snackbarHostState.showSnackbar(
                    "$employeeFailedText: ${failureTexts.describe(message.failure)}",
                )
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (selectedTab == TAB_TASKS) R.string.list_title else R.string.settings_title,
                        ),
                    )
                },
                actions = {
                    if (selectedTab == TAB_TASKS) {
                        IconButton(
                            onClick = taskListViewModel::refresh,
                            enabled = !syncStatus.isRefreshing,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = stringResource(R.string.action_refresh),
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == TAB_TASKS,
                    onClick = { selectedTab = TAB_TASKS },
                    icon = {
                        Icon(Icons.AutoMirrored.Filled.List, contentDescription = null)
                    },
                    label = { Text(stringResource(R.string.nav_tasks)) },
                )
                NavigationBarItem(
                    selected = selectedTab == TAB_SETTINGS,
                    onClick = { selectedTab = TAB_SETTINGS },
                    icon = {
                        Icon(Icons.Filled.Settings, contentDescription = null)
                    },
                    label = { Text(stringResource(R.string.nav_settings)) },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { contentPadding ->
        Box(Modifier.padding(contentPadding)) {
            when (selectedTab) {
                TAB_TASKS -> TaskListContent(
                    rows = rows,
                    counts = counts,
                    filter = filter,
                    tags = tags,
                    categories = categories,
                    syncStatus = syncStatus,
                    expandedParents = expandedParents,
                    pendingTaskIds = pendingTaskIds,
                    pendingMutationCount = pendingMutationCount,
                    onSyncNow = taskListViewModel::syncNow,
                    onStatusFilterChange = taskListViewModel::setStatusFilter,
                    onCategoryToggle = taskListViewModel::toggleCategory,
                    onTagToggle = taskListViewModel::toggleTag,
                    onResetFilter = taskListViewModel::resetFilter,
                    onToggleCompleted = taskListViewModel::setCompleted,
                    onToggleExpanded = taskListViewModel::toggleExpanded,
                    onDeleteConfirmed = taskListViewModel::delete,
                    onOpenTask = { task -> editorViewModel.open(task.id) },
                    onRefresh = taskListViewModel::refresh,
                )

                else -> SettingsContent(
                    employees = employees,
                    tags = tagCatalog,
                    categories = categoryCatalog,
                    defaultCategoryId = defaultCategoryId,
                    sortOrder = sortOrder,
                    onAddEmployee = settingsViewModel::addEmployee,
                    onRenameEmployee = settingsViewModel::renameEmployee,
                    onDeleteEmployee = settingsViewModel::deleteEmployee,
                    onAddTag = settingsViewModel::addTag,
                    onUpdateTag = settingsViewModel::updateTag,
                    onDeleteTag = settingsViewModel::deleteTag,
                    onAddCategory = settingsViewModel::addCategory,
                    onUpdateCategory = settingsViewModel::updateCategory,
                    onDeleteCategory = settingsViewModel::deleteCategory,
                    onSetDefaultCategory = settingsViewModel::setDefaultCategory,
                    onSetSortOrder = settingsViewModel::setSortOrder,
                    onExport = onExport,
                    connection = connection,
                    onCopied = {
                        scope.launch { snackbarHostState.showSnackbar(copiedMessage) }
                    },
                    onLogout = sessionViewModel::logout,
                )
            }
        }
    }

    TaskEditorSheet(viewModel = editorViewModel)

    if (sharedFiles.isNotEmpty()) {
        ShareTargetDialog(
            tasks = rows.map { it.task }.distinctBy { it.id },
            text = shareDialogText,
            onPick = taskListViewModel::attachSharedTo,
            onDismiss = taskListViewModel::dismissSharedFiles,
        )
    }
}

/**
 * Выбор задачи для файлов, пришедших из «Поделиться в Задачи» (§5.3-Ф3).
 * Список — то, что сейчас видно в фильтре; так проще найти нужную задачу.
 */
@Composable
private fun ShareTargetDialog(
    tasks: List<io.github.bigsprut.taskman.domain.Task>,
    text: String,
    onPick: (io.github.bigsprut.taskman.domain.Task) -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.share_pick_task_title)) },
        text = {
            Column {
                Text(text = text, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.padding(top = 8.dp))
                if (tasks.isEmpty()) {
                    Text(
                        text = stringResource(R.string.share_no_tasks),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    items(tasks, key = { it.id }) { task ->
                        Text(
                            text = task.title,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(task) }
                                .padding(vertical = 12.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}