package io.github.bigsprut.taskman.ui.common

import androidx.compose.ui.graphics.Color

/**
 * Разбор цвета из ответа сервера (`"#f63c00"`, `"#80ffffff"`).
 *
 * Отдельная чистая функция, а не `android.graphics.Color.parseColor`: парсинг легко
 * покрыть JVM-тестом, и он не тянет Android-зависимость в слой маппинга.
 * Некорректный/пустой цвет не роняет список — отдаётся [fallback] темы.
 */
fun parseTaskmanColor(raw: String?, fallback: Color): Color {
    val hex = raw?.trim()?.removePrefix("#")?.takeIf { it.isNotEmpty() } ?: return fallback
    if (hex.length != 6 && hex.length != 8) return fallback
    val parsed = hex.toLongOrNull(radix = 16) ?: return fallback
    return if (hex.length == 6) {
        // #RRGGBB — непрозрачный цвет.
        Color(0xFF000000L or parsed)
    } else {
        // #AARRGGBB — альфа приходит первой строкой цвета.
        Color(parsed)
    }
}
