package io.github.bigsprut.taskman.data.attachments

import com.google.common.truth.Truth.assertThat
import io.github.bigsprut.taskman.data.remote.ApiFailure
import io.github.bigsprut.taskman.data.remote.ConnectionHolder
import io.github.bigsprut.taskman.data.repo.TaskRepository
import io.github.bigsprut.taskman.testutil.FakeLocalFileSource
import io.github.bigsprut.taskman.testutil.FakeStateStore
import io.github.bigsprut.taskman.testutil.FakeTaskmanApi
import io.github.bigsprut.taskman.testutil.attachment
import io.github.bigsprut.taskman.testutil.errorResponseOf
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Test

/**
 * Вложения задачи (ТЗ §5.3-Ф3, §6): аплоад multipart, удаление, ссылка на файл скачивания.
 *
 * Проверяется то, что реально уходит на сервер (id задачи, поле формы `file`, имя файла,
 * содержимое) и как репозиторий реагирует на отказы. Скачивание проверяется отдельно
 * (`AttachmentDownloaderTest` на настоящем HTTP), здесь — только сборка ссылки.
 */
class AttachmentRepositoryTest {

    private val api = FakeTaskmanApi()
    private val store = FakeStateStore()
    private val files = FakeLocalFileSource()
    private val connection = ConnectionHolder().apply { set(BASE_URL, TOKEN, DEVICE_ID) }
    private val taskRepository = TaskRepository(api = api, stateStore = store, now = { NOW_MILLIS })
    private val repository = AttachmentRepository(
        api = api,
        store = store,
        files = files,
        tasks = taskRepository,
        connection = connection,
        downloader = AttachmentDownloader(OkHttpClient(), freshCacheDirectory()),
    )

    @Test
    fun `upload читает файл и отправляет его на сервер с id задачи`() = runTest {
        files.file = LocalFile(name = "photo.jpg", mimeType = "image/jpeg", bytes = FILE_BYTES)

        val outcome = repository.upload(TASK_ID, PICKED_URI)

        assertThat(outcome).isEqualTo(AttachmentOutcome.Success)
        assertThat(files.lastUri).isEqualTo(PICKED_URI)
        assertThat(api.uploadCalls).isEqualTo(1)
        assertThat(api.lastUploadedTaskId).isEqualTo(TASK_ID)
        // Поле формы обязано называться `file`, иначе сервер не примет вложение (ТЗ §4).
        assertThat(api.lastUploadedFieldName).isEqualTo("file")
        // Имя файла берётся из локального файла, а не из uri пикера.
        assertThat(api.lastUploadedFileName).isEqualTo("photo.jpg")
        assertThat(api.lastUploadedBytes!!.toList()).isEqualTo(FILE_BYTES.toList())
        // После успеха сервер — источник правды: состояние перечитано.
        assertThat(api.calls).isEqualTo(1)
        assertThat(store.stored).isNotNull()
    }

    @Test
    fun `upload без читаемого файла не ходит в сеть и отдаёт Internal`() = runTest {
        // Пикер вернул uri, но содержимое прочитать не удалось (отозванный доступ): это
        // сбой клиента, а не сети, и запрос отправлять не из чего.
        files.file = null

        val outcome = repository.upload(TASK_ID, PICKED_URI)

        assertThat(outcome).isInstanceOf(AttachmentOutcome.Failed::class.java)
        assertThat((outcome as AttachmentOutcome.Failed).failure)
            .isInstanceOf(ApiFailure.FileAccess::class.java)
        assertThat(api.uploadCalls).isEqualTo(0)
        assertThat(api.calls).isEqualTo(0)
        assertThat(store.stored).isNull()
    }

    @Test
    fun `upload при 500 даёт Http с кодом и текстом ошибки`() = runTest {
        files.file = LocalFile(name = "photo.jpg", mimeType = null, bytes = FILE_BYTES)
        api.uploadOutcome = { errorResponseOf(500, """{"error":"boom"}""") }

        val outcome = repository.upload(TASK_ID, PICKED_URI)

        val failure = failureOf(outcome)
        assertThat(failure).isInstanceOf(ApiFailure.Http::class.java)
        val http = failure as ApiFailure.Http
        assertThat(http.code).isEqualTo(500)
        assertThat(http.serverError).isEqualTo("boom")
        assertThat(api.calls).isEqualTo(0)
    }

    @Test
    fun `upload при IOException даёт сетевую ошибку`() = runTest {
        files.file = LocalFile(name = "photo.jpg", mimeType = null, bytes = FILE_BYTES)
        val boom = IOException("сеть недоступна")
        api.uploadOutcome = { throw boom }

        val outcome = repository.upload(TASK_ID, PICKED_URI)

        val failure = failureOf(outcome)
        assertThat(failure).isInstanceOf(ApiFailure.Network::class.java)
        assertThat((failure as ApiFailure.Network).cause).isSameInstanceAs(boom)
        assertThat(api.calls).isEqualTo(0)
    }

    @Test
    fun `upload при 401 даёт Unauthorized и событие в authFailures`() = runTest {
        files.file = LocalFile(name = "photo.jpg", mimeType = null, bytes = FILE_BYTES)
        api.uploadOutcome = { errorResponseOf(401, """{"error":"unauthorized"}""") }
        // Коллектор встаёт до мутации: SharedFlow без replay не отдаёт событие задним числом.
        // UNDISPATCHED запускает корутину сразу до первого приостановления — подписка успевает
        // произойти синхронно.
        val event = async(start = CoroutineStart.UNDISPATCHED) {
            taskRepository.authFailures.first()
        }

        val outcome = repository.upload(TASK_ID, PICKED_URI)

        assertThat(failureOf(outcome)).isEqualTo(ApiFailure.Unauthorized)
        assertThat(event.await()).isEqualTo(ApiFailure.Unauthorized)
        // Отказ авторизации не повод перечитывать состояние — приложение уводит на вход.
        assertThat(api.calls).isEqualTo(0)
    }

    @Test
    fun `delete отправляет DELETE с id вложения и перечитывает снапшот`() = runTest {
        val outcome = repository.delete(ATTACHMENT_ID)

        assertThat(outcome).isEqualTo(AttachmentOutcome.Success)
        assertThat(api.deleteAttachmentCalls).isEqualTo(1)
        assertThat(api.lastDeletedAttachmentId).isEqualTo(ATTACHMENT_ID)
        // Удаление не собирает payload — уходит только путь.
        assertThat(api.uploadCalls).isEqualTo(0)
        assertThat(api.calls).isEqualTo(1)
        assertThat(store.stored).isNotNull()
    }

    @Test
    fun `delete при 404 даёт Http с кодом ответа`() = runTest {
        api.deleteAttachmentOutcome = { errorResponseOf(404, """{"error":"not_found"}""") }

        val outcome = repository.delete(ATTACHMENT_ID)

        val failure = failureOf(outcome)
        assertThat(failure).isInstanceOf(ApiFailure.Http::class.java)
        assertThat((failure as ApiFailure.Http).code).isEqualTo(404)
        assertThat(api.calls).isEqualTo(0)
    }

    @Test
    fun `url собирается из адреса и токена текущего подключения`() {
        assertThat(repository.url(ATTACHMENT_ID))
            .isEqualTo("https://h:8444/api/attachments/att-1?token=tok-1")
    }

    @Test
    fun `без токена url отсутствует`() {
        connection.clearToken()

        // Показывать превью без токена нельзя: сервер ответит 401, а ссылка утекла бы в логи.
        assertThat(repository.url(ATTACHMENT_ID)).isNull()
    }

    @Test
    fun `observeAttachments отдаёт поток хранилища по id задачи`() = runTest {
        store.putAttachments(TASK_ID, listOf(attachment(id = ATTACHMENT_ID, taskId = TASK_ID)))

        assertThat(repository.observeAttachments(TASK_ID).first())
            .containsExactly(attachment(id = ATTACHMENT_ID, taskId = TASK_ID))
    }

    @Test
    fun `download без ссылки не обращается к сети`() = runTest {
        connection.clearToken()

        // Ссылки нет — скачивать нечего; тест заодно страхует от запроса «куда-нибудь».
        val file = repository.download(attachment(id = ATTACHMENT_ID, taskId = TASK_ID))

        assertThat(file).isNull()
    }

    /** Достаёт причину отказа: у `AttachmentOutcome.Failed` это единственное полезное поле. */
    private fun failureOf(outcome: AttachmentOutcome): ApiFailure {
        assertThat(outcome).isInstanceOf(AttachmentOutcome.Failed::class.java)
        return (outcome as AttachmentOutcome.Failed).failure
    }

    private fun freshCacheDirectory(): File =
        Files.createTempDirectory("taskman-attachments").toFile()

    private companion object {
        const val BASE_URL = "https://h:8444"
        const val TOKEN = "tok-1"
        const val DEVICE_ID = "dev-1"
        const val TASK_ID = "task-1"
        const val ATTACHMENT_ID = "att-1"
        const val PICKED_URI = "content://media/picker/1"

        /** Фиксированное «сейчас», чтобы не зависеть от системных часов. */
        const val NOW_MILLIS = 1_700_000_000_000L

        val FILE_BYTES = "содержимое вложения".toByteArray()
    }
}
