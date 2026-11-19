package io.github.bigsprut.taskman.domain

import com.google.common.truth.Truth.assertThat
import io.github.bigsprut.taskman.testutil.task
import org.junit.Test

/**
 * Порядок задач в списке (ТЗ §5.3, Ф0).
 */
class StatusOrderTest {

    @Test
    fun `группы идут от активных к отменённым`() {
        // Даты намеренно «неправильные»: группа важнее свежести.
        val sorted = StatusOrder.sort(
            listOf(
                task("canceled", TaskStatus.CANCELED, statusDateTs = 500.0),
                task("completed", TaskStatus.COMPLETED, statusDateTs = 400.0),
                task("created", TaskStatus.CREATED, statusDateTs = 300.0),
                task("inprogress", TaskStatus.IN_PROGRESS, statusDateTs = 100.0),
            ),
        )

        assertThat(sorted.map { it.id })
            .containsExactly("inprogress", "created", "completed", "canceled")
            .inOrder()
    }

    @Test
    fun `внутри группы свежие даты статуса идут первыми`() {
        val sorted = StatusOrder.sort(
            listOf(
                task("old", TaskStatus.CREATED, statusDateTs = 100.0),
                task("new", TaskStatus.CREATED, statusDateTs = 300.0),
                task("middle", TaskStatus.CREATED, statusDateTs = 200.0),
            ),
        )

        assertThat(sorted.map { it.id })
            .containsExactly("new", "middle", "old")
            .inOrder()
    }

    @Test
    fun `при равенстве статуса и даты порядок решает id`() {
        val sorted = StatusOrder.sort(
            listOf(
                task("b", TaskStatus.COMPLETED, statusDateTs = 100.0),
                task("a", TaskStatus.COMPLETED, statusDateTs = 100.0),
                task("c", TaskStatus.COMPLETED, statusDateTs = 100.0),
            ),
        )

        assertThat(sorted.map { it.id })
            .containsExactly("a", "b", "c")
            .inOrder()
    }

    @Test
    fun `isSubtask зависит от parentId`() {
        assertThat(task("child", TaskStatus.CREATED, parentId = "parent").isSubtask).isTrue()
        assertThat(task("root", TaskStatus.CREATED, parentId = null).isSubtask).isFalse()
    }
}
