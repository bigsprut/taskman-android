package io.github.bigsprut.taskman.ui.session

import io.github.bigsprut.taskman.data.remote.ApiFailure

/**
 * Перевод сетевой причины в ошибку экрана входа (ТЗ §4: состояния различаются).
 *
 * Вынесено из [SessionViewModel] отдельной функцией, чтобы таблицу маппинга можно было
 * проверить JVM-тестом без DataStore/Keystore:
 *
 * - **401** → [LoginError.Unauthorized] («неверный токен»), а не «нет связи»;
 * - `IOException` → [LoginError.Network] («нет связи»);
 * - **403 `device`** → [LoginError.UnexpectedData]: при входе сервер отвечает 403 до
 *   подтверждения устройства, но этот случай обрабатывается как успешный вход с экраном
 *   ожидания (`SyncOutcome.DeviceNotApproved`), поэтому сюда он попадать не должен;
 * - прочие HTTP-коды → [LoginError.Http] с кодом.
 *
 * Защитная ветка для [ApiFailure.Http] с кодом 401 нужна потому, что 401 может прийти не
 * только как [ApiFailure.Unauthorized]: например, из вспомогательного вызова, который
 * классифицирует ответ сам.
 */
internal fun ApiFailure.toLoginError(): LoginError = when (this) {
    is ApiFailure.Http -> if (code == 401) LoginError.Unauthorized else LoginError.Http(code)
    is ApiFailure.Network -> LoginError.Network
    is ApiFailure.Malformed -> LoginError.UnexpectedData
    is ApiFailure.Conflict -> LoginError.UnexpectedData
    is ApiFailure.Internal -> LoginError.UnexpectedData
    ApiFailure.InvalidInput -> LoginError.UnexpectedData
    ApiFailure.FileAccess -> LoginError.UnexpectedData
    ApiFailure.Unauthorized -> LoginError.Unauthorized
    ApiFailure.DeviceNotApproved -> LoginError.UnexpectedData
}
