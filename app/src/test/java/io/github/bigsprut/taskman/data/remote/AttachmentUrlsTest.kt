package io.github.bigsprut.taskman.data.remote

import com.google.common.truth.Truth.assertThat
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Test

/**
 * Ссылка на файл вложения (ТЗ §4): `GET /api/attachments/{id}?token=…`.
 *
 * У этого эндпоинта токен передаётся **только** query-параметром (заголовок `Token` он не
 * читает), поэтому ссылка собирается целиком и попадает в Coil/скачивание как абсолютный URL.
 * Тесты фиксируют три вещи, от которых зависят превью и скачивание: префикс reverse-proxy
 * не теряется, спецсимволы токена не ломают query, а неполные настройки дают `null`,
 * а не ссылку с пустым токеном.
 */
class AttachmentUrlsTest {

    @Test
    fun `build собирает абсолютный URL с токеном в query`() {
        val url = AttachmentUrls.build(BASE_URL, TOKEN, ATTACHMENT_ID)

        assertThat(url).isEqualTo("https://h:8444/api/attachments/abc?token=tok")
    }

    @Test
    fun `build сохраняет префикс пути baseUrl`() {
        // Адрес может быть за reverse-proxy с префиксом: путь к API дописывается к нему,
        // а не заменяет его (иначе запрос ушёл бы не туда).
        val url = AttachmentUrls.build("https://h/taskman", TOKEN, ATTACHMENT_ID)

        assertThat(url).isEqualTo("https://h/taskman/api/attachments/abc?token=tok")
    }

    @Test
    fun `спецсимволы токена экранируются в query`() {
        val token = "a+b/c=d e"

        val url = AttachmentUrls.build(BASE_URL, token, ATTACHMENT_ID)!!

        // Сырыми эти символы ломают разбор: `+` в query читается как пробел, `/` и `=` —
        // как разделители. Поэтому проверяем именно то, что уходит по проводу.
        assertThat(url.toHttpUrl().encodedQuery).isEqualTo("token=a%2Bb%2Fc%3Dd%20e")
        // И то, что сервер, разобрав query, получит исходный токен.
        assertThat(url.toHttpUrl().queryParameter("token")).isEqualTo(token)
    }

    @Test
    fun `id вложения экранируется как один сегмент пути`() {
        val url = AttachmentUrls.build(BASE_URL, TOKEN, "a/b c")!!

        // Слэш в id не должен превратиться в лишний сегмент пути: иначе запрос уйдёт
        // на другой эндпоинт, а сервер вернёт 404 вместо файла.
        assertThat(url).isEqualTo("https://h:8444/api/attachments/a%2Fb%20c?token=tok")
        assertThat(url.toHttpUrl().pathSegments)
            .containsExactly("api", "attachments", "a/b c")
    }

    @Test
    fun `без baseUrl или token ссылки нет`() {
        assertThat(AttachmentUrls.build(null, TOKEN, ATTACHMENT_ID)).isNull()
        assertThat(AttachmentUrls.build("", TOKEN, ATTACHMENT_ID)).isNull()
        assertThat(AttachmentUrls.build("   ", TOKEN, ATTACHMENT_ID)).isNull()
        assertThat(AttachmentUrls.build(BASE_URL, null, ATTACHMENT_ID)).isNull()
        // Пустой токен в ссылке выглядел бы как «подключение настроено», но сервер ответил бы 401.
        assertThat(AttachmentUrls.build(BASE_URL, "", ATTACHMENT_ID)).isNull()
        assertThat(AttachmentUrls.build(BASE_URL, "  ", ATTACHMENT_ID)).isNull()
    }

    @Test
    fun `пустой id вложения даёт null`() {
        assertThat(AttachmentUrls.build(BASE_URL, TOKEN, "")).isNull()
        assertThat(AttachmentUrls.build(BASE_URL, TOKEN, "   ")).isNull()
    }

    @Test
    fun `мусорный baseUrl даёт null`() {
        assertThat(AttachmentUrls.build("не адрес", TOKEN, ATTACHMENT_ID)).isNull()
        // Не-http схема тоже не годится: Retrofit/Coil по такой ссылке не пойдут.
        assertThat(AttachmentUrls.build("ftp://h", TOKEN, ATTACHMENT_ID)).isNull()
    }

    @Test
    fun `пробелы и завершающий слэш в baseUrl не ломают сборку`() {
        assertThat(AttachmentUrls.build(" https://h:8444/ ", TOKEN, ATTACHMENT_ID))
            .isEqualTo("https://h:8444/api/attachments/abc?token=tok")
    }

    private companion object {
        const val BASE_URL = "https://h:8444"
        const val TOKEN = "tok"
        const val ATTACHMENT_ID = "abc"
    }
}
