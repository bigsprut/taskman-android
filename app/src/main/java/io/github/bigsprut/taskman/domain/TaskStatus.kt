package io.github.bigsprut.taskman.domain

/**
 * Статус задачи по контракту API (ТЗ §4, §6).
 *
 * Порядок объявления задаёт `STATUS_ORDER` вывода в списке: активные задачи идут первыми,
 * закрытые — в конце (§5.3, Ф0).
 */
enum class TaskStatus(val wireValue: String, val sortIndex: Int) {
    IN_PROGRESS("inprogress", 0),
    CREATED("created", 1),
    COMPLETED("completed", 2),
    CANCELED("canceled", 3),
    ;

    companion object {
        /**
         * Разбор статуса из ответа сервера.
         *
         * Легаси-значение `needsAction` сервер принимает как `created` (§4), поэтому трактуем
         * его так же. Неизвестное значение не роняем и не теряем задачу — считаем `created`
         * (задача видна пользователю, расхождение контракта — повод для issue, а не для падения).
         */
        fun fromWireValue(raw: String?): TaskStatus = when (raw?.trim()?.lowercase()) {
            "inprogress", "in_progress" -> IN_PROGRESS
            "completed", "done" -> COMPLETED
            "canceled", "cancelled" -> CANCELED
            "created", "needsaction", "needs_action", null, "" -> CREATED
            else -> CREATED
        }
    }
}
