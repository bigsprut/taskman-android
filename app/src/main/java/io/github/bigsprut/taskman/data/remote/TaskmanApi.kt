package io.github.bigsprut.taskman.data.remote

import io.github.bigsprut.taskman.data.remote.dto.AttachmentUploadResponse
import io.github.bigsprut.taskman.data.remote.dto.CategoryInDto
import io.github.bigsprut.taskman.data.remote.dto.CategoryResponse
import io.github.bigsprut.taskman.data.remote.dto.DeleteCatalogResponse
import io.github.bigsprut.taskman.data.remote.dto.DeleteAttachmentResponse
import io.github.bigsprut.taskman.data.remote.dto.DeleteEmployeeResponse
import io.github.bigsprut.taskman.data.remote.dto.DeleteTaskResponse
import io.github.bigsprut.taskman.data.remote.dto.EmployeeInDto
import io.github.bigsprut.taskman.data.remote.dto.EmployeeResponse
import kotlinx.serialization.json.JsonObject
import io.github.bigsprut.taskman.data.remote.dto.SettingsResponse
import io.github.bigsprut.taskman.data.remote.dto.StateResponse
import io.github.bigsprut.taskman.data.remote.dto.TagInDto
import io.github.bigsprut.taskman.data.remote.dto.TagResponse
import io.github.bigsprut.taskman.data.remote.dto.TaskInDto
import io.github.bigsprut.taskman.data.remote.dto.UpsertTasksResponse
import okhttp3.MultipartBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Part
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

    /**
     * Аплоад вложения (§6): multipart, **поле формы обязано называться `file`**.
     */
    @Multipart
    @POST("api/tasks/{id}/attachments")
    suspend fun uploadAttachment(
        @Path("id") taskId: String,
        @Part file: MultipartBody.Part,
    ): Response<AttachmentUploadResponse>

    /** Удаление вложения; `GET` файла в этом клиенте идёт через query-токен (см. [AttachmentUrls]). */
    @DELETE("api/attachments/{id}")
    suspend fun deleteAttachment(@Path("id") id: String): Response<DeleteAttachmentResponse>

    // --- Теги и категории (§6, §5.3-Ф5) ---

    @POST("api/tags")
    suspend fun createTag(@Body body: TagInDto): Response<TagResponse>

    @PATCH("api/tags/{id}")
    suspend fun updateTag(@Path("id") id: String, @Body body: TagInDto): Response<TagResponse>

    @DELETE("api/tags/{id}")
    suspend fun deleteTag(@Path("id") id: String): Response<DeleteCatalogResponse>

    @POST("api/categories")
    suspend fun createCategory(@Body body: CategoryInDto): Response<CategoryResponse>

    @PATCH("api/categories/{id}")
    suspend fun updateCategory(
        @Path("id") id: String,
        @Body body: CategoryInDto,
    ): Response<CategoryResponse>

    @DELETE("api/categories/{id}")
    suspend fun deleteCategory(@Path("id") id: String): Response<DeleteCatalogResponse>

    /**
     * Категория по умолчанию для новых задач (§6: `PATCH /api/settings`).
     *
     * Тело — `JsonObject`, а не DTO: «снять категорию» означает `{"default_category": null}`,
     * а kotlinx.serialization с `explicitNulls = false` выбросил бы это поле из DTO и отправил
     * `{}` — сервер мог бы понять это как «не менять».
     */
    @PATCH("api/settings")
    suspend fun updateSettings(@Body body: JsonObject): Response<SettingsResponse>

    // --- Справочник сотрудников (§6, §5.3-Ф3) ---

    @POST("api/employees")
    suspend fun createEmployee(@Body body: EmployeeInDto): Response<EmployeeResponse>

    @PATCH("api/employees/{id}")
    suspend fun updateEmployee(
        @Path("id") id: String,
        @Body body: EmployeeInDto,
    ): Response<EmployeeResponse>

    @DELETE("api/employees/{id}")
    suspend fun deleteEmployee(@Path("id") id: String): Response<DeleteEmployeeResponse>
}
