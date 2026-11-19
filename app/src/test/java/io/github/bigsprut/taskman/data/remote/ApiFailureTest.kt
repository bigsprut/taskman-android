package io.github.bigsprut.taskman.data.remote

import com.google.common.truth.Truth.assertThat
import io.github.bigsprut.taskman.testutil.jsonBody
import org.junit.Test

/**
 * Классификация неуспешных HTTP-ответов (ТЗ §6, «Ошибки»).
 */
class ApiFailureTest {

    @Test
    fun `401 даёт Unauthorized`() {
        val failure = ApiFailure.fromHttpResponse(401, jsonBody("""{"error":"unauthorized"}"""))

        assertThat(failure).isEqualTo(ApiFailure.Unauthorized)
    }

    @Test
    fun `403 device даёт DeviceNotApproved`() {
        val failure = ApiFailure.fromHttpResponse(403, jsonBody("""{"error":"device"}"""))

        assertThat(failure).isEqualTo(ApiFailure.DeviceNotApproved)
    }

    @Test
    fun `403 без device остаётся Http с кодом`() {
        val failure = ApiFailure.fromHttpResponse(403, jsonBody("""{"error":"forbidden"}"""))

        assertThat(failure).isInstanceOf(ApiFailure.Http::class.java)
        val http = failure as ApiFailure.Http
        assertThat(http.code).isEqualTo(403)
        assertThat(http.serverError).isEqualTo("forbidden")
    }

    @Test
    fun `409 даёт Conflict с датой из тела`() {
        val failure = ApiFailure.fromHttpResponse(
            409,
            jsonBody("""{"error":"conflict","actual_updated_at":1765843200.123}"""),
        )

        assertThat(failure).isInstanceOf(ApiFailure.Conflict::class.java)
        assertThat((failure as ApiFailure.Conflict).actualUpdatedAt).isEqualTo(1765843200.123)
    }

    @Test
    fun `500 с пустым телом даёт Http без serverError`() {
        val failure = ApiFailure.fromHttpResponse(500, null)

        assertThat(failure).isInstanceOf(ApiFailure.Http::class.java)
        val http = failure as ApiFailure.Http
        assertThat(http.code).isEqualTo(500)
        assertThat(http.serverError).isNull()
    }

    @Test
    fun `пустое тело не мешает классификации`() {
        val failure = ApiFailure.fromHttpResponse(418, jsonBody(""))

        assertThat(failure).isInstanceOf(ApiFailure.Http::class.java)
        val http = failure as ApiFailure.Http
        assertThat(http.code).isEqualTo(418)
        assertThat(http.serverError).isNull()
    }

    @Test
    fun `не-JSON тело не роняет классификацию`() {
        val failure = ApiFailure.fromHttpResponse(500, jsonBody("<html>bad gateway</html>"))

        assertThat(failure).isInstanceOf(ApiFailure.Http::class.java)
        val http = failure as ApiFailure.Http
        assertThat(http.code).isEqualTo(500)
        assertThat(http.serverError).isNull()
    }
}
