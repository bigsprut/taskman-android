package io.github.bigsprut.taskman.ui.session

import com.google.common.truth.Truth.assertThat
import io.github.bigsprut.taskman.data.remote.ApiFailure
import java.io.IOException
import org.junit.Test

/**
 * Таблица перевода сетевой причины в ошибку экрана входа (§4: «401 и нет сети различаются»).
 *
 * Зачем отдельный тест: это регресс живой приёмки. Раньше `IOException` и отказ токена
 * показывались одной строкой «не удалось подключиться», и пользователь с неверным токеном
 * бесконечно жал «Подключиться». Поэтому здесь фиксируется не только сам маппинг, но и то,
 * что 401 и сеть дают **разные** классы ошибки.
 */
class LoginErrorMappingTest {

    @Test
    fun `отказ авторизации остаётся отказом авторизации`() {
        assertThat(ApiFailure.Unauthorized.toLoginError()).isEqualTo(LoginError.Unauthorized)
    }

    @Test
    fun `HTTP 401 из защитной ветки тоже даёт неверный токен`() {
        // 401 может прийти как Http, если классификацию делал не fromHttpResponse.
        assertThat(ApiFailure.Http(401).toLoginError()).isEqualTo(LoginError.Unauthorized)
        assertThat(ApiFailure.Http(401, "unauthorized").toLoginError())
            .isEqualTo(LoginError.Unauthorized)
    }

    @Test
    fun `прочий HTTP-код отдаётся в ошибку с кодом`() {
        assertThat(ApiFailure.Http(500).toLoginError()).isEqualTo(LoginError.Http(500))
    }

    @Test
    fun `403 device не превращается в неверный токен`() {
        // При входе 403 device обрабатывается как успех проверки токена (экран ожидания),
        // но если он всё же дошёл сюда — это HTTP-код, а не «неверный токен».
        assertThat(ApiFailure.Http(403, "device").toLoginError()).isEqualTo(LoginError.Http(403))
    }

    @Test
    fun `сетевой сбой даёт «нет связи»`() {
        assertThat(ApiFailure.Network(IOException("нет сети")).toLoginError())
            .isEqualTo(LoginError.Network)
    }

    @Test
    fun `неразобранный ответ — не сеть и не токен, а неожиданные данные`() {
        val malformed = ApiFailure.Malformed(IllegalStateException("нет поля id"))

        assertThat(malformed.toLoginError()).isEqualTo(LoginError.UnexpectedData)
    }

    @Test
    fun `внутренняя ошибка клиента — неожиданные данные`() {
        assertThat(ApiFailure.Internal(IllegalStateException("сбой Room")).toLoginError())
            .isEqualTo(LoginError.UnexpectedData)
    }

    @Test
    fun `409 при входе — тоже неожиданные данные`() {
        assertThat(ApiFailure.Conflict(actualUpdatedAt = null).toLoginError())
            .isEqualTo(LoginError.UnexpectedData)
    }

    @Test
    fun `остальные отказы контракта сводятся к неожиданным данным`() {
        // Логика входа на эти причины не опирается: различать их пользователю нечего.
        assertThat(ApiFailure.InvalidInput.toLoginError()).isEqualTo(LoginError.UnexpectedData)
        assertThat(ApiFailure.FileAccess.toLoginError()).isEqualTo(LoginError.UnexpectedData)
        assertThat(ApiFailure.DeviceNotApproved.toLoginError())
            .isEqualTo(LoginError.UnexpectedData)
    }

    @Test
    fun `401 и сетевой сбой дают разные ошибки`() {
        // Регресс живой приёмки: «неверный токен» и «нет связи» — разные экраны и разные советы
        // пользователю, их нельзя склеивать в одну строку.
        val unauthorized = ApiFailure.Unauthorized.toLoginError()
        val network = ApiFailure.Network(IOException("нет сети")).toLoginError()

        assertThat(unauthorized).isInstanceOf(LoginError.Unauthorized::class.java)
        assertThat(network).isInstanceOf(LoginError.Network::class.java)
        assertThat(network).isNotInstanceOf(LoginError.Unauthorized::class.java)
        assertThat(unauthorized).isNotEqualTo(network)
    }
}
