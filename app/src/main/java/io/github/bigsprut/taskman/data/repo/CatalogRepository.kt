package io.github.bigsprut.taskman.data.repo

import io.github.bigsprut.taskman.data.remote.ApiFailure
import io.github.bigsprut.taskman.data.remote.TaskmanApi
import io.github.bigsprut.taskman.data.remote.dto.CategoryInDto
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import io.github.bigsprut.taskman.data.remote.dto.TagInDto
import java.io.IOException
import kotlinx.coroutines.CancellationException
import retrofit2.Response

/**
 * Справочники тегов и категорий + настройки инстанса (§5.3-Ф5, §6).
 *
 * Как и сотрудники (Ф3), мутации идут прямыми запросами с последующим `refresh()`:
 * справочники — редкие действия, а сервер — источник правды по id и цветам. Сетевой сбой
 * показывается сообщением (в офлайн-очередь справочники не ставятся, см. PLAN-F4 §2.4).
 */
class CatalogRepository(
    private val api: TaskmanApi,
    private val tasks: TaskRepository,
) {

    suspend fun createTag(title: String, color: String?): MutationOutcome {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return MutationOutcome.Failed(ApiFailure.InvalidInput)
        return execute { api.createTag(TagInDto(trimmed, color)) }
    }

    /** Обновление тега: цвет можно поменять, не трогая заголовок. */
    suspend fun updateTag(id: String, title: String?, color: String?): MutationOutcome {
        if (title != null && title.isBlank()) return MutationOutcome.Failed(ApiFailure.InvalidInput)
        return execute { api.updateTag(id, TagInDto(title?.trim(), color)) }
    }

    suspend fun deleteTag(id: String): MutationOutcome = execute { api.deleteTag(id) }

    suspend fun createCategory(title: String, color: String?): MutationOutcome {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return MutationOutcome.Failed(ApiFailure.InvalidInput)
        return execute { api.createCategory(CategoryInDto(trimmed, color)) }
    }

    suspend fun updateCategory(id: String, title: String?, color: String?): MutationOutcome {
        if (title != null && title.isBlank()) return MutationOutcome.Failed(ApiFailure.InvalidInput)
        return execute { api.updateCategory(id, CategoryInDto(title?.trim(), color)) }
    }

    suspend fun deleteCategory(id: String): MutationOutcome = execute { api.deleteCategory(id) }

    /** Категория по умолчанию для новых задач: `PATCH /api/settings` (§6). */
    suspend fun setDefaultCategory(categoryId: String?): MutationOutcome {
        // Явный `null` в теле: иначе поле выпадет и сервер не поймёт «снять категорию».
        val body = buildJsonObject {
            put("default_category", categoryId?.let(::JsonPrimitive) ?: JsonNull)
        }
        return execute { api.updateSettings(body) }
    }


    private suspend fun execute(call: suspend () -> Response<*>): MutationOutcome = try {
        val response = call()
        if (response.isSuccessful) {
            tasks.refresh()
            MutationOutcome.Success
        } else {
            when (val failure = ApiFailure.fromHttpResponse(response.code(), response.errorBody())) {
                ApiFailure.Unauthorized, ApiFailure.DeviceNotApproved -> {
                    tasks.reportAuthFailure(failure)
                    MutationOutcome.Failed(failure)
                }

                else -> MutationOutcome.Failed(failure)
            }
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (io: IOException) {
        MutationOutcome.Failed(ApiFailure.Network(io))
    } catch (error: Exception) {
        MutationOutcome.Failed(ApiFailure.Internal(error))
    }
}
