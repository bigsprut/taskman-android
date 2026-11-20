package io.github.bigsprut.taskman.ui.common

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlin.math.max
import kotlin.math.min
import org.junit.Test

/**
 * Авто-контраст текста на залитом фоне (ТЗ §5.2: «текст на цветном фоне — авто-контраст
 * по яркости»).
 *
 * Цвет текста выбирается по фактическому коэффициенту WCAG между фоном и двумя кандидатами
 * (тёмный `#1C1B1F` из палитры M3 и белый) — так точка перехода не зависит от порога яркости
 * и на средних тонах выбирается объективно более контрастный вариант.
 *
 * Тесты фиксируют: набор фонов из реальной палитры (теги/категории), читаемость пары
 * «фон/текст» по WCAG и корректность самой метрики контраста.
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
    fun `насыщенный оранжевый читается тёмным текстом`() {
        // #f63c00 — яркий оранжевый тега из фикстуры: по метрике WCAG тёмный текст даёт
        // больший контраст, чем белый (≈4.4 против ≈3.8), поэтому алгоритм обязан выбрать
        // тёмный. Если правило сменят, тест это заметит.
        val background = Color(0xFFF63C00)

        val text = contrastOn(background)

        assertThat(text).isEqualTo(DARK_TEXT)
        assertThat(contrastRatio(background, DARK_TEXT))
            .isAtLeast(contrastRatio(background, LIGHT_TEXT))
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

    @Test
    fun `на светлой заливке статуса выбирается тёмный текст`() {
        assertWithMessage("пастельно-жёлтая заливка светлая")
            .that(contrastOn(Color(0xFFF7E08A)))
            .isEqualTo(DARK_TEXT)
    }

    @Test
    fun `на тёмной заливке статуса выбирается светлый текст`() {
        assertWithMessage("тёмно-зелёная заливка тёмная")
            .that(contrastOn(Color(0xFF2E5B3C)))
            .isEqualTo(LIGHT_TEXT)
    }

    @Test
    fun `на среднем тоне выбирается объективно лучший из двух текстов`() {
        val midTone = Color(0xFF8A8A8A)
        val chosen = contrastOn(midTone)
        val other = if (chosen == DARK_TEXT) LIGHT_TEXT else DARK_TEXT

        assertWithMessage("выбранный текст должен давать не меньший контраст, чем альтернатива")
            .that(contrastRatio(midTone, chosen) >= contrastRatio(midTone, other))
            .isTrue()
    }

    @Test
    fun `контраст чистого чёрного и белого равен 21`() {
        val ratio = contrastRatio(Color.Black, Color.White)
        assertWithMessage("WCAG: #000 на #fff = 21:1, получено $ratio")
            .that(ratio > 20.9f && ratio < 21.1f)
            .isTrue()
    }

    @Test
    fun `мягкий тёмный текст на белом даёт контраст выше AA`() {
        val ratio = contrastRatio(DARK_TEXT, LIGHT_TEXT)
        assertWithMessage("M3-текст #1C1B1F на белом должен быть заметно выше 4.5, получено $ratio")
            .that(ratio > 15f)
            .isTrue()
    }

    @Test
    fun `контраст симметричен`() {
        val a = Color(0xFFF7E08A)
        val b = Color(0xFF5C4A00)
        assertWithMessage("порядок аргументов не должен влиять")
            .that(contrastRatio(a, b))
            .isWithin(1e-5f)
            .of(contrastRatio(b, a))
    }

    @Test
    fun `контраст цвета с самим собой равен единице`() {
        val color = Color(0xFFB7E4C7)
        assertWithMessage("одинаковые цвета дают 1:1")
            .that(contrastRatio(color, color))
            .isWithin(1e-5f)
            .of(1f)
    }

    private companion object {
        /** Тёмный текст M3 (`onSurface`), он же [contrastOn] для светлых фонов. */
        val DARK_TEXT: Color = Color(0xFF1C1B1F)

        /** Белый текст для тёмных фонов. */
        val LIGHT_TEXT: Color = Color(0xFFFFFFFF)
    }
}
