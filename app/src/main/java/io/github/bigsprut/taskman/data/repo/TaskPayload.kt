package io.github.bigsprut.taskman.data.repo

import io.github.bigsprut.taskman.data.local.entity.TaskEntity
import io.github.bigsprut.taskman.data.remote.dto.TaskInDto
import io.github.bigsprut.taskman.domain.TaskStatus

/**
 * Сборка тела `POST /api/tasks` из локальной сущности.
 *
 * Два правила, которые нельзя нарушать (ТЗ §4):
 * 1. payload — **полный** объект: сервер перезапишет отсутствующие поля дефолтами, поэтому
 *    берём значения из Room-записи, а не из модели строки списка (там нет половины полей);
 * 2. `updated_at` — версия из последнего `/api/state` (CAS). Для новой задачи `id = null`
 *    и `updated_at = null`.
 *
 * `status_date` не передаётся: без необходимости он сдвигает даты в истории статусов (§4).
 */
object TaskPayload {

    /**
     * Полный payload существующей задачи с возможной сменой статуса.
     *
     * `completed_at` передаётся `null` при смене статуса: по эталонной фикстуре сервер сам
     * проставляет дату завершения при переходе в `completed` и очищает при выходе из него.
     */
    fun fromEntity(
        entity: TaskEntity,
        tagIds: List<String>,
        status: String = entity.status,
        completedAt: Double? = entity.completedAt,
    ): TaskInDto = TaskInDto(
        id = entity.id,
        listId = entity.listId,
        parentId = entity.parentId,
        categoryId = entity.categoryId,
        title = entity.title,
        content = entity.content,
        status = status,
        source = entity.source,
        tags = tagIds,
        createdAt = entity.createdAt,
        completedAt = completedAt,
        statusDate = null,
        updatedAt = entity.updatedAt,
    )

    /**
     * Payload для чекбокса «Выполнена ↔ Создана» (§5.3-Ф1).
     *
     * Идём через UPSERT, а не через `/toggle`: только upsert несёт `updated_at` и,
     * следовательно, позволяет заметить конфликт (409) вместо молчаливой перезаписи (§4).
     */
    fun forStatusToggle(
        entity: TaskEntity,
        tagIds: List<String>,
        target: TaskStatus,
    ): TaskInDto = fromEntity(
        entity = entity,
        tagIds = tagIds,
        status = target.wireValue,
        completedAt = null,
    )
}
