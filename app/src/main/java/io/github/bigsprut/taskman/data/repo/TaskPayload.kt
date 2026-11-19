package io.github.bigsprut.taskman.data.repo

import io.github.bigsprut.taskman.data.local.entity.TaskEntity
import io.github.bigsprut.taskman.data.remote.dto.TaskInDto
import io.github.bigsprut.taskman.domain.TaskDraft
import io.github.bigsprut.taskman.domain.TaskStatus

/**
 * Сборка тела `POST /api/tasks` из локальной модели.
 *
 * Два правила, которые нельзя нарушать (ТЗ §4):
 * 1. payload — **полный** объект: сервер перезапишет отсутствующие поля дефолтами, поэтому
 *    берём значения из Room-записи (или черновика редактора), а не из модели строки списка;
 * 2. `updated_at` — версия из последнего `/api/state` (CAS). Для новой задачи `id = null`
 *    и `updated_at = null`.
 *
 * `status_date` передаётся только когда пользователь явно выбрал дату статуса («задним
 * числом», §5.3-Ф2): без необходимости он сдвигает даты в истории (§4).
 */
object TaskPayload {

    /**
     * Полный payload существующей задачи с возможными правками полей.
     *
     * `completed_at` передаётся как есть (`null` при смене статуса): по эталонной фикстуре
     * сервер сам проставляет дату завершения при переходе в `completed` и очищает при выходе.
     */
    fun fromEntity(
        entity: TaskEntity,
        tagIds: List<String>,
        status: String = entity.status,
        completedAt: Double? = entity.completedAt,
        title: String = entity.title,
        content: String = entity.content,
    ): TaskInDto = TaskInDto(
        id = entity.id,
        listId = entity.listId,
        parentId = entity.parentId,
        categoryId = entity.categoryId,
        title = title,
        content = content,
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

    /** Payload редактора: все поля черновика, `status_date` — только если выбрана дата. */
    fun fromDraft(draft: TaskDraft): TaskInDto = TaskInDto(
        id = draft.id,
        listId = draft.listId,
        parentId = draft.parentId,
        categoryId = draft.categoryId,
        title = draft.title,
        content = draft.content,
        status = draft.status.wireValue,
        source = draft.source,
        tags = draft.tagIds.sorted(),
        createdAt = draft.createdAt,
        completedAt = draft.completedAt,
        statusDate = draft.statusDateTs,
        updatedAt = draft.updatedAt,
    )

    /**
     * Новая подзадача: `id = null` — сервер выдаст id; список и родитель известны из контекста.
     */
    fun newSubtask(listId: String, parentId: String, title: String): TaskInDto = TaskInDto(
        id = null,
        listId = listId,
        parentId = parentId,
        title = title,
        status = TaskStatus.CREATED.wireValue,
    )
}
