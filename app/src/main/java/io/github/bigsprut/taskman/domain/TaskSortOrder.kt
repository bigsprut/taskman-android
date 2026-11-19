package io.github.bigsprut.taskman.domain

import java.util.Locale

/**
 * Порядок задач в списке (§5.3-Ф5: «порядок сортировки»).
 *
 * Выбор живёт в локальных настройках устройства (DataStore), а не на сервере: контракт
 * `PATCH /api/settings` знает только категорию по умолчанию, а порядок — вкус пользователя
 * конкретного устройства.
 */
enum class TaskSortOrder(val key: String) {

    /** Как в Ф0: `STATUS_ORDER` (в работе → созданные → выполненные → отменённые), свежие выше. */
    STATUS("status"),

    /** По свежести даты статуса, без группировки по статусу. */
    FRESHNESS("freshness"),

    /** По названию (без учёта регистра). */
    TITLE("title"),
    ;

    /** Компаратор для списка. Подзадачи всегда сортируются `STATUS_ORDER` — иначе теряется смысл. */
    fun comparator(): Comparator<Task> = when (this) {
        STATUS -> StatusOrder.comparator
        FRESHNESS -> compareByDescending<Task> { it.statusDateTs }.thenBy { it.id }
        TITLE -> compareBy<Task> { it.title.lowercase(Locale.ROOT) }.thenBy { it.id }
    }

    companion object {
        fun fromKey(key: String?): TaskSortOrder =
            entries.firstOrNull { it.key == key } ?: STATUS
    }
}
