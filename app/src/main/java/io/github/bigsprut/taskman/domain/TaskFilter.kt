package io.github.bigsprut.taskman.domain

/** Быстрый фильтр по статусу — верхняя группа чипов (§5.3-Ф1). */
enum class StatusFilter {
    /** По умолчанию: «Активные» = created + inprogress (ТЗ §5.3-Ф1). */
    ACTIVE,
    COMPLETED,
    CANCELED,
    ALL,
}

/**
 * Состояние фильтров списка.
 *
 * Важно: фильтр **не меняет порядок** — сортировка остаётся `STATUS_ORDER` (§5.3-Ф1,
 * «сортировка ≠ фильтр»). Категории и теги фильтруют только родительские задачи: подзадача
 * живёт в контексте своего родителя, иначе группа `▸ N/M` теряла бы смысл.
 *
 * @property categoryIds пустое множество = без ограничения; несколько значений = ИЛИ.
 * @property tagIds пустое множество = без ограничения; несколько значений = ИЛИ.
 */
data class TaskFilter(
    val status: StatusFilter = StatusFilter.ACTIVE,
    val categoryIds: Set<String> = emptySet(),
    val tagIds: Set<String> = emptySet(),
) {

    val isDefault: Boolean get() = this == DEFAULTS

    /** Совпадение по статусу — применяется и к родителям, и к детям. */
    fun matchesStatus(task: Task): Boolean = when (status) {
        StatusFilter.ACTIVE -> task.status == TaskStatus.CREATED || task.status == TaskStatus.IN_PROGRESS
        StatusFilter.COMPLETED -> task.status == TaskStatus.COMPLETED
        StatusFilter.CANCELED -> task.status == TaskStatus.CANCELED
        StatusFilter.ALL -> true
    }

    /** Полное совпадение — для родительских строк (статус + категория + теги). */
    fun matches(task: Task): Boolean = matchesStatus(task) &&
        (categoryIds.isEmpty() || task.categoryId in categoryIds) &&
        (tagIds.isEmpty() || task.tagIds.any { it in tagIds })

    fun withStatus(newStatus: StatusFilter): TaskFilter = copy(status = newStatus)

    fun toggledCategory(id: String): TaskFilter = copy(
        categoryIds = if (id in categoryIds) categoryIds - id else categoryIds + id,
    )

    fun toggledTag(id: String): TaskFilter = copy(
        tagIds = if (id in tagIds) tagIds - id else tagIds + id,
    )

    fun cleared(): TaskFilter = DEFAULTS

    companion object {
        val DEFAULTS = TaskFilter()
    }
}

/**
 * Счётчики для чипов фильтров. Считаются по всему набору задач (не по отфильтрованному):
 * пользователь должен видеть, сколько задач в каждом срезе, прежде чем его выбрать.
 */
data class TaskCounts(
    val all: Int,
    val active: Int,
    val completed: Int,
    val canceled: Int,
    val byCategory: Map<String, Int> = emptyMap(),
    val byTag: Map<String, Int> = emptyMap(),
) {
    fun forStatus(filter: StatusFilter): Int = when (filter) {
        StatusFilter.ACTIVE -> active
        StatusFilter.COMPLETED -> completed
        StatusFilter.CANCELED -> canceled
        StatusFilter.ALL -> all
    }

    companion object {
        fun of(tasks: List<Task>): TaskCounts = TaskCounts(
            all = tasks.size,
            active = tasks.count {
                it.status == TaskStatus.CREATED || it.status == TaskStatus.IN_PROGRESS
            },
            completed = tasks.count { it.status == TaskStatus.COMPLETED },
            canceled = tasks.count { it.status == TaskStatus.CANCELED },
            byCategory = tasks
                .mapNotNull { it.categoryId }
                .groupingBy { it }
                .eachCount(),
            byTag = tasks
                .flatMap { it.tagIds }
                .groupingBy { it }
                .eachCount(),
        )
    }
}
