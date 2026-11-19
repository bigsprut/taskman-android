package io.github.bigsprut.taskman.data.remote

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Ссылка на файл вложения.
 *
 * ТЗ §4: `GET /api/attachments/{id}` читает токен **только** query-параметром `?token=`,
 * заголовок этот эндпоинт не смотрит. Поэтому ссылка собирается целиком (с путём и токеном)
 * и передаётся в Coil/скачивание как абсолютный URL — сетевой интерсептор с заголовками к ней
 * не применяется.
 */
object AttachmentUrls {

    /**
     * @param baseUrl адрес инстанса из настроек (может содержать префикс reverse-proxy).
     * @param token токен доступа; попадает в query, а значит в URL — логировать такие ссылки нельзя.
     * @return абсолютный URL или `null`, если адрес/токен не заданы или адрес не разбирается.
     */
    fun build(baseUrl: String?, token: String?, attachmentId: String): String? {
        if (baseUrl.isNullOrBlank() || token.isNullOrBlank() || attachmentId.isBlank()) return null
        val base = baseUrl.trim().trimEnd('/').toHttpUrlOrNull() ?: return null
        return base.newBuilder()
            .addPathSegments("api/attachments")
            .addPathSegment(attachmentId)
            .addQueryParameter("token", token)
            .build()
            .toString()
    }
}
