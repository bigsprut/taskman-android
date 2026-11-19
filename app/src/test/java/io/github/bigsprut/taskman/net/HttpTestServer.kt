package io.github.bigsprut.taskman.net

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.net.InetSocketAddress
import java.util.Collections
import java.util.TreeMap
import java.util.concurrent.Executors
import java.util.zip.GZIPOutputStream

/**
 * Запрос, который дошёл до тестового сервера.
 *
 * [headers] — карта с регистронезависимым доступом: `headers["Token"]` и `headers["token"]`
 * вернут одно и то же. Это не удобство, а необходимость: имена HTTP-заголовков
 * регистронезависимы, а `com.sun.net.httpserver` вдобавок нормализует написание
 * (`X-Device-Id` доходит как `X-device-id`). Поэтому исходный регистр с провода
 * восстановить нельзя — проверяются имя без учёта регистра и значение. Значимое
 * отличие `Token` от `X-Token` при этом сохраняется: это разные имена, а не регистр.
 */
class RecordedRequest(
    val method: String,
    val path: String,
    rawHeaders: Map<String, String>,
) {

    val headers: Map<String, String> = TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER).apply {
        putAll(rawHeaders)
    }

    override fun toString(): String = "$method $path $headers"
}

/** Ответ, который сервер отдаст на очередной запрос (очередь наполняет тест). */
private class QueuedResponse(
    val code: Int,
    val body: ByteArray,
    val headers: Map<String, String>,
)

/**
 * Мини-сервер на JDK (`com.sun.net.httpserver`) вместо MockWebServer — без новых зависимостей.
 *
 * Сервер поднимается на loopback и эфемерном порту (0), поэтому тесты не занимают
 * фиксированный порт и не ходят в интернет. На все пути зарегистрирован один обработчик:
 * он запоминает запрос (метод, путь, заголовки), а ответ берёт из очереди [respond] /
 * [gzipResponse] — что именно ответить, решает тест.
 *
 * Закрывать обязательно (в тестах — `use`), иначе поток сервера останется жить.
 */
class HttpTestServer : Closeable {

    private val lock = Any()

    /** Ответы ждут своей очереди: тест сам задаёт сценарий. */
    private val queued = ArrayDeque<QueuedResponse>()

    /** Все дошедшие запросы; наружу отдаются копией через [requests]. */
    private val recorded: MutableList<RecordedRequest> =
        Collections.synchronizedList(mutableListOf<RecordedRequest>())

    /** Потоки сервера — демоны, чтобы забытый сервер не держал JVM тестов. */
    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "taskman-http-test").apply { isDaemon = true }
    }

    private val server: HttpServer = HttpServer.create(InetSocketAddress(LOOPBACK, 0), 0)

    init {
        server.createContext("/") { exchange -> handle(exchange) }
        server.executor = executor
        server.start()
    }

    /** Порт, выданный ОС: в тестах фиксированные порты не используются. */
    val port: Int = server.address.port

    /** Снимок записанных запросов: копия, чтобы тест не держал блокировку сервера. */
    val requests: List<RecordedRequest>
        get() = synchronized(recorded) { recorded.toList() }

    /** Адрес сервера; [pathPrefix], например `/taskman`, имитирует reverse-proxy с префиксом. */
    fun baseUrl(pathPrefix: String = ""): String = "http://$LOOPBACK:$port$pathPrefix"

    /**
     * Поставить в очередь ответ: код, тело, дополнительные заголовки.
     * У `204`/`304` тело не отправляется (так требует HTTP), код и заголовки — отправляются.
     */
    fun respond(code: Int, body: String = "", headers: Map<String, String> = emptyMap()) {
        enqueue(QueuedResponse(code, body.toByteArray(Charsets.UTF_8), headers))
    }

    /** Поставить в очередь gzip-ответ: тело сжимается, добавляется `Content-Encoding: gzip`. */
    fun gzipResponse(code: Int = 200, body: String, headers: Map<String, String> = emptyMap()) {
        val compressed = ByteArrayOutputStream().use { buffer ->
            GZIPOutputStream(buffer).use { gzip -> gzip.write(body.toByteArray(Charsets.UTF_8)) }
            buffer.toByteArray()
        }
        enqueue(
            QueuedResponse(
                code = code,
                body = compressed,
                headers = headers + mapOf(
                    HEADER_CONTENT_ENCODING to GZIP,
                    HEADER_CONTENT_TYPE to JSON_CONTENT_TYPE,
                ),
            ),
        )
    }

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }

    private fun enqueue(response: QueuedResponse) {
        synchronized(lock) { queued.addLast(response) }
    }

    private fun handle(exchange: HttpExchange) {
        try {
            recorded.add(
                RecordedRequest(
                    method = exchange.requestMethod,
                    path = exchange.requestURI.path,
                    rawHeaders = exchange.requestHeaders.entries.associate { (name, values) ->
                        name to values.joinToString(separator = ", ")
                    },
                ),
            )
            // Очередь пуста — отвечаем явной ошибкой, чтобы тест упал, а не завис на таймауте.
            val response = synchronized(lock) { queued.removeFirstOrNull() }
                ?: QueuedResponse(
                    code = 500,
                    body = NO_RESPONSE_QUEUED.toByteArray(Charsets.UTF_8),
                    headers = emptyMap(),
                )
            write(exchange, response)
        } finally {
            exchange.close()
        }
    }

    private fun write(exchange: HttpExchange, response: QueuedResponse) {
        response.headers.forEach { (name, value) -> exchange.responseHeaders.set(name, value) }
        if (exchange.responseHeaders.getFirst(HEADER_CONTENT_TYPE) == null) {
            exchange.responseHeaders.set(HEADER_CONTENT_TYPE, JSON_CONTENT_TYPE)
        }
        // 204/304 не имеют тела: HttpServer требует для них responseLength <= -1.
        if (response.code == 204 || response.code == 304) {
            exchange.sendResponseHeaders(response.code, -1)
            return
        }
        exchange.sendResponseHeaders(response.code, response.body.size.toLong())
        exchange.responseBody.use { it.write(response.body) }
    }
}

private const val LOOPBACK = "127.0.0.1"
private const val GZIP = "gzip"
private const val HEADER_CONTENT_ENCODING = "Content-Encoding"
private const val HEADER_CONTENT_TYPE = "Content-Type"
private const val JSON_CONTENT_TYPE = "application/json; charset=utf-8"
private const val NO_RESPONSE_QUEUED = """{"error":"no_response_queued"}"""
