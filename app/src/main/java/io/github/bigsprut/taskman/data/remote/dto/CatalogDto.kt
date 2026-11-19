package io.github.bigsprut.taskman.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Тело `POST /api/tags` и `PATCH /api/tags/{id}` — `{title, color}`.
 * Цвет — строка `#rrggbb` (так его отдаёт сервер, см. фикстуру).
 */
@Serializable
data class TagInDto(
    val title: String? = null,
    val color: String? = null,
)

/** Ответ сервера по тегу: `{id, title, color}`. */
@Serializable
data class TagResponse(
    val id: String = "",
    val title: String = "",
    val color: String? = null,
)

/** Тело `POST /api/categories` и `PATCH /api/categories/{id}`. */
@Serializable
data class CategoryInDto(
    val title: String? = null,
    val color: String? = null,
)

/** Ответ по категории: `{id, title, color}`. */
@Serializable
data class CategoryResponse(
    val id: String = "",
    val title: String = "",
    val color: String? = null,
)

/** Ответ `DELETE /api/tags/{id}` и `DELETE /api/categories/{id}`: `{deleted: id}`. */
@Serializable
data class DeleteCatalogResponse(
    val deleted: String = "",
)

/** Тело `PATCH /api/settings`: `{default_category: id|null}` (см. §6). */
@Serializable
data class SettingsInDto(
    @SerialName("default_category") val defaultCategory: String? = null,
)

/** Ответ `PATCH /api/settings`. */
@Serializable
data class SettingsResponse(
    @SerialName("default_category") val defaultCategory: String? = null,
    @SerialName("server_time") val serverTime: Double = 0.0,
)
