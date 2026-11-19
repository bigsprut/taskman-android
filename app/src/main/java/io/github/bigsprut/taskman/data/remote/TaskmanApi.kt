package io.github.bigsprut.taskman.data.remote

import io.github.bigsprut.taskman.data.remote.dto.StateResponse
import retrofit2.Response
import retrofit2.http.GET

/**
 * API taskman (ТЗ §6). Заголовки `Token` и `X-Device-Id` добавляет
 * [ConnectionInterceptor] — они не дублируются в объявлении методов, чтобы токен не
 * оказался в аннотациях и не попал в сгенерированный код/логи.
 *
 * Ф0 использует только чтение состояния; мутации (POST/DELETE) появятся в Ф1–Ф3.
 */
interface TaskmanApi {

    /**
     * Полный снапшот состояния. OkHttp-кэш сам добавляет `If-None-Match` и отдаёт тело
     * из кэша на `304` (§4), поэтому метод не знает про ETag.
     *
     * Возвращается [Response], а не тело: коды 401/403/409 несут смысл для навигации
     * (экран входа, ожидание устройства, конфликт версий), поэтому их нельзя терять
     * в исключении.
     */
    @GET("api/state")
    suspend fun getState(): Response<StateResponse>
}
