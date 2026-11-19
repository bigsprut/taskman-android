package io.github.bigsprut.taskman.ui.common

import androidx.annotation.StringRes
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import io.github.bigsprut.taskman.R
import io.github.bigsprut.taskman.domain.StatusFilter
import io.github.bigsprut.taskman.domain.TaskStatus

/** Подпись статуса в списке (все строки — русские, в ресурсах, §5.4). */
@StringRes
fun TaskStatus.labelRes(): Int = when (this) {
    TaskStatus.CREATED -> R.string.status_created
    TaskStatus.IN_PROGRESS -> R.string.status_inprogress
    TaskStatus.COMPLETED -> R.string.status_completed
    TaskStatus.CANCELED -> R.string.status_canceled
}

/**
 * Цвет подписи статуса из темы: цветной текст на залитом фоне не используется,
 * поэтому контраст гарантирован цветами Material 3.
 */
@Composable
fun TaskStatus.labelColor(): Color = when (this) {
    TaskStatus.IN_PROGRESS -> MaterialTheme.colorScheme.primary
    TaskStatus.CREATED -> MaterialTheme.colorScheme.onSurfaceVariant
    TaskStatus.COMPLETED -> MaterialTheme.colorScheme.tertiary
    TaskStatus.CANCELED -> MaterialTheme.colorScheme.error
}

/** Подпись чипа фильтра по статусу (§5.3-Ф1). */
@StringRes
fun StatusFilter.labelRes(): Int = when (this) {
    StatusFilter.ACTIVE -> R.string.filter_active
    StatusFilter.COMPLETED -> R.string.filter_completed
    StatusFilter.CANCELED -> R.string.filter_canceled
    StatusFilter.ALL -> R.string.filter_all
}
