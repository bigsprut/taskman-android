package io.github.bigsprut.taskman.data.repo

import com.google.common.truth.Truth.assertThat
import io.github.bigsprut.taskman.domain.TaskStatus
import io.github.bigsprut.taskman.testutil.taskEntity
import org.junit.Test

/**
 * Сборка тела `POST /api/tasks` из локальной сущности (ТЗ §4, §6).
 *
 * Сервер делает UPSERT: чего нет в payload, то перезапишется дефолтом, поэтому проверяется
 * полнота объекта. Отдельно фиксируются два правила: `status_date` не передаём (иначе
 * сдвигается история статусов) и `updated_at` берём из снапшота — это версия для CAS.
 */
class TaskPayloadTest {

    private val entity = taskEntity(
        id = "task-1",
        listId = "list-1",
        parentId = "parent-1",
        categoryId = "cat-1",
        title = "Купить хлеб",
        content = "две булки",
        status = "completed",
        source = "Иван",
        createdAt = 1_700_000_000.0,
        updatedAt = 1_700_000_500.0,
        completedAt = 1_700_000_400.0,
    )

    private val tagIds = listOf("tag-a", "tag-b")

    @Test
    fun `fromEntity переносит все поля сущности`() {
        val payload = TaskPayload.fromEntity(entity, tagIds)

        assertThat(payload.id).isEqualTo("task-1")
        assertThat(payload.listId).isEqualTo("list-1")
        assertThat(payload.parentId).isEqualTo("parent-1")
        assertThat(payload.categoryId).isEqualTo("cat-1")
        assertThat(payload.title).isEqualTo("Купить хлеб")
        assertThat(payload.content).isEqualTo("две булки")
        assertThat(payload.status).isEqualTo("completed")
        assertThat(payload.source).isEqualTo("Иван")
        assertThat(payload.tags).containsExactly("tag-a", "tag-b").inOrder()
        assertThat(payload.createdAt).isEqualTo(1_700_000_000.0)
        assertThat(payload.completedAt).isEqualTo(1_700_000_400.0)
        assertThat(payload.updatedAt).isEqualTo(1_700_000_500.0)
    }

    @Test
    fun `status_date не передаётся — историю не сдвигаем`() {
        // Без явной необходимости `status_date` сдвигал бы даты в истории статусов (§4),
        // поэтому он null и в полном payload, и в payload смены статуса.
        assertThat(TaskPayload.fromEntity(entity, tagIds).statusDate).isNull()
        assertThat(
            TaskPayload.fromEntity(entity, tagIds, status = "created", completedAt = null).statusDate,
        ).isNull()
        assertThat(TaskPayload.forStatusToggle(entity, tagIds, TaskStatus.CREATED).statusDate).isNull()
    }

    @Test
    fun `fromEntity с аргументами меняет только статус и дату завершения`() {
        val payload = TaskPayload.fromEntity(
            entity = entity,
            tagIds = tagIds,
            status = "created",
            completedAt = null,
        )

        assertThat(payload.status).isEqualTo("created")
        assertThat(payload.completedAt).isNull()
        // Всё остальное — из сущности: payload остаётся полным объектом.
        assertThat(payload.id).isEqualTo("task-1")
        assertThat(payload.updatedAt).isEqualTo(1_700_000_500.0)
        assertThat(payload.tags).containsExactly("tag-a", "tag-b").inOrder()
    }

    @Test
    fun `forStatusToggle completed ставит статус и сохраняет updated_at`() {
        val payload = TaskPayload.forStatusToggle(entity, tagIds, TaskStatus.COMPLETED)

        assertThat(payload.status).isEqualTo("completed")
        // completed_at считает сервер по истории; клиент только просит сменить статус.
        assertThat(payload.completedAt).isNull()
        // updated_at — ожидаемая версия (CAS): расхождение на сервере даст 409, а не перезапись.
        assertThat(payload.updatedAt).isEqualTo(entity.updatedAt)
        assertThat(payload.statusDate).isNull()
    }

    @Test
    fun `forStatusToggle created возвращает задачу в работу`() {
        val payload = TaskPayload.forStatusToggle(entity, tagIds, TaskStatus.CREATED)

        assertThat(payload.status).isEqualTo("created")
        assertThat(payload.completedAt).isNull()
        assertThat(payload.updatedAt).isEqualTo(entity.updatedAt)
        assertThat(payload.id).isEqualTo("task-1")
    }
}
