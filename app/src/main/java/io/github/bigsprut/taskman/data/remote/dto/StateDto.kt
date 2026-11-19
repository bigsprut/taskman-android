package io.github.bigsprut.taskman.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Ответ `GET /api/state` (контракт — ТЗ §6).
 *
 * Все поля имеют значения по умолчанию: сервер может не прислать пустую секцию, и это не
 * должно ронять список. Неизвестные поля игнорируются (см. [io.github.bigsprut.taskman.data.remote.TaskmanJson]).
 *
 * Времена — unix-float СЕКУНДЫ (§4).
 */
@Serializable
data class StateResponse(
    val lists: List<TaskListDto> = emptyList(),
    val categories: List<CategoryDto> = emptyList(),
    val employees: List<EmployeeDto> = emptyList(),
    val tags: List<TagDto> = emptyList(),
    val tasks: List<TaskDto> = emptyList(),
    val settings: ServerSettingsDto = ServerSettingsDto(),
    @SerialName("server_time") val serverTime: Double? = null,
)

@Serializable
data class TaskListDto(
    val id: String,
    val title: String = "",
    val position: Int = 0,
)

@Serializable
data class CategoryDto(
    val id: String,
    val title: String = "",
    val color: String? = null,
    val position: Int = 0,
)

@Serializable
data class TagDto(
    val id: String,
    val title: String = "",
    val color: String? = null,
    val position: Int = 0,
)

@Serializable
data class EmployeeDto(
    val id: String,
    val name: String = "",
)

@Serializable
data class ServerSettingsDto(
    @SerialName("default_category") val defaultCategoryId: String? = null,
)

@Serializable
data class TaskDto(
    val id: String,
    @SerialName("list_id") val listId: String = "",
    @SerialName("parent_id") val parentId: String? = null,
    @SerialName("category_id") val categoryId: String? = null,
    val title: String = "",
    val content: String = "",
    val status: String = "",
    val source: String? = null,
    @SerialName("created_at") val createdAt: Double? = null,
    @SerialName("updated_at") val updatedAt: Double? = null,
    @SerialName("completed_at") val completedAt: Double? = null,
    /** На сервере SQLite-флаг: 0/1, а не boolean. */
    val deleted: Int = 0,
    val tags: List<String> = emptyList(),
    val attachments: List<AttachmentDto> = emptyList(),
    val history: List<StatusHistoryDto> = emptyList(),
)

@Serializable
data class AttachmentDto(
    val id: String,
    @SerialName("task_id") val taskId: String? = null,
    @SerialName("orig_name") val origName: String = "",
    val mime: String? = null,
    val size: Long = 0,
    @SerialName("created_at") val createdAt: Double? = null,
    val deleted: Int = 0,
)

@Serializable
data class StatusHistoryDto(
    val status: String = "",
    val ts: Double = 0.0,
)

/**
 * Тело ошибки API: `{"error": "device"}` для 403 TOFU и
 * `{"error":"conflict","actual_updated_at":…}` для 409 CAS (ТЗ §4).
 */
@Serializable
data class ApiErrorDto(
    val error: String? = null,
    @SerialName("actual_updated_at") val actualUpdatedAt: Double? = null,
)
