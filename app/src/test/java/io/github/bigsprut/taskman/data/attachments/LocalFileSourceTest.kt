package io.github.bigsprut.taskman.data.attachments

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Безопасное имя файла вложения для записи в кэш.
 *
 * Имя приходит из чужого приложения (photo picker, «Поделиться», открытие документа), поэтому
 * запись обязана остаться внутри каталога вложений: каталоги отбрасываются, запрещённые для
 * файловой системы символы заменяются, пустое имя получает запасное.
 */
class LocalFileSourceTest {

    @Test
    fun `путь с переходами вверх схлопывается до имени файла`() {
        // Иначе имя из другого приложения записало бы файл куда угодно.
        assertThat(safeAttachmentFileName("../../etc/passwd")).isEqualTo("passwd")
    }

    @Test
    fun `запрещённые символы заменяются подчёркиванием`() {
        assertThat(safeAttachmentFileName("a*b?.txt")).isEqualTo("a_b_.txt")

        // Двоеточие на Windows трактуется как буква диска и отбрасывается вместе с префиксом,
        // поэтому для `a:b*c?.txt` проверяем не букву, а отсутствие всех запрещённых символов —
        // это свойство одинаково на любой ОС.
        val windowsLike = safeAttachmentFileName("a:b*c?.txt")
        val leftForbidden = FORBIDDEN_CHARACTERS.filter { it in windowsLike }
        assertThat(leftForbidden).isEmpty()
    }

    @Test
    fun `управляющие символы не попадают в имя`() {
        assertThat(safeAttachmentFileName("a\u0000b\u001Fc.txt")).isEqualTo("a_b_c.txt")
    }

    @Test
    fun `пустое имя и одни пробелы дают запасное имя`() {
        assertThat(safeAttachmentFileName("")).isEqualTo("attachment")
        assertThat(safeAttachmentFileName("   ")).isEqualTo("attachment")
    }

    @Test
    fun `нормальное имя не меняется`() {
        assertThat(safeAttachmentFileName("photo.jpg")).isEqualTo("photo.jpg")
        assertThat(safeAttachmentFileName("отчёт 2026.pdf")).isEqualTo("отчёт 2026.pdf")
    }

    private companion object {
        /** Символы, недопустимые в имени файла (Windows строже остальных). */
        const val FORBIDDEN_CHARACTERS = "\\/:*?\"<>|"
    }
}
