package io.github.bigsprut.taskman.data.attachments

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import android.provider.OpenableColumns
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Локальный файл, выбранный пользователем (photo picker / «Поделиться» / открытие документа).
 * `uri` — строкой, а не `android.net.Uri`, чтобы репозиторий оставался тестируемым на JVM.
 */
data class LocalFile(
    val name: String,
    val mimeType: String?,
    val bytes: ByteArray,
)

/** Источник локальных файлов: интерфейс ради тестов, реализация — [ContentResolverFileSource]. */
interface LocalFileSource {
    suspend fun read(uri: String): LocalFile?
}

/** Чтение выбранного файла через `ContentResolver` (`content://` и `file://`). */
class ContentResolverFileSource(private val context: Context) : LocalFileSource {

    override suspend fun read(uri: String): LocalFile? = withContext(Dispatchers.IO) {
        runCatching {
            val parsed = uri.toUri()
            val resolver = context.contentResolver
            val mimeType = resolver.getType(parsed)
            val name = displayName(parsed) ?: parsed.lastPathSegment ?: DEFAULT_NAME
            val bytes = resolver.openInputStream(parsed)?.use { it.readBytes() }
                ?: return@runCatching null
            LocalFile(name = name, mimeType = mimeType, bytes = bytes)
        }.getOrNull()
    }

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) cursor.getString(index) else null
                } else {
                    null
                }
            }
    }.getOrNull()

    private companion object {
        const val DEFAULT_NAME = "attachment"
    }
}

/**
 * Безопасное имя файла для записи в кэш: берём только базовое имя, чтобы имя из другого
 * приложения не могло вывести запись за пределы каталога вложений.
 */
fun safeAttachmentFileName(raw: String): String {
    val base = File(raw).name
    val cleaned = base.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_").trim()
    return cleaned.ifBlank { "attachment" }
}
