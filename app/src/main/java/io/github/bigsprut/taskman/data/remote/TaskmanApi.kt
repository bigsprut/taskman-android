package io.github.bigsprut.taskman.data.remote

import io.github.bigsprut.taskman.data.remote.dto.DeleteTaskResponse
import io.github.bigsprut.taskman.data.remote.dto.StateResponse
import io.github.bigsprut.taskman.data.remote.dto.TaskInDto
import io.github.bigsprut.taskman.data.remote.dto.UpsertTasksResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * API taskman (ТЗ §6). Заголовки `Token` и `X-Device-Id` добавляет
 * [ConnectionInterceptor] — они не дублируются в объявлении методов, чтобы токен не
 * оказался в аннотациях и не попал в сгенерированный код/логи.
 *
 * Все методы возвращают [Response], потому что коды ошибок несут смысл для UX:
 * 401 → экран входа, 403 `device` → ожидание подтверждения, 409 → конфликт версий.
 */
interface TaskmanApi {

    /**
     * Полный снапшот состояния. OkHttp-кэш сам добавляет `If-None-Match` и отдаёт тело
     * из кэша на `304` (§4), поэтому метод не знает про ETag.
     */
    @GET("api/state")
    suspend fun getState(): Response<StateResponse>

    /**
     * UPSERT задач полным объектом (§4). `updated_at` в теле — версия из последнего
     * `/api/state`; расхождение → 409 `{"error":"conflict","actual_updated_at":…}`.
     */
    @POST("api/tasks")
    suspend fun upsertTasks(@Body tasks: List<TaskInDto>): Response<UpsertTasksResponse>

    /** Soft-delete задачи и её подзадач одним вызовом (§4). */
    @DELETE("api/tasks/{id}")
    suspend fun deleteTask(@Path("id") id: String): Response<DeleteTaskResponse>
}
