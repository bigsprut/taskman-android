package io.github.bigsprut.taskman.data.remote

import com.google.common.truth.Truth.assertThat
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Test

/**
 * Нормализация и проверка адреса сервера, подстановка base URL в запрос (ТЗ §5.4, §6).
 */
class UrlNormalizerTest {

    private fun request(url: String) = url.toHttpUrl()

    @Test
    fun `normalize убирает пробелы и завершающий слэш`() {
        assertThat(UrlNormalizer.normalize(" https://h:8444/ ")).isEqualTo("https://h:8444")
    }

    @Test
    fun `https валиден без разрешения cleartext`() {
        assertThat(UrlNormalizer.isValid("https://h:8444", allowCleartext = false)).isTrue()
    }

    @Test
    fun `http валиден только при allowCleartext`() {
        assertThat(UrlNormalizer.isValid("http://h:8444", allowCleartext = false)).isFalse()
        assertThat(UrlNormalizer.isValid("http://h:8444", allowCleartext = true)).isTrue()
    }

    @Test
    fun `мусор, пустая строка и не-http схема невалидны`() {
        assertThat(UrlNormalizer.isValid("не адрес", allowCleartext = true)).isFalse()
        assertThat(UrlNormalizer.isValid("", allowCleartext = true)).isFalse()
        assertThat(UrlNormalizer.isValid("ftp://h", allowCleartext = true)).isFalse()
    }

    @Test
    fun `endpointFor подставляет схему, хост и порт base URL`() {
        val endpoint = UrlNormalizer.endpointFor(
            baseUrl = " https://h:8444/ ",
            requestUrl = request("http://127.0.0.1/api/state"),
        )

        assertThat(endpoint).isNotNull()
        assertThat(endpoint!!.scheme).isEqualTo("https")
        assertThat(endpoint.host).isEqualTo("h")
        assertThat(endpoint.port).isEqualTo(8444)
        assertThat(endpoint.encodedPath).isEqualTo("/api/state")
    }

    @Test
    fun `endpointFor сохраняет префикс пути base URL`() {
        val endpoint = UrlNormalizer.endpointFor(
            baseUrl = "https://h/taskman",
            requestUrl = request("http://127.0.0.1/api/state"),
        )

        assertThat(endpoint).isNotNull()
        assertThat(endpoint!!.encodedPath).isEqualTo("/taskman/api/state")
    }

    @Test
    fun `endpointFor сохраняет query запроса`() {
        val endpoint = UrlNormalizer.endpointFor(
            baseUrl = "https://h",
            requestUrl = request("http://127.0.0.1/api/state?since=10&limit=5"),
        )

        assertThat(endpoint).isNotNull()
        assertThat(endpoint!!.queryParameter("since")).isEqualTo("10")
        assertThat(endpoint.queryParameter("limit")).isEqualTo("5")
        assertThat(endpoint.encodedPath).isEqualTo("/api/state")
    }

    @Test
    fun `endpointFor возвращает null для невалидного base URL`() {
        val endpoint = UrlNormalizer.endpointFor(
            baseUrl = "не адрес",
            requestUrl = request("http://127.0.0.1/api/state"),
        )

        assertThat(endpoint).isNull()
    }
}
