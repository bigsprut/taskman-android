package io.github.bigsprut.taskman.ui.common

import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Разбор цвета категории из ответа сервера (ТЗ §6).
 */
class ColorParsingTest {

    private val fallback = Color(0xFF123456)

    @Test
    fun `шестизначный цвет становится непрозрачным`() {
        assertThat(parseTaskmanColor("#f63c00", fallback)).isEqualTo(Color(0xFFF63C00))
    }

    @Test
    fun `восьмизначный цвет сохраняет альфу`() {
        assertThat(parseTaskmanColor("#80ffffff", fallback)).isEqualTo(Color(0x80FFFFFF))
    }

    @Test
    fun `цвет без решётки тоже разбирается`() {
        assertThat(parseTaskmanColor("f63c00", fallback)).isEqualTo(Color(0xFFF63C00))
    }

    @Test
    fun `null даёт fallback`() {
        assertThat(parseTaskmanColor(null, fallback)).isEqualTo(fallback)
    }

    @Test
    fun `пустая строка даёт fallback`() {
        assertThat(parseTaskmanColor("", fallback)).isEqualTo(fallback)
    }

    @Test
    fun `невалидный hex даёт fallback`() {
        assertThat(parseTaskmanColor("#zzz", fallback)).isEqualTo(fallback)
    }

    @Test
    fun `цвет неверной длины даёт fallback`() {
        assertThat(parseTaskmanColor("#12345", fallback)).isEqualTo(fallback)
    }
}
