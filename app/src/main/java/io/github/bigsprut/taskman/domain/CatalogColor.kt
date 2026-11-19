package io.github.bigsprut.taskman.domain

/**
 * Цвет тега/категории в формате сервера: `#rrggbb` (так цвета лежат в фикстуре и в `/api/state`).
 *
 * Нормализация нужна, потому что цвет вводят руками: принимаем `#RGB`, `#RRGGBB` и вариант без
 * решётки, всё остальное — `null` (не отправляем серверу мусор, UI показывает подсказку).
 */
object CatalogColor {

    private val hex = Regex("^#?([0-9a-fA-F]{3}|[0-9a-fA-F]{6})$")

    /** @return `#rrggbb` в нижнем регистре или `null`, если строка не похожа на цвет. */
    fun normalize(raw: String?): String? {
        val candidate = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val match = hex.matchEntire(candidate) ?: return null
        val digits = match.groupValues[1].lowercase()
        val expanded = if (digits.length == 3) {
            digits.map { "".plus(it).plus(it) }.joinToString(separator = "")
        } else {
            digits
        }
        return "#$expanded"
    }

    /** Цвет по умолчанию для новых тегов/категорий (мягкий синий M3). */
    const val DEFAULT: String = "#4a5c92"
}
