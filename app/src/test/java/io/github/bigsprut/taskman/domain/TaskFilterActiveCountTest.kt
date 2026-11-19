package io.github.bigsprut.taskman.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Счётчик условий фильтра — для компактной строки «Отборы · N», которая видна, когда блок
 * фильтров свёрнут (§5.3-Ф1, решение владельца 20.11).
 *
 * Считаются «штуки условий», а не задачи: смена статуса — одно условие, каждая выбранная
 * категория и каждый выбранный тег — по одному. Цифра не должна зависеть от того, какие
 * именно id выбраны, иначе она «прыгала» бы при замене одного фильтра другим.
 *
 * Отдельно фиксируется инвариант: `activeCount() == 0` ровно тогда, когда фильтр равен
 * умолчанию. Иначе строка «Отборы · 0» появлялась бы при непустом отборе — или пропадала
 * при пустом.
 */
class TaskFilterActiveCountTest {

    @Test
    fun `фильтр по умолчанию не содержит условий`() {
        // Умолчание — «Активные» без категорий и тегов: сворачивать блок нечего.
        assertThat(TaskFilter().activeCount()).isEqualTo(0)
        assertThat(TaskFilter.DEFAULTS.activeCount()).isEqualTo(0)
    }

    @Test
    fun `каждый статус кроме активного считается одним условием`() {
        assertThat(TaskFilter(status = StatusFilter.COMPLETED).activeCount()).isEqualTo(1)
        assertThat(TaskFilter(status = StatusFilter.CANCELED).activeCount()).isEqualTo(1)
        // «Все» — тоже явный выбор пользователя, хотя задач он не отсекает.
        assertThat(TaskFilter(status = StatusFilter.ALL).activeCount()).isEqualTo(1)
    }

    @Test
    fun `каждая выбранная категория — отдельное условие`() {
        assertThat(TaskFilter(categoryIds = setOf("cat-a")).activeCount()).isEqualTo(1)
        assertThat(TaskFilter(categoryIds = setOf("cat-a", "cat-b")).activeCount()).isEqualTo(2)
    }

    @Test
    fun `каждый выбранный тег — отдельное условие`() {
        assertThat(TaskFilter(tagIds = setOf("tag-a", "tag-b", "tag-c")).activeCount())
            .isEqualTo(3)
    }

    @Test
    fun `категории и теги складываются`() {
        val filter = TaskFilter(
            categoryIds = setOf("cat-a", "cat-b"),
            tagIds = setOf("tag-a"),
        )

        assertThat(filter.activeCount()).isEqualTo(3)
    }

    @Test
    fun `статус, категория и тег складываются`() {
        val filter = TaskFilter(
            status = StatusFilter.COMPLETED,
            categoryIds = setOf("cat-a"),
            tagIds = setOf("tag-a"),
        )

        assertThat(filter.activeCount()).isEqualTo(3)
    }

    @Test
    fun `снятие категории уменьшает счётчик`() {
        val twoCategories = TaskFilter.DEFAULTS
            .toggledCategory("cat-a")
            .toggledCategory("cat-b")
        assertThat(twoCategories.activeCount()).isEqualTo(2)

        // Первое снятие: осталась одна категория — одно условие.
        val oneCategory = twoCategories.toggledCategory("cat-a")
        assertThat(oneCategory.activeCount()).isEqualTo(1)

        // Сняли последнюю — условий не осталось, фильтр снова по умолчанию.
        val noCategories = oneCategory.toggledCategory("cat-b")
        assertThat(noCategories.activeCount()).isEqualTo(0)
        assertThat(noCategories).isEqualTo(TaskFilter.DEFAULTS)
    }

    @Test
    fun `cleared обнуляет счётчик`() {
        val dirty = TaskFilter(
            status = StatusFilter.CANCELED,
            categoryIds = setOf("cat-a"),
            tagIds = setOf("tag-a", "tag-b"),
        )
        assertThat(dirty.activeCount()).isEqualTo(4)

        assertThat(dirty.cleared().activeCount()).isEqualTo(0)
    }

    @Test
    fun `счётчик зависит от количества, а не от значений id`() {
        val first = TaskFilter(categoryIds = setOf("cat-a", "cat-b"))
        val second = TaskFilter(categoryIds = setOf("cat-x", "cat-y"))

        // Другие id, то же количество — та же цифра на кнопке «Отборы».
        assertThat(first.activeCount()).isEqualTo(second.activeCount())
        // Порядок внутри множества на счётчик не влияет.
        assertThat(TaskFilter(tagIds = setOf("b", "a")).activeCount())
            .isEqualTo(TaskFilter(tagIds = setOf("a", "b")).activeCount())
    }

    @Test
    fun `ноль условий тогда и только тогда, когда фильтр по умолчанию`() {
        val samples = listOf(
            TaskFilter(),
            TaskFilter(status = StatusFilter.COMPLETED),
            TaskFilter(status = StatusFilter.ALL),
            TaskFilter(categoryIds = setOf("cat-a")),
            TaskFilter(tagIds = setOf("tag-a", "tag-b")),
            TaskFilter(
                status = StatusFilter.CANCELED,
                categoryIds = setOf("cat-a"),
                tagIds = setOf("tag-a"),
            ),
        )

        samples.forEach { filter ->
            assertThat(filter.activeCount() == 0).isEqualTo(filter.isDefault)
        }
    }
}
