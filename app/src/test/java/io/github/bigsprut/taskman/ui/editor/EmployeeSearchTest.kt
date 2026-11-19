package io.github.bigsprut.taskman.ui.editor

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Поиск по справочнику сотрудников в листе выбора (§5.3-Ф2: «выбор из списка с поиском»).
 *
 * Правила простые, но их легко сломать незаметно: фильтр должен быть регистронезависимым,
 * не путать кириллицу с латиницей, игнорировать краевые пробелы запроса и сохранять порядок
 * справочника. Пустой ввод показывает весь список — иначе поиск «прячет» сотрудников сразу
 * после открытия листа.
 */
class EmployeeSearchTest {

    /** Небольшой справочник в «естественном» порядке — его и обязан сохранять фильтр. */
    private val names = listOf("Иван Петров", "Мария Иванова", "Пётр Сидоров", "Анна")

    @Test
    fun `пустой запрос возвращает весь список в порядке справочника`() {
        val result = filterEmployees(names, "")

        assertThat(result).isEqualTo(names)
        // Порядок — как в справочнике, а не по алфавиту и не по релевантности.
        assertThat(result).containsExactlyElementsIn(names).inOrder()
    }

    @Test
    fun `запрос из одних пробелов не фильтрует справочник`() {
        // Пробел — не поиск: пользователь просто коснулся поля и ушёл, список не должен опустеть.
        assertThat(filterEmployees(names, "   ")).isEqualTo(names)
        assertThat(filterEmployees(names, " \t\n ")).isEqualTo(names)
    }

    @Test
    fun `поиск по части имени не зависит от регистра`() {
        val people = listOf("Иван", "Марья", "Пётр")

        // Подстрока в любом месте имени и в любом регистре — одна и та же выдача.
        assertThat(filterEmployees(people, "ив")).containsExactly("Иван")
        assertThat(filterEmployees(people, "ИВАН")).containsExactly("Иван")
        assertThat(filterEmployees(people, "иВаН")).containsExactly("Иван")
    }

    @Test
    fun `краевые пробелы в запросе не мешают поиску`() {
        val people = listOf("Иван", "Пётр")

        assertThat(filterEmployees(people, "  Иван  ")).containsExactly("Иван")
        assertThat(filterEmployees(people, " ив ")).containsExactly("Иван")
    }

    @Test
    fun `нет совпадений — пустой список`() {
        // Пустая выдача, а не весь справочник: иначе пользователь решит, что поиск не работает.
        assertThat(filterEmployees(names, "Зоя")).isEmpty()
        assertThat(filterEmployees(names, "иван петров с отчеством")).isEmpty()
    }

    @Test
    fun `кириллица и латиница не путаются`() {
        val people = listOf("Иван", "Ivan")

        // Похожие по звучанию, но разные буквы: «Ivan» не должен находить «Иван».
        assertThat(filterEmployees(people, "Ivan")).containsExactly("Ivan")
        assertThat(filterEmployees(people, "Иван")).containsExactly("Иван")
    }

    @Test
    fun `совпадения идут в порядке справочника, а не в порядке находки`() {
        val people = listOf("Анна Иванова", "Пётр", "Иван", "Марина Иванченко")

        assertThat(filterEmployees(people, "иван"))
            .containsExactly("Анна Иванова", "Иван", "Марина Иванченко")
            .inOrder()
    }

    @Test
    fun `фильтрация не меняет исходный список`() {
        // Список неизменяемый: попытка «почистить» его на месте упала бы здесь же.
        val source = listOf("Иван", "Пётр", "Мария")

        val result = filterEmployees(source, "Иван")

        assertThat(result).containsExactly("Иван")
        // Новый список, а не тот же объект: вызывающий код вправе ждать независимый результат.
        assertThat(result).isNotSameInstanceAs(source)
        // Исходный справочник остался целым — фильтр не должен его обрезать.
        assertThat(source).containsExactly("Иван", "Пётр", "Мария").inOrder()
    }
}
