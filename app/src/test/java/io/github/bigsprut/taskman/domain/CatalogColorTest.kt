package io.github.bigsprut.taskman.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Нормализация цвета тега/категории (§5.3-Ф5).
 *
 * Цвет вводят руками, а сервер и фикстура ждут ровно `#rrggbb` в нижнем регистре: всё, что
 * не похоже на цвет, на сервер не отправляется (UI показывает подсказку и подставляет
 * [CatalogColor.DEFAULT]).
 */
class CatalogColorTest {

    @Test
    fun `шестизначный цвет остаётся как есть`() {
        assertThat(CatalogColor.normalize("#e5ad00")).isEqualTo("#e5ad00")
    }

    @Test
    fun `верхний регистр приводится к нижнему`() {
        assertThat(CatalogColor.normalize("#E5AD00")).isEqualTo("#e5ad00")
    }

    @Test
    fun `цвет без решётки получает решётку`() {
        assertThat(CatalogColor.normalize("e5ad00")).isEqualTo("#e5ad00")
    }

    @Test
    fun `без решётки и в верхнем регистре тоже разбирается`() {
        assertThat(CatalogColor.normalize("E5AD00")).isEqualTo("#e5ad00")
    }

    @Test
    fun `трёхзначный цвет разворачивается в шесть знаков`() {
        assertThat(CatalogColor.normalize("#abc")).isEqualTo("#aabbcc")
        // Каждая пара — удвоенная шестнадцатеричная цифра, регистр сохраняется только до приведения.
        assertThat(CatalogColor.normalize("#ABC")).isEqualTo("#aabbcc")
    }

    @Test
    fun `пробелы по краям обрезаются`() {
        assertThat(CatalogColor.normalize(" #AbC ")).isEqualTo("#aabbcc")
    }

    @Test
    fun `null пустая и пробельная строка дают null`() {
        assertThat(CatalogColor.normalize(null)).isNull()
        assertThat(CatalogColor.normalize("")).isNull()
        assertThat(CatalogColor.normalize("   ")).isNull()
    }

    @Test
    fun `неверная длина или мусор дают null`() {
        assertThat(CatalogColor.normalize("#12345")).isNull()
        assertThat(CatalogColor.normalize("zzzzzz")).isNull()
        // Восемь знаков — формат Android-цвета с альфой; сервер его не понимает.
        assertThat(CatalogColor.normalize("#e5ad00ff")).isNull()
    }

    @Test
    fun `цвет по умолчанию нормализуется сам в себя`() {
        assertThat(CatalogColor.normalize(CatalogColor.DEFAULT)).isEqualTo(CatalogColor.DEFAULT)
        assertThat(CatalogColor.DEFAULT).isEqualTo("#4a5c92")
    }
}
