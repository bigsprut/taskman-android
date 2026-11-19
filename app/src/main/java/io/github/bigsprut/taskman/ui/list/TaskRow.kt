package io.github.bigsprut.taskman.ui.list

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.bigsprut.taskman.R
import io.github.bigsprut.taskman.domain.Tag
import io.github.bigsprut.taskman.domain.Task
import io.github.bigsprut.taskman.domain.TaskRowItem
import io.github.bigsprut.taskman.domain.TaskStatus
import io.github.bigsprut.taskman.ui.common.AttachmentBadge
import io.github.bigsprut.taskman.ui.common.CategoryDot
import io.github.bigsprut.taskman.ui.common.StatusBadge
import io.github.bigsprut.taskman.ui.common.TagChip
import kotlinx.coroutines.launch

/** Сколько чипов тегов показываем в строке, остальное — «+N»: строка не должна расползаться. */
private const val MAX_VISIBLE_TAGS = 2

/**
 * Строка списка (Ф1): чекбокс «Выполнена ↔ Создана», бейджи статуса/категории/тегов/
 * вложений/сотрудника, сворачивание подзадач и смахивание-удаление.
 *
 * Кнопки удаления нет намеренно (UI-политика продукта): удаление — только свайпом влево
 * с подтверждением. Свайп не удаляет сразу: `confirmValueChange` возвращает `false`,
 * сначала спрашиваем пользователя.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskRow(
    item: TaskRowItem,
    tagsById: Map<String, Tag>,
    isExpanded: Boolean,
    isPending: Boolean,
    onToggleCompleted: (Task, Boolean) -> Unit,
    onToggleExpanded: (Task) -> Unit,
    onDeleteRequest: (Task) -> Unit,
    modifier: Modifier = Modifier,
) {
    val task = item.task
    val scope = rememberCoroutineScope()
    val dismissState = rememberSwipeToDismissBoxState(
        positionalThreshold = { totalDistance -> totalDistance * SWIPE_THRESHOLD_FRACTION },
    )

    SwipeToDismissBox(
        state = dismissState,
        // Отступ подзадачи снаружи: иначе фон свайпа просвечивает в полосе отступа.
        modifier = modifier
            .fillMaxWidth()
            .padding(start = if (item.isChild) CHILD_INDENT else 0.dp),
        enableDismissFromStartToEnd = false,
        onDismiss = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                // Удаления без подтверждения нет: сначала диалог, строку возвращаем на место
                // (если пользователь откажется — она уже на месте).
                onDeleteRequest(task)
                scope.launch { dismissState.reset() }
            }
        },
        backgroundContent = { DeleteSwipeBackground() },
    ) {
        ListItem(
            modifier = Modifier.fillMaxWidth(),
            colors = ListItemDefaults.colors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
            leadingContent = {
                // Подпись для экранного диктора: что именно сделает чекбокс.
                val toggleDescription = stringResource(
                    if (task.status == TaskStatus.COMPLETED) {
                        R.string.cd_toggle_created
                    } else {
                        R.string.cd_toggle_completed
                    },
                )
                Checkbox(
                    checked = task.status == TaskStatus.COMPLETED,
                    onCheckedChange = { checked -> onToggleCompleted(task, checked) },
                    enabled = !isPending,
                    modifier = Modifier.semantics { contentDescription = toggleDescription },
                )
            },
            headlineContent = {
                Text(
                    text = task.title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            supportingContent = {
                SupportingBadges(task = task, tagsById = tagsById)
            },
            trailingContent = {
                GroupProgress(
                    item = item,
                    isExpanded = isExpanded,
                    onToggleExpanded = onToggleExpanded,
                )
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SupportingBadges(task: Task, tagsById: Map<String, Tag>) {
    val tags = task.tagIds.mapNotNull(tagsById::get)

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        StatusBadge(status = task.status)

        task.categoryTitle?.let { categoryTitle ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CategoryDot(colorHex = task.categoryColor)
                Text(
                    text = categoryTitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = CATEGORY_MAX_WIDTH),
                )
            }
        }

        tags.take(MAX_VISIBLE_TAGS).forEach { tag -> TagChip(tag = tag) }
        if (tags.size > MAX_VISIBLE_TAGS) {
            Text(
                text = stringResource(R.string.badge_more_tags, tags.size - MAX_VISIBLE_TAGS),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (task.attachmentCount > 0) {
            AttachmentBadge(count = task.attachmentCount)
        }

        task.source?.let { source ->
            Text(
                text = source,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = SOURCE_MAX_WIDTH),
            )
        }
    }
}

@Composable
private fun GroupProgress(
    item: TaskRowItem,
    isExpanded: Boolean,
    onToggleExpanded: (Task) -> Unit,
) {
    val group = item.group ?: return
    if (!group.hasChildren) return

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(
                R.string.group_progress,
                group.completedChildren,
                group.totalChildren,
            ),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        IconButton(onClick = { onToggleExpanded(item.task) }) {
            Icon(
                imageVector = if (isExpanded) {
                    Icons.Filled.KeyboardArrowDown
                } else {
                    Icons.AutoMirrored.Filled.KeyboardArrowRight
                },
                contentDescription = stringResource(
                    if (isExpanded) R.string.cd_collapse_group else R.string.cd_expand_group,
                ),
            )
        }
    }
}

/** Фон под смахиваемой строкой: подсказка, что жест означает удаление. */
@Composable
private fun DeleteSwipeBackground() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Icon(
            imageVector = Icons.Filled.Delete,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

private const val SWIPE_THRESHOLD_FRACTION = 0.35f
private val CHILD_INDENT = 24.dp
private val CATEGORY_MAX_WIDTH = 120.dp
private val SOURCE_MAX_WIDTH = 110.dp
