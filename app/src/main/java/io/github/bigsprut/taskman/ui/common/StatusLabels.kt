package io.github.bigsprut.taskman.ui.common

import androidx.annotation.StringRes
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


/** Подпись чипа фильтра по статусу (§5.3-Ф1). */
@StringRes
fun StatusFilter.labelRes(): Int = when (this) {
    StatusFilter.ACTIVE -> R.string.filter_active
    StatusFilter.COMPLETED -> R.string.filter_completed
    StatusFilter.CANCELED -> R.string.filter_canceled
    StatusFilter.ALL -> R.string.filter_all
}
