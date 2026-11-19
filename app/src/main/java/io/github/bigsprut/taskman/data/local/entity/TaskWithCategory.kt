package io.github.bigsprut.taskman.data.local.entity

/**
 * Строка списка задач в том виде, в каком её удобно рисовать: задача + название и цвет
 * её категории и число неудалённых вложений — одним запросом (join + подзапрос).
 * Сортировка по `STATUS_ORDER` и группировка подзадач выполняются в домене
 * (см. [io.github.bigsprut.taskman.domain.StatusOrder], [io.github.bigsprut.taskman.domain.TaskGroups]).
 */
data class TaskWithCategory(
    val id: String,
    val title: String,
    val status: String,
    val statusDateTs: Double,
    val parentId: String?,
    val categoryId: String?,
    val source: String?,
    val categoryTitle: String?,
    val categoryColor: String?,
    val attachmentCount: Int,
)
