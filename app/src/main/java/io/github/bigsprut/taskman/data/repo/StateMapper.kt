package io.github.bigsprut.taskman.data.repo

import io.github.bigsprut.taskman.data.local.entity.AttachmentEntity
import io.github.bigsprut.taskman.data.local.entity.CategoryEntity
import io.github.bigsprut.taskman.data.local.entity.EmployeeEntity
import io.github.bigsprut.taskman.data.local.entity.TagEntity
import io.github.bigsprut.taskman.data.local.entity.TaskEntity
import io.github.bigsprut.taskman.data.local.entity.TaskListEntity
import io.github.bigsprut.taskman.data.local.entity.TaskStatusHistoryEntity
import io.github.bigsprut.taskman.data.local.entity.TaskTagEntity
import io.github.bigsprut.taskman.data.remote.dto.AttachmentDto
import io.github.bigsprut.taskman.data.remote.dto.StateResponse
import io.github.bigsprut.taskman.data.remote.dto.TaskDto

/**
 * Маппинг ответа `/api/state` (ТЗ §6) в локальные сущности.
 *
 * Всё, что контракт обещает «странным», собрано здесь:
 * - `deleted` — число 0/1, а не boolean;
 * - времена — unix-float секунды;
 * - `tags` — список id, разворачивается в отдельную таблицу связей;
 * - дата статуса не приходит полем: берётся последняя запись истории.
 */
object StateMapper {

    fun toSnapshot(response: StateResponse): StateSnapshot = StateSnapshot(
        lists = response.lists.map { TaskListEntity(it.id, it.title, it.position) },
        categories = response.categories.map { CategoryEntity(it.id, it.title, it.color, it.position) },
        employees = response.employees.map { EmployeeEntity(it.id, it.name) },
        tags = response.tags.map { TagEntity(it.id, it.title, it.color, it.position) },
        tasks = response.tasks.map { it.toEntity() },
        attachments = response.tasks.flatMap { task -> task.attachments.map { it.toEntity(task.id) } },
        taskTags = response.tasks.flatMap { task -> task.tags.map { TaskTagEntity(taskId = task.id, tagId = it) } },
        history = response.tasks.flatMap { task ->
            task.history.map { TaskStatusHistoryEntity(taskId = task.id, status = it.status, ts = it.ts) }
        },
        defaultCategoryId = response.settings.defaultCategoryId,
    )

    private fun TaskDto.toEntity(): TaskEntity = TaskEntity(
        id = id,
        listId = listId,
        parentId = parentId,
        categoryId = categoryId,
        title = title,
        content = content,
        status = status,
        source = source,
        createdAt = createdAt,
        updatedAt = updatedAt,
        completedAt = completedAt,
        statusDateTs = statusDateTs(this),
        deleted = deleted != 0,
    )

    private fun AttachmentDto.toEntity(taskId: String): AttachmentEntity = AttachmentEntity(
        id = id,
        // В контракте task_id есть; если сервер его не прислал — вложение принадлежит задаче,
        // в которой оно пришло.
        taskId = this.taskId ?: taskId,
        origName = origName,
        mime = mime,
        size = size,
        createdAt = createdAt ?: 0.0,
        deleted = deleted != 0,
    )

    /**
     * Дата последней смены статуса (unix-секунды).
     *
     * Приоритет: последняя запись истории статусов → дата закрытия → дата обновления →
     * дата создания. Так задача с историей сортируется по факту смены статуса, а задача
     * без истории (например, созданная сразу выполненной) — по ближайшей осмысленной дате.
     */
    fun statusDateTs(task: TaskDto): Double =
        task.history.maxOfOrNull { it.ts }
            ?: task.completedAt
            ?: task.updatedAt
            ?: task.createdAt
            ?: 0.0
}
