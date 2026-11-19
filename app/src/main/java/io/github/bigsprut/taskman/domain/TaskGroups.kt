package io.github.bigsprut.taskman.domain

/**
 * Группа «родитель + его подзадачи» для списка (§5.3-Ф1: подзадачи сворачиваемые, `▸ N/M`).
 *
 * Фильтр статуса отбирает **родителей**; дети показываются всегда — это контекст родителя,
 * а `▸ N/M` означает его прогресс. Иначе при фильтре «Активные» раскрытие завершённой
 * группы вело бы в пустоту (у «▸ 3/3» все дети выполнены, и они бы исчезали).
 * Фильтр по категориям/тегам к детям тоже не применяется — по той же причине.
 *
 * @property children все подзадачи родителя, отсортированные `STATUS_ORDER`.
 */
data class TaskGroup(
    val parent: Task,
    val children: List<Task>,
    val totalChildren: Int,
    val completedChildren: Int,
) {
    val hasChildren: Boolean get() = totalChildren > 0
}

/**
 * Сборка групп из уже отсортированного списка задач.
 *
 * Родителем считается задача с `parent_id == null`; дети подшиваются по `parent_id` и получают
 * свой порядок `STATUS_ORDER`. Задачи, чей родитель не прошёл фильтр или вообще отсутствует в
 * снапшоте (сироты), в группы не попадают — иначе вложенность была бы неоткуда взять.
 */
object TaskGroups {

    fun build(sortedTasks: List<Task>, filter: TaskFilter): List<TaskGroup> {
        val childrenByParent: Map<String, List<Task>> = sortedTasks
            .asSequence()
            .filter { it.parentId != null }
            .groupBy { it.parentId.orEmpty() }
            .mapValues { (_, children) -> children.sortedWith(StatusOrder.comparator) }

        return sortedTasks
            .asSequence()
            .filter { it.parentId == null && filter.matches(it) }
            .map { parent ->
                val allChildren = childrenByParent[parent.id].orEmpty()
                TaskGroup(
                    parent = parent,
                    children = allChildren,
                    totalChildren = allChildren.size,
                    completedChildren = allChildren.count { it.status == TaskStatus.COMPLETED },
                )
            }
            .toList()
    }

    /**
     * Плоский список строк для `LazyColumn`: родитель и, если группа раскрыта, её дети.
     * Порядок и ключи стабильны — по id задачи.
     */
    fun toRows(groups: List<TaskGroup>, expandedParents: Set<String>): List<TaskRowItem> =
        buildList {
            groups.forEach { group ->
                add(TaskRowItem(group.parent, isChild = false, group = group))
                if (group.parent.id in expandedParents) {
                    group.children.forEach { child ->
                        add(TaskRowItem(child, isChild = true, group = null))
                    }
                }
            }
        }
}

/** Строка списка: задача + контекст (подзадача ли, есть ли у неё группа-родитель). */
data class TaskRowItem(
    val task: Task,
    val isChild: Boolean,
    val group: TaskGroup?,
)
