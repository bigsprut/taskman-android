package io.github.bigsprut.taskman.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Порядок задач в списке (§5.3-Ф5).
 *
 * `STATUS` повторяет порядок Ф0 (группы по статусу), `FRESHNESS` смотрит только на дату
 * смены статуса, `TITLE` — на название без учёта регистра. Во всех режимах при полном
 * равенстве решает id: иначе `LazyColumn` переставлял бы строки между обновлениями.
 */
class TaskSortOrderTest {

    @Test
    fun `fromKey узнаёт ключи из настроек`() {
        assertThat(TaskSortOrder.fromKey("status")).isEqualTo(TaskSortOrder.STATUS)
        assertThat(TaskSortOrder.fromKey("freshness")).isEqualTo(TaskSortOrder.FRESHNESS)
        assertThat(TaskSortOrder.fromKey("title")).isEqualTo(TaskSortOrder.TITLE)
        assertThat(TaskSortOrder.fromKey(TaskSortOrder.TITLE.key)).isEqualTo(TaskSortOrder.TITLE)
    }

    @Test
    fun `неизвестный ключ и null дают статус`() {
        assertThat(TaskSortOrder.fromKey(null)).isEqualTo(TaskSortOrder.STATUS)
        assertThat(TaskSortOrder.fromKey("")).isEqualTo(TaskSortOrder.STATUS)
        assertThat(TaskSortOrder.fromKey("unknown")).isEqualTo(TaskSortOrder.STATUS)
        // Ключ регистрозависим: «TITLE» — уже не наш ключ, безопасный откат к STATUS.
        assertThat(TaskSortOrder.fromKey("TITLE")).isEqualTo(TaskSortOrder.STATUS)
    }

    @Test
    fun `STATUS группирует по статусу, внутри группы свежие выше`() {
        val sorted = sortedBy(
            TaskSortOrder.STATUS,
            task("completed", TaskStatus.COMPLETED, statusDateTs = 900.0),
            task("created-old", TaskStatus.CREATED, statusDateTs = 100.0),
            task("inprogress", TaskStatus.IN_PROGRESS, statusDateTs = 50.0),
            task("created-new", TaskStatus.CREATED, statusDateTs = 300.0),
        )

        // У inprogress самая старая дата — группа всё равно важнее свежести.
        assertThat(sorted.map { it.id })
            .containsExactly("inprogress", "created-new", "created-old", "completed")
            .inOrder()
    }

    @Test
    fun `FRESHNESS не группирует по статусу`() {
        val sorted = sortedBy(
            TaskSortOrder.FRESHNESS,
            task("created", TaskStatus.CREATED, statusDateTs = 100.0),
            task("completed", TaskStatus.COMPLETED, statusDateTs = 500.0),
            task("inprogress", TaskStatus.IN_PROGRESS, statusDateTs = 300.0),
        )

        // Выполненная задача со свежей датой идёт первой: режим «только по дате».
        assertThat(sorted.map { it.id })
            .containsExactly("completed", "inprogress", "created")
            .inOrder()
    }

    @Test
    fun `FRESHNESS при равной дате решает id`() {
        val sorted = sortedBy(
            TaskSortOrder.FRESHNESS,
            task("b", TaskStatus.CREATED, statusDateTs = 100.0),
            task("a", TaskStatus.COMPLETED, statusDateTs = 100.0),
        )

        assertThat(sorted.map { it.id }).containsExactly("a", "b").inOrder()
    }

    @Test
    fun `TITLE сравнивает названия без учёта регистра`() {
        val sorted = sortedBy(
            TaskSortOrder.TITLE,
            task("z", TaskStatus.CREATED, title = "яблоко"),
            task("m", TaskStatus.CREATED, title = "Апельсин"),
            task("a", TaskStatus.CREATED, title = "Яблоко"),
        )

        // «апельсин» < «яблоко», а «яблоко» и «Яблоко» равны — решает id.
        assertThat(sorted.map { it.id }).containsExactly("m", "a", "z").inOrder()
    }

    @Test
    fun `порядок не зависит от исходного порядка элементов`() {
        val tasks = listOf(
            task("b", TaskStatus.CREATED, title = "яблоко", statusDateTs = 100.0),
            task("a", TaskStatus.CREATED, title = "Яблоко", statusDateTs = 100.0),
            task("c", TaskStatus.COMPLETED, title = "апельсин", statusDateTs = 200.0),
        )
        val reversed = tasks.reversed()

        TaskSortOrder.entries.forEach { order ->
            assertThat(reversed.sortedWith(order.comparator()).map { it.id })
                .isEqualTo(tasks.sortedWith(order.comparator()).map { it.id })
        }
    }

    @Test
    fun `comparator доступен как обычный Comparator`() {
        // Компаратор отдаётся наружу (`sortedWith` в репозитории), поэтому проверяем,
        // что разные режимы действительно дают разные порядки на одном наборе.
        val tasks = listOf(
            task("inprogress", TaskStatus.IN_PROGRESS, statusDateTs = 100.0),
            task("completed", TaskStatus.COMPLETED, statusDateTs = 500.0),
        )

        assertThat(tasks.sortedWith(TaskSortOrder.STATUS.comparator()).map { it.id })
            .containsExactly("inprogress", "completed")
            .inOrder()
        assertThat(tasks.sortedWith(TaskSortOrder.FRESHNESS.comparator()).map { it.id })
            .containsExactly("completed", "inprogress")
            .inOrder()
    }

    private fun sortedBy(order: TaskSortOrder, vararg tasks: Task): List<Task> =
        tasks.sortedWith(order.comparator())

    /** Доменная задача без «шумовых» полей: тест проверяет только порядок. */
    private fun task(
        id: String,
        status: TaskStatus,
        title: String = "Задача $id",
        statusDateTs: Double = 0.0,
    ): Task = Task(
        id = id,
        title = title,
        status = status,
        statusDateTs = statusDateTs,
        parentId = null,
        categoryId = null,
        categoryTitle = null,
        categoryColor = null,
        source = null,
    )
}
