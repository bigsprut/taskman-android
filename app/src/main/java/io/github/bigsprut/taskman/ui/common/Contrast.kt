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
fun contrastOn(background: Color): Color =
    if (background.luminance() > CONTRAST_THRESHOLD) {
        Color(DARK_TEXT)
    } else {
        Color(LIGHT_TEXT)
    }

/** Относительная яркость, выше которой нужен тёмный текст. */
private const val CONTRAST_THRESHOLD = 0.18f

/** Не чистый чёрный/белый: мягче для глаза и совпадает с палитрой M3. */
private const val DARK_TEXT = 0xFF1C1B1F
private const val LIGHT_TEXT = 0xFFFFFFFF
