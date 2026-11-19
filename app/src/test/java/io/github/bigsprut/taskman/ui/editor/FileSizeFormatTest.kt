package io.github.bigsprut.taskman.ui.editor

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Размер файла вложения для строки редактора (§5.3-Ф3).
 *
 * Дробный разделитель берётся из локали устройства, поэтому КБ/МБ проверяются регуляркой,
 * допускающей и точку, и запятую: тест не должен зависеть от языка системы.
 */
class FileSizeFormatTest {

    @Test
    fun `байты печатаются без дробной части`() {
        assertThat(formatFileSize(0)).isEqualTo("0 Б")
        assertThat(formatFileSize(1023)).isEqualTo("1023 Б")
    }

    @Test
    fun `килобайты печатаются с одним знаком после запятой`() {
        assertThat(formatFileSize(1024)).matches("""^1[.,]0 КБ$""")
        assertThat(formatFileSize(1536)).matches("""^1[.,]5 КБ$""")
    }

    @Test
    fun `мегабайты печатаются с одним знаком после запятой`() {
        assertThat(formatFileSize(1024L * 1024)).matches("""^1[.,]0 МБ$""")
        assertThat(formatFileSize(1024L * 1024 * 5 / 2)).matches("""^2[.,]5 МБ$""")
    }

    @Test
    fun `граница перехода в килобайты — 1024 байта`() {
        assertThat(formatFileSize(1023)).endsWith(" Б")
        assertThat(formatFileSize(1024)).endsWith(" КБ")
    }
}
