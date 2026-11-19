package io.github.bigsprut.taskman.domain

/**
 * Порядок задач в списке (ТЗ §5.3, Ф0; критерий приёмки §9).
 *
 * 1. `STATUS_ORDER`: в работе → созданные → выполненные → отменённые;
 * 2. внутри группы — свежесть даты статуса, сначала свежие;
 * 3. при полном равенстве — по id, чтобы порядок был детерминированным и `LazyColumn`
 *    не переставлял строки между обновлениями.
 *
 * Сортировка живёт в одном месте (Kotlin-компаратор), а не в SQL: так у неё ровно одна
 * реализация, которую можно покрыть обычным JVM-тестом.
 */
object StatusOrder {

    val comparator: Comparator<Task> = compareBy<Task> { it.status.sortIndex }
        .thenByDescending(Task::statusDateTs)
        .thenBy(Task::id)

    fun sort(tasks: List<Task>): List<Task> = tasks.sortedWith(comparator)
}
