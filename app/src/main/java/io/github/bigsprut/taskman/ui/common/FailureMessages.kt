package io.github.bigsprut.taskman.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.bigsprut.taskman.R
import io.github.bigsprut.taskman.data.remote.ApiFailure
import io.github.bigsprut.taskman.ui.session.LoginError

/**
 * Человеческое описание сбоя. Код ответа показывается явно, если сервер его дал:
 * «сервер отказал: 500» полезнее, чем «что-то пошло не так» (ТЗ §5.3, Ф4).
 */
@Composable
fun ApiFailure.userMessage(): String = when (this) {
    is ApiFailure.Network -> stringResource(R.string.error_network)
    ApiFailure.Unauthorized -> stringResource(R.string.error_unauthorized)
    ApiFailure.DeviceNotApproved -> stringResource(R.string.error_device_pending)
    is ApiFailure.Conflict -> stringResource(R.string.error_conflict)
    is ApiFailure.Http -> stringResource(R.string.error_http, code)
    is ApiFailure.Malformed -> stringResource(R.string.error_data)
    ApiFailure.InvalidInput -> stringResource(R.string.error_invalid_input)
    is ApiFailure.Internal -> stringResource(R.string.error_internal)
}

/** Текст ошибки формы входа. */
@Composable
fun LoginError.userMessage(): String = when (this) {
    LoginError.InvalidUrl -> stringResource(R.string.login_error_invalid_url)
    LoginError.EmptyToken -> stringResource(R.string.login_error_empty_token)
    LoginError.Unauthorized -> stringResource(R.string.login_error_unauthorized)
    LoginError.Network -> stringResource(R.string.login_error_network)
    LoginError.UnexpectedData -> stringResource(R.string.login_error_data)
    LoginError.Storage -> stringResource(R.string.login_error_storage)
    is LoginError.Http -> stringResource(R.string.login_error_http, code)
}
