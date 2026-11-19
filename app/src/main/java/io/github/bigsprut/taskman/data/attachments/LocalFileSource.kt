package io.github.bigsprut.taskman.data.attachments

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import android.provider.OpenableColumns
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext


/**
 * Открытый файл: метаданные + ленивый поток байт.
 *
 * Поток, а не `ByteArray` (ответ Q16 из `ANSWERS-F3.md`): большой файл не обязан целиком
 * помещаться в память — OkHttp пишет его в сокет частями сразу.
 */
data class LocalFileHandle(
    val name: String,
    val mimeType: String?,
    val size: Long?,
    val openStream: () -> java.io.InputStream,
)

/** Источник локальных файлов: интерфейс ради тестов, реализация — [ContentResolverFileSource]. */
interface LocalFileSource {
    suspend fun open(uri: String): LocalFileHandle?
}

/** Файл целиком в памяти — удобно для подставного источника в тестах. */
data class LocalFile(
    val name: String,
    val mimeType: String?,
    val bytes: ByteArray,
) {
    fun handle(): LocalFileHandle = LocalFileHandle(
        name = name,
        mimeType = mimeType,
        size = bytes.size.toLong(),
        openStream = { bytes.inputStream() },
    )
}

/** Чтение выбранного файла через `ContentResolver` (`content://` и `file://`). */
class ContentResolverFileSource(private val context: Context) : LocalFileSource {

    override suspend fun open(uri: String): LocalFileHandle? = withContext(Dispatchers.IO) {
        runCatching {
            val parsed = uri.toUri()
            val resolver = context.contentResolver
            val mimeType = resolver.getType(parsed)
            val name = displayName(parsed) ?: parsed.lastPathSegment ?: DEFAULT_NAME
            // Проверяем, что поток открывается: иначе сервер получит пустое вложение.
            resolver.openInputStream(parsed)?.close() ?: return@runCatching null
            LocalFileHandle(
                name = name,
                mimeType = mimeType,
                size = declaredSize(parsed),
                openStream = {
                    resolver.openInputStream(parsed)
                        ?: throw java.io.IOException("content provider отказал: $uri")
                },
            )
        }.getOrNull()
    }

    /** Размер из `OpenableColumns.SIZE` нужен для `Content-Length` в multipart. */
    private fun declaredSize(uri: Uri): Long? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (index >= 0 && !cursor.isNull(index)) cursor.getLong(index) else null
                } else {
                    null
                }
            }
    }.getOrNull()

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
