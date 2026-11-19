package io.github.bigsprut.taskman.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Тело `POST /api/tasks` — **полный** объект задачи (ТЗ §4, §6).
 *
 * Сервер делает UPSERT: что не передано — перезапишется значением по умолчанию, поэтому
 * payload собирается из полной локальной сущности ([io.github.bigsprut.taskman.data.repo.TaskPayload]).
 * `tags` — список id, заменяет набор целиком.
 *
 * @property updatedAt ожидаемая версия задачи (CAS). `null` — новая задача.
 * @property statusDate явная дата смены статуса; без необходимости не передаём (§4: сдвигает даты).
 */
@Serializable
data class TaskInDto(
    val id: String? = null,
    @SerialName("list_id") val listId: String = "",
    @SerialName("parent_id") val parentId: String? = null,
    @SerialName("category_id") val categoryId: String? = null,
    val title: String = "",
    val content: String = "",
    val status: String = "created",
    val source: String? = null,
    val tags: List<String> = emptyList(),
    @SerialName("created_at") val createdAt: Double? = null,
    @SerialName("completed_at") val completedAt: Double? = null,
    @SerialName("status_date") val statusDate: Double? = null,
    @SerialName("updated_at") val updatedAt: Double? = null,
)

/** Сохранённая задача в ответе upsert. */
@Serializable
data class SavedTaskDto(
    val id: String,
    @SerialName("updated_at") val updatedAt: Double? = null,
)

/** Ответ `POST /api/tasks`. */
@Serializable
data class UpsertTasksResponse(
    val saved: List<SavedTaskDto> = emptyList(),
    @SerialName("server_time") val serverTime: Double? = null,
)

/** Ответ `DELETE /api/tasks/{id}` (soft-delete поддерева, §4). */
@Serializable
data class DeleteTaskResponse(
    val deleted: String = "",
)
