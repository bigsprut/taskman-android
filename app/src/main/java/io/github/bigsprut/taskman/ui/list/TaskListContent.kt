package io.github.bigsprut.taskman.ui.list

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Surface
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.bigsprut.taskman.R
import io.github.bigsprut.taskman.data.repo.SyncStatus
import io.github.bigsprut.taskman.domain.Category
import io.github.bigsprut.taskman.domain.StatusFilter
import io.github.bigsprut.taskman.domain.Tag
import io.github.bigsprut.taskman.domain.Task
import io.github.bigsprut.taskman.domain.TaskCounts
import io.github.bigsprut.taskman.domain.TaskFilter
import io.github.bigsprut.taskman.domain.TaskRowItem
import io.github.bigsprut.taskman.ui.common.EmptyState
import io.github.bigsprut.taskman.ui.common.ErrorState
import io.github.bigsprut.taskman.ui.common.LoadingState
import io.github.bigsprut.taskman.ui.common.userMessage

private const val PARENT_ITEM_TYPE = "parent"
private const val CHILD_ITEM_TYPE = "child"

/**
 * Список задач Ф1: шапка фильтров + прокручиваемый список групп.
 *
 * Состояния раздельные (§5.2): загрузка, «задач нет», «под фильтр ничего не подошло»,
 * ошибка, содержимое. Жест pull-to-refresh работает и на пустых экранах — состояния
 * скроллируемые.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskListContent(
    rows: List<TaskRowItem>,
    counts: TaskCounts,
    filter: TaskFilter,
    tags: List<Tag>,
    categories: List<Category>,
    syncStatus: SyncStatus,
    expandedParents: Set<String>,
    pendingTaskIds: Set<String>,
    pendingMutationCount: Int,
    filtersHidden: Boolean,
    onToggleFilters: () -> Unit,
    onSyncNow: () -> Unit,
    onStatusFilterChange: (StatusFilter) -> Unit,
    onCategoryToggle: (String) -> Unit,
    onTagToggle: (String) -> Unit,
    onResetFilter: () -> Unit,
    onToggleCompleted: (Task, Boolean) -> Unit,
    onToggleExpanded: (Task) -> Unit,
    onDeleteConfirmed: (Task) -> Unit,
    onOpenTask: (Task) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var taskPendingDeletion by remember { mutableStateOf<Task?>(null) }

    val failure = syncStatus.lastFailure
    val isInitialLoading = rows.isEmpty() &&
        failure == null &&
        (!syncStatus.hasAttempted || syncStatus.isRefreshing)
    val hasAnyTasks = counts.all > 0
    val tagsById = remember(tags) { tags.associateBy(Tag::id) }

    Column(modifier = modifier.fillMaxSize()) {
        if (pendingMutationCount > 0) {
            // Ф4: видно, что изменения сохранены локально и ждут сети.
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.list_pending_banner, pendingMutationCount),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onSyncNow) {
                        Text(
                            text = stringResource(R.string.list_pending_sync_now),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
        }

        FilterToggleRow(
            expanded = !filtersHidden,
            activeCount = filter.activeCount(),
            onToggle = onToggleFilters,
        )

        if (!filtersHidden) {
            TaskFilterHeader(
                filter = filter,
                counts = counts,
                categories = categories,
                tags = tags,
                onStatusChange = onStatusFilterChange,
                onCategoryToggle = onCategoryToggle,
                onTagToggle = onTagToggle,
                onReset = onResetFilter,
            )
        }

        PullToRefreshBox(
            isRefreshing = syncStatus.isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            when {
                isInitialLoading -> LoadingState(Modifier.fillMaxSize())

                rows.isEmpty() && failure != null -> ErrorState(
                    title = stringResource(R.string.list_error_title),
                    message = failure.userMessage(),
                    onRetry = onRefresh,
                    modifier = Modifier.fillMaxSize(),
                )

                rows.isEmpty() && hasAnyTasks -> EmptyState(
                    title = stringResource(R.string.list_filtered_empty_title),
                    message = stringResource(R.string.list_filtered_empty_body),
                    modifier = Modifier.fillMaxSize(),
                    onRetry = onResetFilter,
                    retryLabel = stringResource(R.string.filter_reset),
                )

                rows.isEmpty() -> EmptyState(
                    title = stringResource(R.string.list_empty_title),
                    message = stringResource(R.string.list_empty_body),
                    modifier = Modifier.fillMaxSize(),
                    onRetry = onRefresh,
                )

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    items(
                        items = rows,
                        key = { row -> row.task.id },
                        contentType = { row ->
                            if (row.isChild) CHILD_ITEM_TYPE else PARENT_ITEM_TYPE
                        },
                    ) { row ->
                        TaskRow(
                            item = row,
                            tagsById = tagsById,
                            isExpanded = row.task.id in expandedParents,
                            isPending = row.task.id in pendingTaskIds,
                            onToggleCompleted = onToggleCompleted,
                            onToggleExpanded = onToggleExpanded,
                            onDeleteRequest = { task -> taskPendingDeletion = task },
                            onOpen = onOpenTask,
                        )
                    }
                }
            }
        }
    }

    taskPendingDeletion?.let { task ->
        AlertDialog(
            onDismissRequest = { taskPendingDeletion = null },
            title = { Text(stringResource(R.string.delete_dialog_title)) },
            text = { Text(stringResource(R.string.delete_dialog_text, task.title)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        taskPendingDeletion = null
                        onDeleteConfirmed(task)
                    },
                ) {
                    Text(stringResource(R.string.delete_dialog_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { taskPendingDeletion = null }) {
                    Text(stringResource(R.string.delete_dialog_cancel))
                }
            },
        )
    }
}
/**
 * Компактная строка-переключатель блока фильтров (§5.3-Ф1, решение владельца 20.11).
 *
 * Развёрнутые чипы занимают экран, поэтому строка остаётся всегда, а шапка фильтров
 * показывается только в развёрнутом состоянии. Счётчик активных отборов подсказывает,
 * что фильтры не пустые, когда чипы скрыты.
 */
@Composable
private fun FilterToggleRow(
    expanded: Boolean,
    activeCount: Int,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (activeCount > 0) {
                stringResource(R.string.filters_toggle_counted, activeCount)
            } else {
                stringResource(R.string.filters_toggle)
            },
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
            contentDescription = stringResource(
                if (expanded) R.string.filters_toggle_hide else R.string.filters_toggle_show,
            ),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}