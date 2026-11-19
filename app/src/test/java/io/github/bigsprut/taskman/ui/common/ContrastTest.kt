package io.github.bigsprut.taskman.ui.common

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.google.common.truth.Truth.assertThat
import kotlin.math.max
import kotlin.math.min
import org.junit.Test

/**
 * Авто-контраст текста на залитом фоне (ТЗ §5.3-Ф1: «цветной текст на залитом фоне —
 * авто-контраст по яркости»).
 *
 * Решение принимается по относительной яркости фона и порогу 0.18: выше порога текст тёмный,
 * ниже — белый. Тесты фиксируют не только выбранный цвет, но и то, что пара «фон/текст»
 * остаётся читаемой: отношение яркостей по WCAG не ниже 4:1 (грубая проверка, а не точная
 * сертификация AA).
 */
class ContrastTest {

    @Test
    fun `на светлом жёлтом фоне выбирается тёмный текст`() {
        val background = Color(0xFFFFF428)

        val text = contrastOn(background)

        assertThat(text).isEqualTo(DARK_TEXT)
        // Направление проверяем по яркости: тёмный текст светлее не должен быть.
        assertThat(text.luminance()).isLessThan(background.luminance())
    }

    @Test
    fun `на тёмно-синем фоне выбирается белый текст`() {
        val background = Color(0xFF2C52FF)

        val text = contrastOn(background)

        assertThat(text).isEqualTo(LIGHT_TEXT)
        assertThat(text.luminance()).isGreaterThan(background.luminance())
    }

    @Test
    fun `золотистый фон тоже получает тёмный текст`() {
        val background = Color(0xFFE5AD00)

        val text = contrastOn(background)

        assertThat(text).isEqualTo(DARK_TEXT)
        assertThat(text.luminance()).isLessThan(background.luminance())
    }

    @Test
    fun `насыщенный оранжевый остаётся выше порога — текст тёмный`() {
        // Фактическое поведение по порогу 0.18: яркость #f63c00 ≈ 0.228, то есть выше 0.18,
        // поэтому алгоритм выбирает тёмный текст, хотя оранжевый выглядит насыщенным и глазом
        // «тёмным» не кажется. Контраст при этом ≈4.5:1 — на границе WCAG AA, то есть
        // читаемо. Фиксируем как есть: если порог поменяют, тест это заметит.
        val background = Color(0xFFF63C00)

        val text = contrastOn(background)

        assertThat(text).isEqualTo(DARK_TEXT)
        assertThat(background.luminance()).isGreaterThan(0.18f)
    }

    @Test
    fun `выбранный текст читается на светлых и тёмных фонах`() {
        val backgrounds = listOf(
            Color(0xFFFFFFFF),
            Color(0xFFFFF428),
            Color(0xFFE5AD00),
            Color(0xFFF63C00),
            Color(0xFF2C52FF),
            Color(0xFF000000),
        )

        backgrounds.forEach { background ->
            val text = contrastOn(background)
            // Ровно два цвета текста: иначе «авто-контраст» превратился бы в подбор оттенков.
            assertThat(text).isAnyOf(DARK_TEXT, LIGHT_TEXT)

            // Грубая проверка читаемости по WCAG: (L1 + 0.05) / (L2 + 0.05) >= 4.
            val first = max(text.luminance(), background.luminance()) + 0.05f
            val second = min(text.luminance(), background.luminance()) + 0.05f
            assertThat(first / second).isAtLeast(4.0f)
        }
    }

    private companion object {
        /** Тёмный текст M3 (`onSurface`), он же [contrastOn] для светлых фонов. */
        val DARK_TEXT: Color = Color(0xFF1C1B1F)

        /** Белый текст для тёмных фонов. */
        val LIGHT_TEXT: Color = Color(0xFFFFFFFF)
    }
}
