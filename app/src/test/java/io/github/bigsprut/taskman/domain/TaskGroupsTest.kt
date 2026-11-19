package io.github.bigsprut.taskman.domain

import com.google.common.truth.Truth.assertThat
import io.github.bigsprut.taskman.testutil.task
import org.junit.Test

/**
 * Группировка «родитель → подзадачи» и плоские строки для `LazyColumn` (ТЗ §5.3-Ф1).
 *
 * Правило после живой проверки на эмуляторе: **фильтр статуса отбирает только родителей**,
 * дети показываются всегда. Иначе раскрытие группы вроде `▸ 3/3` под фильтром «Активные»
 * вело бы в пустоту. Счётчик `▸ N/M` — прогресс родителя и считается по всем его детям.
 * Категории и теги к детям тоже не применяются: подзадача живёт в контексте родителя.
 *
 * Вход `build` — уже отсортированный список (сортировка — `StatusOrder`), поэтому тесты
 * подают его через [sortedGroups], как это делает репозиторий.
 */
class TaskGroupsTest {

    private fun sortedGroups(
        tasks: List<Task>,
        filter: TaskFilter = TaskFilter(status = StatusFilter.ALL),
    ): List<TaskGroup> = TaskGroups.build(StatusOrder.sort(tasks), filter)

    @Test
    fun `родитель без детей — группа с нулевым счётчиком`() {
        val groups = sortedGroups(listOf(task("parent", TaskStatus.CREATED)))

        val group = groups.single()
        assertThat(group.parent.id).isEqualTo("parent")
        assertThat(group.children).isEmpty()
        assertThat(group.totalChildren).isEqualTo(0)
        assertThat(group.completedChildren).isEqualTo(0)
        assertThat(group.hasChildren).isFalse()
    }

    @Test
    fun `два ребёнка, один выполнен — счётчик два и один`() {
        val groups = sortedGroups(
            listOf(
                task("parent", TaskStatus.CREATED),
                task("child-active", TaskStatus.CREATED, parentId = "parent"),
                task("child-done", TaskStatus.COMPLETED, parentId = "parent"),
            ),
        )

        val group = groups.single()
        assertThat(group.hasChildren).isTrue()
        assertThat(group.totalChildren).isEqualTo(2)
        assertThat(group.completedChildren).isEqualTo(1)
        // При фильтре ALL видны оба ребёнка; порядок — STATUS_ORDER, а не порядок входа.
        assertThat(group.children.map { it.id })
            .containsExactly("child-active", "child-done")
            .inOrder()
    }

    @Test
    fun `дети идут по статусу, внутри статуса — по свежести даты`() {
        val groups = sortedGroups(
            listOf(
                task("parent", TaskStatus.CREATED),
                // Намеренно вперемешку: build обязан отсортировать детей сам.
                task("done", TaskStatus.COMPLETED, statusDateTs = 500.0, parentId = "parent"),
                task("created-old", TaskStatus.CREATED, statusDateTs = 100.0, parentId = "parent"),
                task("inprogress", TaskStatus.IN_PROGRESS, statusDateTs = 50.0, parentId = "parent"),
                task("created-new", TaskStatus.CREATED, statusDateTs = 300.0, parentId = "parent"),
            ),
        )

        assertThat(groups.single().children.map { it.id })
            .containsExactly("inprogress", "created-new", "created-old", "done")
            .inOrder()
    }

    @Test
    fun `фильтр статуса отбирает только родителей`() {
        val groups = sortedGroups(
            listOf(
                task("parent-done", TaskStatus.COMPLETED),
                task("parent-active", TaskStatus.CREATED),
                task("child-of-done", TaskStatus.CREATED, parentId = "parent-done"),
                task("child-of-active", TaskStatus.COMPLETED, parentId = "parent-active"),
            ),
            filter = TaskFilter(status = StatusFilter.COMPLETED),
        )

        // В группы попал только родитель, чей статус прошёл фильтр; статус детей не важен —
        // активный ребёнок выполненного родителя остаётся в его группе.
        assertThat(groups.map { it.parent.id }).containsExactly("parent-done")
        assertThat(groups.single().children.map { it.id }).containsExactly("child-of-done")
    }

    @Test
    fun `дети показываются независимо от фильтра статуса`() {
        val groups = sortedGroups(
            listOf(
                task("parent", TaskStatus.CREATED),
                task("child-active", TaskStatus.IN_PROGRESS, parentId = "parent"),
                task("child-done", TaskStatus.COMPLETED, parentId = "parent"),
                task("child-canceled", TaskStatus.CANCELED, parentId = "parent"),
            ),
            filter = TaskFilter(status = StatusFilter.ACTIVE),
        )

        val group = groups.single()
        // Родитель активен — значит группа видна целиком, вместе с выполненным и отменённым
        // ребёнком: раскрытие не должно вести в пустоту, а `▸ N/M` — это прогресс родителя.
        assertThat(group.children.map { it.id })
            .containsExactly("child-active", "child-done", "child-canceled")
            .inOrder()
        assertThat(group.totalChildren).isEqualTo(3)
        assertThat(group.completedChildren).isEqualTo(1)
    }

    @Test
    fun `родитель, скрытый фильтром, уводит своих детей из списка`() {
        val groups = sortedGroups(
            listOf(
                task("parent-active", TaskStatus.CREATED),
                task("child-active", TaskStatus.IN_PROGRESS, parentId = "parent-active"),
                task("child-done", TaskStatus.COMPLETED, parentId = "parent-active"),
            ),
            filter = TaskFilter(status = StatusFilter.COMPLETED),
        )

        // Родитель не прошёл фильтр — группы нет, и дети не «всплывают» отдельными строками:
        // без родителя у них нет контекста, а раскрывать нечего.
        assertThat(groups).isEmpty()
        assertThat(TaskGroups.toRows(groups, expandedParents = setOf("parent-active"))).isEmpty()
    }

    @Test
    fun `сирота не попадает в группы`() {
        val groups = sortedGroups(
            listOf(
                task("parent", TaskStatus.CREATED),
                // Родитель отсутствует в снапшоте (удалён или не пришёл) — вложить некуда.
                task("orphan", TaskStatus.CREATED, parentId = "missing-parent"),
                task("orphan-done", TaskStatus.COMPLETED, parentId = "missing-parent"),
            ),
        )

        assertThat(groups.map { it.parent.id }).containsExactly("parent")
        assertThat(groups.single().totalChildren).isEqualTo(0)
        assertThat(TaskGroups.toRows(groups, expandedParents = setOf("missing-parent")))
            .hasSize(1)
    }

    @Test
    fun `toRows в свёрнутом виде отдаёт только родителей`() {
        val groups = sortedGroups(
            listOf(
                task("p1", TaskStatus.CREATED, statusDateTs = 200.0),
                task("c1", TaskStatus.CREATED, parentId = "p1"),
                task("p2", TaskStatus.CREATED, statusDateTs = 100.0),
                task("c2", TaskStatus.COMPLETED, parentId = "p2"),
            ),
        )

        val rows = TaskGroups.toRows(groups, expandedParents = emptySet())

        assertThat(rows.map { it.task.id }).containsExactly("p1", "p2").inOrder()
        assertThat(rows.map { it.isChild }).containsExactly(false, false).inOrder()
        // У родительской строки группа есть — по ней рисуется `▸ N/M` и стрелка.
        assertThat(rows.map { it.group?.parent?.id }).containsExactly("p1", "p2").inOrder()
    }

    @Test
    fun `toRows раскрывает выбранную группу сразу после родителя`() {
        val groups = sortedGroups(
            listOf(
                task("p1", TaskStatus.CREATED, statusDateTs = 200.0),
                task("c1-done", TaskStatus.COMPLETED, parentId = "p1"),
                task("c1-active", TaskStatus.IN_PROGRESS, parentId = "p1"),
                task("p2", TaskStatus.CREATED, statusDateTs = 100.0),
                task("c2", TaskStatus.CREATED, parentId = "p2"),
            ),
        )

        val rows = TaskGroups.toRows(groups, expandedParents = setOf("p1"))

        // Дети p1 — между p1 и p2, внутри — по STATUS_ORDER.
        assertThat(rows.map { it.task.id })
            .containsExactly("p1", "c1-active", "c1-done", "p2")
            .inOrder()
        assertThat(rows.filter { it.isChild }.map { it.task.id })
            .containsExactly("c1-active", "c1-done")
            .inOrder()
        assertThat(rows.filterNot { it.isChild }.map { it.task.id })
            .containsExactly("p1", "p2")
            .inOrder()
    }

    @Test
    fun `toRows даёт стабильный порядок при повторной сборке`() {
        val tasks = listOf(
            task("p1", TaskStatus.CREATED, statusDateTs = 200.0),
            task("c1", TaskStatus.CREATED, parentId = "p1"),
            task("c2", TaskStatus.COMPLETED, parentId = "p1"),
            task("p2", TaskStatus.CANCELED, statusDateTs = 100.0),
        )

        val first = TaskGroups.toRows(sortedGroups(tasks), expandedParents = setOf("p1"))
        val second = TaskGroups.toRows(sortedGroups(tasks), expandedParents = setOf("p1"))

        assertThat(first.map { it.task.id })
            .containsExactly("p1", "c1", "c2", "p2")
            .inOrder()
        // Тот же вход — тот же порядок: LazyColumn не переставляет строки между обновлениями.
        assertThat(second.map { it.task.id }).isEqualTo(first.map { it.task.id })
    }
}
