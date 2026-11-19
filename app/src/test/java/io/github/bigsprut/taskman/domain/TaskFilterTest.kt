package io.github.bigsprut.taskman.domain

import com.google.common.truth.Truth.assertThat
import io.github.bigsprut.taskman.testutil.task
import org.junit.Test

/**
 * Фильтр списка и счётчики для чипов (ТЗ §5.3-Ф1).
 *
 * Главное, что здесь фиксируется: «по умолчанию» — это «Активные» (`created` + `inprogress`),
 * пустой набор категорий/тегов означает «без ограничения», а несколько выбранных значений
 * складываются по ИЛИ. Фильтр при этом только отбирает строки и не меняет порядок — порядок
 * проверяется в `StatusOrderTest`.
 */
class TaskFilterTest {

    private val activeStatuses = listOf(TaskStatus.CREATED, TaskStatus.IN_PROGRESS)
    private val closedStatuses = listOf(TaskStatus.COMPLETED, TaskStatus.CANCELED)

    @Test
    fun `по умолчанию фильтр оставляет только активные задачи`() {
        val filter = TaskFilter()

        assertThat(filter.status).isEqualTo(StatusFilter.ACTIVE)
        assertThat(filter.isDefault).isTrue()
        assertThat(filter.categoryIds).isEmpty()
        assertThat(filter.tagIds).isEmpty()

        val tasks = activeStatuses.map { task("active-$it", it) } +
            closedStatuses.map { task("closed-$it", it) }

        assertThat(tasks.filter(filter::matches).map { it.id })
            .containsExactly("active-CREATED", "active-IN_PROGRESS")
    }

    @Test
    fun `закрытые фильтры отбирают свой статус, ALL пропускает всё`() {
        val tasks = TaskStatus.entries.map { task("task-$it", it) }

        assertThat(tasks.filter(TaskFilter(status = StatusFilter.COMPLETED)::matches).map { it.id })
            .containsExactly("task-COMPLETED")
        assertThat(tasks.filter(TaskFilter(status = StatusFilter.CANCELED)::matches).map { it.id })
            .containsExactly("task-CANCELED")
        assertThat(tasks.filter(TaskFilter(status = StatusFilter.ALL)::matches))
            .hasSize(TaskStatus.entries.size)
    }

    @Test
    fun `пустой набор категорий не ограничивает выборку`() {
        val filter = TaskFilter(status = StatusFilter.ALL)

        assertThat(filter.matches(task("with-category", TaskStatus.CREATED, categoryId = "cat-a")))
            .isTrue()
        // Задача без категории тоже проходит: «не выбрано» — это не «выбрано "нет"».
        assertThat(filter.matches(task("without-category", TaskStatus.CREATED, categoryId = null)))
            .isTrue()
    }

    @Test
    fun `одна категория отбирает только свои задачи`() {
        val filter = TaskFilter(status = StatusFilter.ALL, categoryIds = setOf("cat-a"))

        assertThat(filter.matches(task("mine", TaskStatus.CREATED, categoryId = "cat-a"))).isTrue()
        assertThat(filter.matches(task("other", TaskStatus.CREATED, categoryId = "cat-b"))).isFalse()
        assertThat(filter.matches(task("none", TaskStatus.CREATED, categoryId = null))).isFalse()
    }

    @Test
    fun `несколько категорий складываются по ИЛИ`() {
        val filter = TaskFilter(
            status = StatusFilter.ALL,
            categoryIds = setOf("cat-a", "cat-b"),
        )

        assertThat(filter.matches(task("a", TaskStatus.CREATED, categoryId = "cat-a"))).isTrue()
        assertThat(filter.matches(task("b", TaskStatus.CREATED, categoryId = "cat-b"))).isTrue()
        assertThat(filter.matches(task("c", TaskStatus.CREATED, categoryId = "cat-c"))).isFalse()
    }

    @Test
    fun `категория проверяется вместе со статусом`() {
        val filter = TaskFilter(
            status = StatusFilter.COMPLETED,
            categoryIds = setOf("cat-a"),
        )

        assertThat(filter.matches(task("match", TaskStatus.COMPLETED, categoryId = "cat-a")))
            .isTrue()
        // Категория совпала, но задача ещё активна — статус важнее.
        assertThat(filter.matches(task("wrong-status", TaskStatus.CREATED, categoryId = "cat-a")))
            .isFalse()
        // Статус совпал, но категория чужая.
        assertThat(filter.matches(task("wrong-category", TaskStatus.COMPLETED, categoryId = "cat-b")))
            .isFalse()
    }

    @Test
    fun `пустой набор тегов не ограничивает выборку`() {
        val filter = TaskFilter(status = StatusFilter.ALL)

        assertThat(filter.matches(task("tagged", TaskStatus.CREATED, tagIds = listOf("tag-a"))))
            .isTrue()
        assertThat(filter.matches(task("untagged", TaskStatus.CREATED, tagIds = emptyList())))
            .isTrue()
    }

    @Test
    fun `совпадение хотя бы по одному тегу задачи достаточно`() {
        val filter = TaskFilter(status = StatusFilter.ALL, tagIds = setOf("tag-b", "tag-c"))

        // У задачи несколько тегов: достаточно пересечения с выбранными (ИЛИ).
        assertThat(
            filter.matches(task("multi", TaskStatus.CREATED, tagIds = listOf("tag-a", "tag-b"))),
        ).isTrue()
        assertThat(filter.matches(task("only-c", TaskStatus.CREATED, tagIds = listOf("tag-c"))))
            .isTrue()
        assertThat(filter.matches(task("other", TaskStatus.CREATED, tagIds = listOf("tag-a"))))
            .isFalse()
        assertThat(filter.matches(task("untagged", TaskStatus.CREATED, tagIds = emptyList())))
            .isFalse()
    }

    @Test
    fun `withStatus меняет только статус`() {
        val base = TaskFilter(categoryIds = setOf("cat-a"), tagIds = setOf("tag-a"))

        val completed = base.withStatus(StatusFilter.COMPLETED)

        assertThat(completed.status).isEqualTo(StatusFilter.COMPLETED)
        assertThat(completed.categoryIds).containsExactly("cat-a")
        assertThat(completed.tagIds).containsExactly("tag-a")
        assertThat(completed.isDefault).isFalse()
        // Исходный фильтр не мутируется: состояние экрана — неизменяемое значение.
        assertThat(base.status).isEqualTo(StatusFilter.ACTIVE)
    }

    @Test
    fun `toggledCategory включает и выключает категорию`() {
        val once = TaskFilter.DEFAULTS.toggledCategory("cat-a")

        assertThat(once.categoryIds).containsExactly("cat-a")
        assertThat(once.isDefault).isFalse()

        val twice = once.toggledCategory("cat-a")

        assertThat(twice.categoryIds).isEmpty()
        assertThat(twice).isEqualTo(TaskFilter.DEFAULTS)
    }

    @Test
    fun `toggledTag включает и выключает тег`() {
        val once = TaskFilter.DEFAULTS.toggledTag("tag-a")

        assertThat(once.tagIds).containsExactly("tag-a")
        assertThat(once.isDefault).isFalse()

        val twice = once.toggledTag("tag-a")

        assertThat(twice.tagIds).isEmpty()
        assertThat(twice).isEqualTo(TaskFilter.DEFAULTS)
    }

    @Test
    fun `cleared сбрасывает все фильтры к значениям по умолчанию`() {
        val dirty = TaskFilter(
            status = StatusFilter.CANCELED,
            categoryIds = setOf("cat-a"),
            tagIds = setOf("tag-a"),
        )
        assertThat(dirty.isDefault).isFalse()

        val cleared = dirty.cleared()

        assertThat(cleared).isEqualTo(TaskFilter.DEFAULTS)
        assertThat(cleared.isDefault).isTrue()
        assertThat(cleared.status).isEqualTo(StatusFilter.ACTIVE)
    }
}

/**
 * Счётчики чипов: считаются по всему набору задач, а не по отфильтрованному — иначе цифра
 * на чипе менялась бы от выбора пользователя.
 */
class TaskCountsTest {

    @Test
    fun `of считает все, активные, выполненные и отменённые`() {
        val tasks = listOf(
            task("created", TaskStatus.CREATED),
            task("inprogress", TaskStatus.IN_PROGRESS),
            task("created-2", TaskStatus.CREATED),
            task("completed", TaskStatus.COMPLETED),
            task("canceled", TaskStatus.CANCELED),
        )

        val counts = TaskCounts.of(tasks)

        assertThat(counts.all).isEqualTo(5)
        assertThat(counts.active).isEqualTo(3)
        assertThat(counts.completed).isEqualTo(1)
        assertThat(counts.canceled).isEqualTo(1)
        assertThat(counts.forStatus(StatusFilter.ACTIVE)).isEqualTo(3)
        assertThat(counts.forStatus(StatusFilter.COMPLETED)).isEqualTo(1)
        assertThat(counts.forStatus(StatusFilter.CANCELED)).isEqualTo(1)
        assertThat(counts.forStatus(StatusFilter.ALL)).isEqualTo(5)
    }

    @Test
    fun `of собирает карты категорий и тегов по всем задачам`() {
        val tasks = listOf(
            task(
                "first",
                TaskStatus.CREATED,
                categoryId = "cat-a",
                tagIds = listOf("tag-1", "tag-2"),
            ),
            task(
                "second",
                TaskStatus.COMPLETED,
                categoryId = "cat-a",
                tagIds = listOf("tag-2"),
            ),
            // Ни категории, ни тегов — в картах такие задачи не появляются.
            task("third", TaskStatus.CANCELED, categoryId = null, tagIds = emptyList()),
        )

        val counts = TaskCounts.of(tasks)

        assertThat(counts.byCategory).containsExactly("cat-a", 2)
        assertThat(counts.byTag).containsExactly("tag-1", 1, "tag-2", 2)
    }

    @Test
    fun `of на пустом списке даёт нули и пустые карты`() {
        val counts = TaskCounts.of(emptyList())

        assertThat(counts).isEqualTo(TaskCounts(all = 0, active = 0, completed = 0, canceled = 0))
        assertThat(counts.byCategory).isEmpty()
        assertThat(counts.byTag).isEmpty()
    }
}
