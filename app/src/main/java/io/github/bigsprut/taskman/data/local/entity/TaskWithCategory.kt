package io.github.bigsprut.taskman.data.local.entity

/**
 * Строка списка задач в том виде, в каком её удобно рисовать: задача + название и цвет
 * её категории одним запросом (join). Сортировка по `STATUS_ORDER` выполняется в домене,
 * а не в SQL (см. [io.github.bigsprut.taskman.domain.StatusOrder]).
 */
data class TaskWithCategory(
    val id: String,
    val title: String,
    val status: String,
    val statusDateTs: Double,
    val parentId: String?,
    val source: String?,
    val categoryTitle: String?,
    val categoryColor: String?,
)
