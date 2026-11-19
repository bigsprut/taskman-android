package io.github.bigsprut.taskman.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Разбор статуса из ответа сервера (ТЗ §4, §6).
 */
class TaskStatusTest {

    @Test
    fun `известные значения разбираются в свои статусы`() {
        assertThat(TaskStatus.fromWireValue("created")).isEqualTo(TaskStatus.CREATED)
        assertThat(TaskStatus.fromWireValue("inprogress")).isEqualTo(TaskStatus.IN_PROGRESS)
        assertThat(TaskStatus.fromWireValue("completed")).isEqualTo(TaskStatus.COMPLETED)
        assertThat(TaskStatus.fromWireValue("canceled")).isEqualTo(TaskStatus.CANCELED)
    }

    @Test
    fun `легаси needsAction трактуется как created`() {
        assertThat(TaskStatus.fromWireValue("needsAction")).isEqualTo(TaskStatus.CREATED)
        assertThat(TaskStatus.fromWireValue("needs_action")).isEqualTo(TaskStatus.CREATED)
    }

    @Test
    fun `null и пустая строка дают created`() {
        assertThat(TaskStatus.fromWireValue(null)).isEqualTo(TaskStatus.CREATED)
        assertThat(TaskStatus.fromWireValue("")).isEqualTo(TaskStatus.CREATED)
    }

    @Test
    fun `неизвестный статус не роняет разбор`() {
        assertThat(TaskStatus.fromWireValue("archived")).isEqualTo(TaskStatus.CREATED)
    }

    @Test
    fun `sortIndex задаёт порядок групп`() {
        assertThat(TaskStatus.IN_PROGRESS.sortIndex).isEqualTo(0)
        assertThat(TaskStatus.CREATED.sortIndex).isEqualTo(1)
        assertThat(TaskStatus.COMPLETED.sortIndex).isEqualTo(2)
        assertThat(TaskStatus.CANCELED.sortIndex).isEqualTo(3)
    }
}
