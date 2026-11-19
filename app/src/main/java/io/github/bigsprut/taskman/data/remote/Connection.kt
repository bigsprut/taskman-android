package io.github.bigsprut.taskman.data.remote

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Источник текущих параметров подключения для сетевого слоя.
 *
 * Интерфейс (а не ссылка на репозиторий) нужен, чтобы сеть не зависела от DataStore и
 * чтобы правила контракта можно было проверить тестом с подставным источником.
 */
interface ConnectionProvider {
    fun baseUrl(): String?
    fun token(): String?
    fun deviceId(): String?
}

/**
 * Потокобезопасный держатель текущих параметров подключения.
 *
 * Живёт столько же, сколько процесс: значения обновляются при загрузке настроек, входе,
 * смене сервера и выходе. Читается из потоков OkHttp, поэтому состояние хранится одним
 * неизменяемым объектом в `@Volatile`-поле: интерсептор никогда не увидит новый адрес со
 * старым токеном (это отправило бы секрет не на тот хост).
 */
class ConnectionHolder : ConnectionProvider {

    private data class Config(
        val baseUrl: String?,
        val token: String?,
        val deviceId: String?,
    )

    @Volatile
    private var config = Config(baseUrl = null, token = null, deviceId = null)

    override fun baseUrl(): String? = config.baseUrl

    override fun token(): String? = config.token

    override fun deviceId(): String? = config.deviceId

    fun set(baseUrl: String?, token: String?, deviceId: String?) {
        config = Config(baseUrl = baseUrl, token = token, deviceId = deviceId)
    }

    fun clearToken() {
        config = config.copy(token = null)
    }
}

/**
 * Единственное место, где к запросу добавляются заголовки контракта (ТЗ §4, §6):
 * `Token` — авторизация, `X-Device-Id` — привязка устройства (TOFU).
 *
 * Заголовки сознательно не заданы в аннотациях Retrofit: так токен не попадает в
 * сгенерированный код и его проще держать только в памяти.
 */
class ConnectionInterceptor(private val connection: ConnectionProvider) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        val builder = request.newBuilder()
        val configuredBaseUrl = connection.baseUrl()
        if (configuredBaseUrl != null) {
            UrlNormalizer.endpointFor(configuredBaseUrl, request.url)?.let(builder::url)
        }
        connection.deviceId()?.takeIf { it.isNotBlank() }?.let { builder.header(HEADER_DEVICE_ID, it) }
        connection.token()?.takeIf { it.isNotBlank() }?.let { builder.header(HEADER_TOKEN, it) }

        return chain.proceed(builder.build())
    }

    private companion object {
        /** Именно `Token`, не `X-Token` (ТЗ §4). */
        const val HEADER_TOKEN = "Token"
        const val HEADER_DEVICE_ID = "X-Device-Id"
    }
}
