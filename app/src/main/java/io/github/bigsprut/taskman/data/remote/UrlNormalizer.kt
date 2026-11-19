package io.github.bigsprut.taskman.data.remote

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Нормализация и проверка адреса сервера, который вводит пользователь.
 *
 * Base URL живёт в настройках, а не в Retrofit: инстанс один, адрес может измениться без
 * перезапуска приложения (экран входа, смена сервера). Поэтому Retrofit создаётся на
 * заглушке, а настоящий адрес подставляет [ConnectionInterceptor].
 */
object UrlNormalizer {

    /** Схема считается защищённой: release-сборка не ходит по cleartext. */
    private const val HTTPS = "https"
    private const val HTTP = "http"

    /** Убирает пробелы и завершающий слэш: `https://host:8444/` → `https://host:8444`. */
    fun normalize(raw: String): String = raw.trim().trimEnd('/')

    /**
     * Проверка адреса перед сохранением.
     *
     * @param allowCleartext `http://` разрешён только debug-сборке (локальный стенд разработки).
     *   В release допустим только https — токен не должен уходить по открытому каналу.
     */
    fun isValid(raw: String, allowCleartext: Boolean): Boolean {
        val url = normalize(raw).toHttpUrlOrNull() ?: return false
        if (url.host.isBlank()) return false
        return when (url.scheme) {
            HTTPS -> true
            HTTP -> allowCleartext
            else -> false
        }
    }

    /**
     * Подставляет адрес настроенного сервера в запрос, сохраняя путь и query.
     *
     * Если в base URL указан путь (`https://host/taskman`), он становится префиксом пути
     * запроса — так клиент работает и за reverse-proxy с префиксом.
     *
     * @return `null`, если base URL не разбирается (тогда запрос уйдёт на заглушку и
     *   закончится сетевой ошибкой — это честнее, чем молча идти не туда).
     */
    fun endpointFor(baseUrl: String, requestUrl: HttpUrl): HttpUrl? {
        val base = normalize(baseUrl).toHttpUrlOrNull() ?: return null
        val basePath = base.encodedPath.trimEnd('/')
        val requestPath = requestUrl.encodedPath
        return requestUrl.newBuilder()
            .scheme(base.scheme)
            .host(base.host)
            .port(base.port)
            .encodedPath(basePath + requestPath)
            .build()
    }
}
