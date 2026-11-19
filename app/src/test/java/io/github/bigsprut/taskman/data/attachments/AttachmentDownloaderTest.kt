package io.github.bigsprut.taskman.data.attachments

import com.google.common.truth.Truth.assertThat
import io.github.bigsprut.taskman.net.HttpTestServer
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Test

/**
 * Скачивание файла вложения в кэш приложения (ТЗ §4, §5.3-Ф3).
 *
 * Сеть — JDK `com.sun.net.httpserver` на loopback и эфемерном порту: в интернет тесты не ходят
 * и фиксированные порты не занимают. Проверяется то, что важно пользователю и безопасности:
 * файл появляется в каталоге вложений с телом ответа, отказ сервера и недоступная сеть дают
 * `null` без мусора в кэше, а имя из чужого приложения не выводит запись за каталог.
 */
class AttachmentDownloaderTest {

    private val cacheDirectory: File = Files.createTempDirectory("taskman-downloads").toFile()

    // Таймауты короче штатных: проверка «сети нет» не должна держать тест десять секунд.
    private val downloader = AttachmentDownloader(
        client = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build(),
        cacheDirectory = cacheDirectory,
    )

    @Test
    fun `успешное скачивание кладёт файл в каталог вложений с телом ответа`() = runTest {
        HttpTestServer().use { server ->
            server.respond(200, BODY)

            val file = downloader.download(url = attachmentUrl(server), fileName = "photo.jpg")

            assertThat(file).isNotNull()
            val saved = file!!.canonicalFile
            assertThat(saved.name).isEqualTo("photo.jpg")
            assertThat(saved.parentFile).isEqualTo(attachmentsDirectory().canonicalFile)
            assertThat(saved.readText()).isEqualTo(BODY)
            // Запрос ушёл ровно на путь файла; токен едет query-параметром, заголовка `Token` нет.
            val request = server.requests.single()
            assertThat(request.path).isEqualTo("/api/attachments/att-1")
            assertThat(request.headers["Token"]).isNull()
        }
    }

    @Test
    fun `404 не создаёт файл`() = runTest {
        HttpTestServer().use { server ->
            server.respond(404, """{"error":"not_found"}""")

            val file = downloader.download(url = attachmentUrl(server), fileName = "photo.jpg")

            assertThat(file).isNull()
            // Пустой файл в кэше выглядел бы как «скачано» и открывался бы пустым.
            assertThat(savedFiles()).isEmpty()
        }
    }

    @Test
    fun `имя с переходами вверх не выводит файл за каталог вложений`() = runTest {
        HttpTestServer().use { server ->
            server.respond(200, BODY)

            val file = downloader.download(
                url = attachmentUrl(server),
                fileName = "../../evil.txt",
            )

            assertThat(file).isNotNull()
            val saved = file!!.canonicalFile
            val directory = attachmentsDirectory().canonicalFile
            // Имя схлопнулось до базового, и запись осталась внутри каталога вложений.
            assertThat(saved.name).isEqualTo("evil.txt")
            assertThat(saved.parentFile).isEqualTo(directory)
            // Ни в кэше, ни на уровень выше (куда ведёт `../../` от каталога вложений) файла нет.
            assertThat(File(cacheDirectory, "evil.txt").exists()).isFalse()
            assertThat(File(cacheDirectory.parentFile, "evil.txt").exists()).isFalse()
        }
    }

    @Test
    fun `недоступная сеть даёт null и не создаёт файл`() = runTest {
        // Порт закрытого сервера свободен: соединение к нему отвергается, как при отсутствии сети.
        val closedPort = HttpTestServer().use { it.port }

        val file = downloader.download(
            url = "http://127.0.0.1:$closedPort/api/attachments/att-1?token=tok",
            fileName = "photo.jpg",
        )

        assertThat(file).isNull()
        assertThat(savedFiles()).isEmpty()
    }

    private fun attachmentUrl(server: HttpTestServer): String =
        server.baseUrl() + "/api/attachments/att-1?token=tok"

    /** Каталог, в который скачиватель складывает файлы вложений. */
    private fun attachmentsDirectory(): File = File(cacheDirectory, "attachments")

    private fun savedFiles(): List<File> = attachmentsDirectory().listFiles()?.toList().orEmpty()

    private companion object {
        const val BODY = "тело вложения"
    }
}
