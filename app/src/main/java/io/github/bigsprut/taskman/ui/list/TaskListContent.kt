package io.github.bigsprut.taskman.ui.list

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.bigsprut.taskman.R
import io.github.bigsprut.taskman.data.repo.SyncStatus
import io.github.bigsprut.taskman.domain.Task
import io.github.bigsprut.taskman.ui.common.EmptyState
import io.github.bigsprut.taskman.ui.common.ErrorState
import io.github.bigsprut.taskman.ui.common.LoadingState
import io.github.bigsprut.taskman.ui.common.labelColor
import io.github.bigsprut.taskman.ui.common.labelRes
import io.github.bigsprut.taskman.ui.common.parseTaskmanColor
import io.github.bigsprut.taskman.ui.common.userMessage

private const val TASK_ITEM_TYPE = "task"

/**
 * Список задач Ф0 (только чтение).
 *
 * `LazyColumn` с ключом по id задачи (§5.2: стабильные ключи, не индексы) — иначе при
 * обновлении снапшота строки пересоздавались бы и список «прыгал».
 *
 * Состояния раздельные (§5.2, §5.3): загрузка, пусто, ошибка, содержимое. Жест
 * pull-to-refresh работает и на пустом экране — состояния скроллируемые.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskListContent(
    tasks: List<Task>,
    syncStatus: SyncStatus,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val failure = syncStatus.lastFailure
    val isInitialLoading = tasks.isEmpty() &&
        failure == null &&
        (!syncStatus.hasAttempted || syncStatus.isRefreshing)

    PullToRefreshBox(
        isRefreshing = syncStatus.isRefreshing,
        onRefresh = onRefresh,
        modifier = modifier.fillMaxSize(),
    ) {
        when {
            isInitialLoading -> LoadingState(Modifier.fillMaxSize())

            tasks.isEmpty() && failure != null -> ErrorState(
                title = stringResource(R.string.list_error_title),
                message = failure.userMessage(),
                onRetry = onRefresh,
                modifier = Modifier.fillMaxSize(),
            )

            tasks.isEmpty() -> EmptyState(
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
                    items = tasks,
                    key = { task -> task.id },
                    contentType = { TASK_ITEM_TYPE },
                ) { task ->
                    TaskRow(task)
                }
            }
        }
    }
}

@Composable
private fun TaskRow(task: Task) {
    val categoryColor = parseTaskmanColor(
        raw = task.categoryColor,
        fallback = MaterialTheme.colorScheme.outline,
    )

    ListItem(
        modifier = Modifier.fillMaxWidth(),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = {
            // Точка категории декоративна: название категории продублировано текстом ниже,
            // поэтому contentDescription не нужен (§5.2, доступность).
            Box(
                Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(categoryColor),
            )
        },
        headlineContent = {
            Text(
                text = if (task.isSubtask) {
                    stringResource(R.string.list_subtask_prefix) + task.title
                } else {
                    task.title
                },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(task.status.labelRes()),
                    style = MaterialTheme.typography.labelMedium,
                    color = task.status.labelColor(),
                )
                task.categoryTitle?.let { category ->
                    Text(
                        text = category,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                task.source?.let { source ->
                    Text(
                        text = source,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
    )
}
