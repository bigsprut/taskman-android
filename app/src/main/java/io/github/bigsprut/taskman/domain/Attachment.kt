package io.github.bigsprut.taskman.domain

/**
 * Вложение задачи (метаданные). Сам файл живёт на сервере, клиент его не кэширует —
 * превью и скачивание идут по `GET /api/attachments/{id}?token=…` (ТЗ §4: токен только
 * query-параметром, заголовок этот эндпоинт не читает).
 *
 * @property mime MIME-тип с сервера (`image/jpeg`, `application/pdf`, …), может отсутствовать.
 * @property size размер в байтах.
 * @property createdAt unix-секунды.
 */
data class Attachment(
    val id: String,
    val taskId: String,
    val origName: String,
    val mime: String?,
    val size: Long,
    val createdAt: Double,
) {
    /** Картинка: показываем превью через Coil (ТЗ §5.3-Ф3). */
    val isImage: Boolean get() = mime?.startsWith("image/") == true
}
