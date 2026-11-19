package io.github.bigsprut.taskman.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.res.stringResource
import io.github.bigsprut.taskman.R
import io.github.bigsprut.taskman.data.remote.ApiFailure
import java.util.Locale

/**
 * Тексты ошибок [ApiFailure] как значения (без Compose и без `Context`).
 *
 * Зачем так: снекбары показываются из корутин, где нет composable-контекста, а обращаться
 * к `LocalContext.getString` из композиции нельзя — lint справедливо считает такое чтение
 * неconfiguration-aware. Поэтому строки собираются один раз в композиции
 * ([rememberFailureTexts]) и дальше используются как обычные значения — их можно и в тестах
 * проверить.
 */
@Immutable
data class FailureTexts(
    val network: String,
    val unauthorized: String,
    val devicePending: String,
    val conflict: String,
    val malformed: String,
    val internal: String,
    val invalidInput: String,
    val fileAccess: String,
    /** Шаблон с `%1$d` для кода ответа сервера. */
    val httpFormat: String,
) {
    fun describe(failure: ApiFailure): String = when (failure) {
        is ApiFailure.Network -> network
        ApiFailure.Unauthorized -> unauthorized
        ApiFailure.DeviceNotApproved -> devicePending
        is ApiFailure.Conflict -> conflict
        is ApiFailure.Http -> String.format(Locale.getDefault(), httpFormat, failure.code)
        is ApiFailure.Malformed -> malformed
        is ApiFailure.Internal -> internal
        ApiFailure.InvalidInput -> invalidInput
        ApiFailure.FileAccess -> fileAccess
    }
}

@Composable
fun rememberFailureTexts(): FailureTexts = FailureTexts(
    network = stringResource(R.string.error_network),
    unauthorized = stringResource(R.string.error_unauthorized),
    devicePending = stringResource(R.string.error_device_pending),
    conflict = stringResource(R.string.error_conflict),
    malformed = stringResource(R.string.error_data),
    internal = stringResource(R.string.error_internal),
    invalidInput = stringResource(R.string.error_invalid_input),
    fileAccess = stringResource(R.string.error_file_access),
    httpFormat = stringResource(R.string.error_http),
)
