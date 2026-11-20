package io.github.bigsprut.taskman.ui.common

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * Цвет текста на залитом фоне — по относительной яркости фона (ТЗ §5.3-Ф1: «цветной текст
 * на залитом фоне — авто-контраст по яркости»).
 *
 * Порог 0.18 — точка, где контраст с чёрным и белым равны по WCAG: при большей яркости
 * фона читаемым становится тёмный текст, при меньшей — белый.
 */
fun contrastOn(background: Color): Color {
    val dark = Color(DARK_TEXT)
    val light = Color(LIGHT_TEXT)
    // Выбираем не по «порогу яркости», а по фактическому коэффициенту WCAG: у мягкого
    // тёмного текста (#1C1B1F) точка равенства с белым смещена относительно чистого чёрного,
    // и порог 0.18 мог бы дать контраст ниже AA на средних тонах.
    return if (contrastRatio(background, dark) >= contrastRatio(background, light)) dark else light
}

/**
 * Коэффициент контраста WCAG 2.x между двумя цветами: (Lmax + 0.05) / (Lmin + 0.05).
 * Вынесен наружу, чтобы палитры могли проверяться тестами и переиспользоваться.
 */
fun contrastRatio(first: Color, second: Color): Float {
    val a = first.luminance()
    val b = second.luminance()
    val lighter = maxOf(a, b)
    val darker = minOf(a, b)
    return (lighter + 0.05f) / (darker + 0.05f)
}

/** Не чистый чёрный/белый: мягче для глаза и совпадает с палитрой M3. */
private const val DARK_TEXT = 0xFF1C1B1F
private const val LIGHT_TEXT = 0xFFFFFFFF
