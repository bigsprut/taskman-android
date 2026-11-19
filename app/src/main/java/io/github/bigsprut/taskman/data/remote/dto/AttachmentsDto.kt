package io.github.bigsprut.taskman.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Ответ `POST /api/tasks/{id}/attachments` (§6): `{id, name, size}`. */
@Serializable
data class AttachmentUploadResponse(
    val id: String = "",
    val name: String = "",
    val size: Long = 0,
)

/** Ответ `DELETE /api/attachments/{id}`: `{deleted: id}`. */
@Serializable
data class DeleteAttachmentResponse(
    val deleted: String = "",
)

/** Тело `POST /api/employees` и `PATCH /api/employees/{id}` — `{name}`. */
@Serializable
data class EmployeeInDto(
    val name: String,
)

/** Ответ справочника сотрудников: `{id, name}`. */
@Serializable
data class EmployeeResponse(
    val id: String = "",
    val name: String = "",
)

/** Ответ `DELETE /api/employees/{id}`. */
@Serializable
data class DeleteEmployeeResponse(
    val deleted: String = "",
)
