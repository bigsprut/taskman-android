package io.github.bigsprut.taskman.data.remote

import io.github.bigsprut.taskman.data.remote.dto.ApiErrorDto
import okhttp3.ResponseBody

/**
 * Причины неуспеха сетевого вызова — в терминах контракта (ТЗ §6, «Ошибки»).
 *
 * Отдельные типы нужны потому, что реакция приложения у них разная:
 * 401 → экран входа, 403 `device` → экран ожидания подтверждения, 409 → конфликт версий
 * задачи, остальные 4xx → сообщение с кодом, `IOException` → «нет сети» (в Ф4 такие
 * сбои попадут в очередь, а серверные отказы — нет).
 */
sealed class ApiFailure(message: String?, cause: Throwable? = null) : Exception(message, cause) {

    /** Сеть недоступна или соединение оборвалось (в Ф4 — повод для очереди мутаций). */
    class Network(cause: Throwable) : ApiFailure("network", cause)

    /** 401 — токен не принят. */
    data object Unauthorized : ApiFailure("unauthorized")

    /** 403 `{"error":"device"}` — устройство ждёт подтверждения (TOFU, §4). */
    data object DeviceNotApproved : ApiFailure("device")

    /** 409 — задачу изменил другой клиент; `actualUpdatedAt` — версия на сервере (§4). */
    class Conflict(val actualUpdatedAt: Double?) : ApiFailure("conflict")

    /** Прочие ответы сервера с кодом ошибки. */
    class Http(val code: Int, val serverError: String? = null) : ApiFailure("http-$code")

    /** Ответ 2xx не разобрался в модель (расхождение контракта — повод для issue). */
    class Malformed(cause: Throwable) : ApiFailure("malformed", cause)

    /**
     * Внутренняя ошибка клиента (например, запись в локальную базу не удалась).
     * Отдельный тип, чтобы не выдавать сбой хранилища за «сервер вернул странный ответ».
     */
    class Internal(cause: Throwable) : ApiFailure("internal", cause)

    companion object {

        /**
         * Классификация неуспешного ответа.
         *
         * Разбор тела ошибки необязателен: если сервер ответил пустым/неизвестным телом,
         * код ответа всё равно достаточен для решения.
         */
        fun fromHttpResponse(code: Int, errorBody: ResponseBody?): ApiFailure {
            val parsed = parseErrorBody(errorBody)
            return when (code) {
                401 -> Unauthorized
                403 -> if (parsed?.error == "device") DeviceNotApproved else Http(403, parsed?.error)
                409 -> Conflict(parsed?.actualUpdatedAt)
                else -> Http(code, parsed?.error)
            }
        }

        private fun parseErrorBody(errorBody: ResponseBody?): ApiErrorDto? {
            val text = runCatching { errorBody?.string() }.getOrNull().orEmpty()
            if (text.isBlank()) return null
            return runCatching { TaskmanJson.decodeFromString<ApiErrorDto>(text) }.getOrNull()
        }
    }
}
