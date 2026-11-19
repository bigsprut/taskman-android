package io.github.bigsprut.taskman.data.repo

import com.google.common.truth.Truth.assertThat
import io.github.bigsprut.taskman.data.remote.ApiFailure
import io.github.bigsprut.taskman.testutil.FakeStateStore
import io.github.bigsprut.taskman.testutil.FakeTaskmanApi
import io.github.bigsprut.taskman.testutil.errorResponseOf
import java.io.IOException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Справочники тегов и категорий + категория по умолчанию (§5.3-Ф5, §6).
 *
 * Репозиторий не трогает цвет: нормализацию делает ViewModel, а сюда приходит уже готовое
 * значение — проверяется, что репозиторий отправляет ровно переданное. Заголовок он
 * обрезает сам: « Тег » на сервере стал бы отдельным тегом.
 *
 * Успешная мутация перечитывает состояние (`/api/state`): id и цвета справочников —
 * серверная правда, локально их не придумываем.
 */
class CatalogRepositoryTest {

    private val api = FakeTaskmanApi()
    private val store = FakeStateStore()
    private val tasks = TaskRepository(api = api, stateStore = store)
    private val repository = CatalogRepository(api = api, tasks = tasks)

    @Test
    fun `createTag обрезает заголовок, но не трогает цвет`() = runTest {
        val outcome = repository.createTag(" Тег ", "#E5AD00")

        assertThat(outcome).isEqualTo(MutationOutcome.Success)
        val body = api.lastCreatedTag!!
        assertThat(body.title).isEqualTo("Тег")
        // Нормализация — обязанность ViewModel: репозиторий не перекрашивает цвет молча.
        assertThat(body.color).isEqualTo("#E5AD00")
        // Успех — повод перечитать снапшот: там появится настоящий id тега.
        assertThat(api.calls).isEqualTo(1)
        assertThat(store.stored).isNotNull()
    }

    @Test
    fun `createTag с пустым или пробельным именем не идёт в API`() = runTest {
        val empty = repository.createTag("", "#e5ad00")
        val blank = repository.createTag("   ", "#e5ad00")

        assertThat(failureOf(empty)).isEqualTo(ApiFailure.InvalidInput)
        assertThat(failureOf(blank)).isEqualTo(ApiFailure.InvalidInput)
        assertThat(api.lastCreatedTag).isNull()
        assertThat(api.calls).isEqualTo(0)
    }

    @Test
    fun `updateTag отправляет id и новое тело`() = runTest {
        val outcome = repository.updateTag(TAG_ID, " Новое ", null)

        assertThat(outcome).isEqualTo(MutationOutcome.Success)
        assertThat(api.lastUpdatedTagId).isEqualTo(TAG_ID)
        val body = api.lastUpdatedTag!!
        assertThat(body.title).isEqualTo("Новое")
        // Цвет не передан — значит, не меняем: сервер оставит старый.
        assertThat(body.color).isNull()
        assertThat(api.calls).isEqualTo(1)
    }

    @Test
    fun `updateTag с пробельным заголовком не идёт в API`() = runTest {
        val outcome = repository.updateTag(TAG_ID, "  ", "#abc")

        assertThat(failureOf(outcome)).isEqualTo(ApiFailure.InvalidInput)
        assertThat(api.lastUpdatedTag).isNull()
        assertThat(api.calls).isEqualTo(0)
    }

    @Test
    fun `deleteTag отправляет DELETE и перечитывает снапшот`() = runTest {
        val outcome = repository.deleteTag(TAG_ID)

        assertThat(outcome).isEqualTo(MutationOutcome.Success)
        assertThat(api.lastDeletedTagId).isEqualTo(TAG_ID)
        assertThat(api.calls).isEqualTo(1)
        assertThat(store.stored).isNotNull()
    }

    @Test
    fun `createCategory обрезает заголовок и сохраняет цвет как есть`() = runTest {
        val outcome = repository.createCategory(" Категория ", "#2C52FF")

        assertThat(outcome).isEqualTo(MutationOutcome.Success)
        val body = api.lastCreatedCategory!!
        assertThat(body.title).isEqualTo("Категория")
        assertThat(body.color).isEqualTo("#2C52FF")
        assertThat(api.calls).isEqualTo(1)
        assertThat(store.stored).isNotNull()
    }

    @Test
    fun `updateCategory отправляет id и тело`() = runTest {
        val outcome = repository.updateCategory(CATEGORY_ID, "Новое имя", "#abc")

        assertThat(outcome).isEqualTo(MutationOutcome.Success)
        assertThat(api.lastUpdatedCategoryId).isEqualTo(CATEGORY_ID)
        val body = api.lastUpdatedCategory!!
        assertThat(body.title).isEqualTo("Новое имя")
        assertThat(body.color).isEqualTo("#abc")
        assertThat(api.calls).isEqualTo(1)
    }

    @Test
    fun `deleteCategory отправляет DELETE и перечитывает снапшот`() = runTest {
        val outcome = repository.deleteCategory(CATEGORY_ID)

        assertThat(outcome).isEqualTo(MutationOutcome.Success)
        assertThat(api.lastDeletedCategoryId).isEqualTo(CATEGORY_ID)
        assertThat(api.calls).isEqualTo(1)
        assertThat(store.stored).isNotNull()
    }

    @Test
    fun `setDefaultCategory отправляет id категории`() = runTest {
        val outcome = repository.setDefaultCategory(CATEGORY_ID)

        assertThat(outcome).isEqualTo(MutationOutcome.Success)
        assertThat(api.lastSettingsBody!!["default_category"].toString()).isEqualTo("\"$CATEGORY_ID\"")
        assertThat(api.calls).isEqualTo(1)
    }

    @Test
    fun `setDefaultCategory null очищает категорию`() = runTest {
        val outcome = repository.setDefaultCategory(null)

        assertThat(outcome).isEqualTo(MutationOutcome.Success)
        // Тело всё равно отправляется: `{"default_category": null}` — это явная команда «сбросить».
        assertThat(api.lastSettingsBody).isNotNull()
        // Именно `null`, а не отсутствие поля: сервер должен понять «снять категорию».
        assertThat(api.lastSettingsBody!!["default_category"].toString()).isEqualTo("null")
        assertThat(api.calls).isEqualTo(1)
    }

    @Test
    fun `401 даёт Unauthorized и событие в authFailures`() = runTest {
        api.createTagOutcome = { errorResponseOf(401, """{"error":"unauthorized"}""") }
        // Коллектор встаёт до мутации: SharedFlow без replay не отдаёт событие задним числом.
        val event = async(start = CoroutineStart.UNDISPATCHED) { tasks.authFailures.first() }

        val outcome = repository.createTag("Тег", "#e5ad00")

        assertThat(failureOf(outcome)).isEqualTo(ApiFailure.Unauthorized)
        assertThat(event.await()).isEqualTo(ApiFailure.Unauthorized)
        // Отказ авторизации не повод перечитывать состояние — приложение уводит на вход.
        assertThat(api.calls).isEqualTo(0)
    }

    @Test
    fun `IOException даёт сетевую ошибку`() = runTest {
        val boom = IOException("сеть недоступна")
        api.deleteTagOutcome = { throw boom }

        val failure = failureOf(repository.deleteTag(TAG_ID))

        assertThat(failure).isInstanceOf(ApiFailure.Network::class.java)
        assertThat((failure as ApiFailure.Network).cause).isSameInstanceAs(boom)
        assertThat(api.calls).isEqualTo(0)
    }

    @Test
    fun `500 даёт Http с кодом и текстом ошибки`() = runTest {
        api.updateTagOutcome = { errorResponseOf(500, """{"error":"boom"}""") }

        val failure = failureOf(repository.updateTag(TAG_ID, "Новое", "#abc"))

        assertThat(failure).isInstanceOf(ApiFailure.Http::class.java)
        val http = failure as ApiFailure.Http
        assertThat(http.code).isEqualTo(500)
        assertThat(http.serverError).isEqualTo("boom")
        assertThat(api.calls).isEqualTo(0)
    }

    /** Достаёт причину отказа: у `MutationOutcome.Failed` это единственное полезное поле. */
    private fun failureOf(outcome: MutationOutcome): ApiFailure {
        assertThat(outcome).isInstanceOf(MutationOutcome.Failed::class.java)
        return (outcome as MutationOutcome.Failed).failure
    }

    private companion object {
        const val TAG_ID = "tag-1"
        const val CATEGORY_ID = "cat-1"
    }
}
