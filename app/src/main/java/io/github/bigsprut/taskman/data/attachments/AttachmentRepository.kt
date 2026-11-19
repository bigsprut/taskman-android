package io.github.bigsprut.taskman.data.attachments

import io.github.bigsprut.taskman.data.remote.ApiFailure
import io.github.bigsprut.taskman.data.remote.AttachmentUrls
import io.github.bigsprut.taskman.data.remote.ConnectionProvider
import io.github.bigsprut.taskman.data.remote.TaskmanApi
import io.github.bigsprut.taskman.data.repo.TaskRepository
import io.github.bigsprut.taskman.domain.Attachment
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.BufferedSink
import okio.source

/** Итог операции с вложением. 409 у вложений не бывает, поэтому исходов меньше, чем у задач. */
sealed interface AttachmentOutcome {
    data object Success : AttachmentOutcome
    class Failed(val failure: ApiFailure) : AttachmentOutcome
}

/** Локальное хранилище метаданных вложений (Room). */
interface AttachmentStore {
    fun observeAttachments(taskId: String): Flow<List<Attachment>>
    suspend fun findAttachment(attachmentId: String): Attachment?
}

/**
 * Вложения задачи (Ф3, §5.3): аплоад, удаление, превью/скачивание.
 *
 * Особенности контракта (§4, §6):
 * - аплоад — multipart, поле формы обязано называться `file`;
 * - скачивание — токен **только** query-параметром (см. [AttachmentUrls]), поэтому идёт
 *   отдельным клиентом без интерсептора с заголовками;
 * - после успешной мутации состояние перечитывается с сервера, чтобы id/размеры пришли
 *   от него, а не из локальной догадки.
 */
class AttachmentRepository(
    private val api: TaskmanApi,
    private val store: AttachmentStore,
    private val files: LocalFileSource,
    private val tasks: TaskRepository,
    private val connection: ConnectionProvider,
    private val downloader: AttachmentDownloader,
) {

    fun observeAttachments(taskId: String): Flow<List<Attachment>> = store.observeAttachments(taskId)

    /** Абсолютный URL с токеном для Coil/скачивания; `null`, если подключение не настроено. */
    fun url(attachmentId: String): String? = AttachmentUrls.build(
        baseUrl = connection.baseUrl(),
        token = connection.token(),
        attachmentId = attachmentId,
    )

    suspend fun upload(taskId: String, uri: String): AttachmentOutcome {
        val file = files.open(uri)
            ?: return AttachmentOutcome.Failed(ApiFailure.FileAccess)

        // Потоковое тело: файл пишется в сокет частями, память не зависит от размера (§Q16).
        val requestBody = StreamRequestBody(
            contentType = file.mimeType?.toMediaTypeOrNull(),
            contentLength = file.size,
            openStream = file.openStream,
        )
        // Поле формы — именно `file` (ТЗ §4), иначе сервер не примет вложение.
        val part = MultipartBody.Part.createFormData("file", file.name, requestBody)

        return execute { api.uploadAttachment(taskId, part) }
    }

    suspend fun delete(attachmentId: String): AttachmentOutcome =
        execute { api.deleteAttachment(attachmentId) }

    /** Скачивает файл во временный каталог — для открытия внешним просмотрщиком и шаринга. */
    suspend fun download(attachment: Attachment): File? {
        val url = url(attachment.id) ?: return null
        return downloader.download(url = url, fileName = attachment.origName)
    }

    private suspend fun execute(call: suspend () -> retrofit2.Response<*>): AttachmentOutcome = try {
        val response = call()
        if (response.isSuccessful) {
            tasks.refresh()
            AttachmentOutcome.Success
        } else {
            val failure = ApiFailure.fromHttpResponse(response.code(), response.errorBody())
            if (failure is ApiFailure.Unauthorized || failure is ApiFailure.DeviceNotApproved) {
                tasks.reportAuthFailure(failure)
            }
            AttachmentOutcome.Failed(failure)
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (io: IOException) {
        AttachmentOutcome.Failed(ApiFailure.Network(io))
    } catch (error: Exception) {
        AttachmentOutcome.Failed(ApiFailure.Internal(error))
    }
}

/**
 * Скачивание файлов вложений в кэш приложения.
 *
 * Токен уже в URL (query), поэтому клиент — без `ConnectionInterceptor`: он бы добавил
 * заголовки и подменил хост, а ссылка на файл абсолютная.
 */
class AttachmentDownloader(
    private val client: OkHttpClient,
    private val cacheDirectory: File,
) {

    suspend fun download(url: String, fileName: String): File? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body
                val directory = File(cacheDirectory, CACHE_SUBDIRECTORY).apply { mkdirs() }
                val target = File(directory, safeAttachmentFileName(fileName))
                body.byteStream().use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                target
            }
        }.getOrNull()
    }

    private companion object {
        const val CACHE_SUBDIRECTORY = "attachments"
    }
}
/**
 * Тело запроса, которое читает файл потоком (ответ на Q16, техдолг Ф3/Ф4).
 *
 * `contentLength` может быть неизвестен (`-1`) — тогда OkHttp отправит `Transfer-Encoding:
 * chunked`; это допустимо для multipart.
 */
private class StreamRequestBody(
    private val contentType: okhttp3.MediaType?,
    private val contentLength: Long?,
    private val openStream: () -> java.io.InputStream,
) : okhttp3.RequestBody() {

    override fun contentType(): okhttp3.MediaType? = contentType

    override fun contentLength(): Long = contentLength ?: -1L

    override fun writeTo(sink: BufferedSink) {
        openStream().use { input -> sink.writeAll(input.source()) }
    }
}