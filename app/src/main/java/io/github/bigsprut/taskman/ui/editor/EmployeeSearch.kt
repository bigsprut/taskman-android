package io.github.bigsprut.taskman.ui.editor

/**
 * Фильтр справочника сотрудников для листа выбора (ТЗ §5.3-Ф2: «выбор из списка с поиском»).
 *
 * Отдельная функция, а не логика внутри композабла: справочник может быть большим, и правило
 * поиска (регистронезависимо, без учёта краевых пробелов) стоит проверить обычным JVM-тестом.
 *
 * @param names все имена в порядке справочника.
 * @param query то, что ввёл пользователь; пусто/пробелы — весь список.
 */
internal fun filterEmployees(names: List<String>, query: String): List<String> {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return names
    return names.filter { name -> name.contains(trimmed, ignoreCase = true) }
}
