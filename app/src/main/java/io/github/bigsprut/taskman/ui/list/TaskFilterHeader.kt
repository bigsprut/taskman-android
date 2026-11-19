package io.github.bigsprut.taskman.ui.list

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.bigsprut.taskman.R
import io.github.bigsprut.taskman.domain.Category
import io.github.bigsprut.taskman.domain.StatusFilter
import io.github.bigsprut.taskman.domain.Tag
import io.github.bigsprut.taskman.domain.TaskCounts
import io.github.bigsprut.taskman.domain.TaskFilter
import io.github.bigsprut.taskman.ui.common.CategoryDot
import io.github.bigsprut.taskman.ui.common.labelRes

/**
 * Шапка фильтров (§5.3-Ф1): три группы чипов — статус, категории, теги.
 *
 * У каждой группы **читаемая подпись** (требование UI-политики продукта), у чипов —
 * счётчики. Фильтр не влияет на сортировку: порядок строк задаёт `STATUS_ORDER`.
 * Ряды чипов прокручиваются горизонтально, чтобы работать при font-scale 130%.
 */
@Composable
fun TaskFilterHeader(
    filter: TaskFilter,
    counts: TaskCounts,
    categories: List<Category>,
    tags: List<Tag>,
    onStatusChange: (StatusFilter) -> Unit,
    onCategoryToggle: (String) -> Unit,
    onTagToggle: (String) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
    ) {
        Column(Modifier.padding(vertical = 6.dp)) {
            FilterGroupLabel(stringResource(R.string.filter_status_label))
            ChipRow {
                StatusFilter.entries.forEach { status ->
                    FilterChip(
                        selected = filter.status == status,
                        onClick = { onStatusChange(status) },
                        label = {
                            Text(
                                stringResource(
                                    R.string.filter_chip_with_count,
                                    stringResource(status.labelRes()),
                                    counts.forStatus(status),
                                ),
                            )
                        },
                    )
                }
            }

            if (categories.isNotEmpty()) {
                FilterGroupLabel(stringResource(R.string.filter_categories_label))
                ChipRow {
                    categories.forEach { category ->
                        FilterChip(
                            selected = category.id in filter.categoryIds,
                            onClick = { onCategoryToggle(category.id) },
                            label = {
                                Text(
                                    stringResource(
                                        R.string.filter_chip_with_count,
                                        category.title,
                                        counts.byCategory[category.id] ?: 0,
                                    ),
                                )
                            },
                            leadingIcon = {
                                CategoryDot(
                                    colorHex = category.color,
                                    modifier = Modifier.padding(end = 0.dp),
                                )
                            },
                        )
                    }
                }
            }

            if (tags.isNotEmpty()) {
                FilterGroupLabel(stringResource(R.string.filter_tags_label))
                ChipRow {
                    tags.forEach { tag ->
                        FilterChip(
                            selected = tag.id in filter.tagIds,
                            onClick = { onTagToggle(tag.id) },
                            label = {
                                Text(
                                    stringResource(
                                        R.string.filter_chip_with_count,
                                        tag.title,
                                        counts.byTag[tag.id] ?: 0,
                                    ),
                                )
                            },
                            leadingIcon = { CategoryDot(colorHex = tag.color) },
                        )
                    }
                }
            }

            if (!filter.isDefault) {
                TextButton(onClick = onReset, modifier = Modifier.padding(start = 8.dp)) {
                    Text(stringResource(R.string.filter_reset))
                }
            }
        }
    }
}

@Composable
private fun FilterGroupLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 2.dp),
    )
}

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        content()
    }
}
