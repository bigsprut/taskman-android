package io.github.bigsprut.taskman.domain

/**
 * Сотрудник из справочника — поле «сотрудник» в редакторе задачи (§5.3-Ф2).
 * В контракте `task.source` — имя строкой, поэтому выбор сотрудника пишет его имя.
 */
data class Employee(
    val id: String,
    val name: String,
)

/** Запись истории статусов задачи для блока «История» в редакторе (§5.3-Ф2). */
data class TaskHistoryEntry(
    val status: TaskStatus,
    /** unix-секунды. */
    val ts: Double,
)
