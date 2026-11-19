package io.github.bigsprut.taskman.domain

/**
 * Задача в терминах домена — то, что показывает UI.
 *
 * @property statusDateTs unix-секунды последней смены статуса (последняя запись истории статусов,
 *   иначе дата закрытия/обновления/создания). По этому полю сравниваются задачи внутри группы
 *   `STATUS_ORDER` (§5.3, Ф0: «внутри группы — свежесть даты статуса»).
 * @property categoryColor цвет категории в виде `#RRGGBB` (приходит с сервера как есть).
 * @property source сотрудник-источник задачи: сервер отдаёт имя строкой, а не id.
 */
data class Task(
    val id: String,
    val title: String,
    val status: TaskStatus,
    val statusDateTs: Double,
    val parentId: String?,
    val categoryTitle: String?,
    val categoryColor: String?,
    val source: String?,
) {
    /** Подзадача: сервер отдаёт её в общем списке с заполненным `parent_id`. */
    val isSubtask: Boolean get() = parentId != null
}
