package io.github.bigsprut.taskman.ui.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.viewModelScope
import com.google.common.truth.Truth.assertThat
import io.github.bigsprut.taskman.data.export.ExportSnapshot
import io.github.bigsprut.taskman.data.remote.ApiFailure
import io.github.bigsprut.taskman.data.repo.CatalogRepository
import io.github.bigsprut.taskman.data.repo.EmployeeRepository
import io.github.bigsprut.taskman.data.repo.TaskRepository
import io.github.bigsprut.taskman.data.settings.SettingsStore
import io.github.bigsprut.taskman.data.settings.TokenCodec
import io.github.bigsprut.taskman.domain.CatalogColor
import io.github.bigsprut.taskman.domain.Category
import io.github.bigsprut.taskman.domain.Employee
import io.github.bigsprut.taskman.domain.Tag
import io.github.bigsprut.taskman.domain.TaskSortOrder
import io.github.bigsprut.taskman.testutil.FakeStateStore
import io.github.bigsprut.taskman.testutil.FakeTaskmanApi
import io.github.bigsprut.taskman.testutil.errorResponseOf
import io.github.bigsprut.taskman.testutil.taskEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.Test

/**
 * Экран настроек (§5.3-Ф5): теги и категории с цветами, категория по умолчанию, порядок
 * сортировки и снапшот для экспорта.
 *
 * `SettingsStore` — конкретный класс, но его зависимость `DataStore` — интерфейс: подменяем
 * хранилище на `MutableStateFlow`, а шифрование токена — заглушкой. Так проверяется весь путь
 * «ViewModel → SettingsStore → DataStore» без файловой системы и Android Keystore.
 *
 * Цвет нормализует именно ViewModel: репозиторий получает уже `#rrggbb`, поэтому проверки
 * цвета здесь — про VM, а не про сетевой слой.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val api = FakeTaskmanApi()
    private val store = FakeStateStore()
    private val tasks = TaskRepository(api = api, stateStore = store)
    private val preferences = FakePreferencesDataStore()
    private val settings = SettingsStore(
        dataStore = preferences,
        tokenCodec = FakeTokenCodec,
        defaultBaseUrl = "http://localhost:8443",
    )

    /**
     * Создаётся внутри [settingsTest], после `Dispatchers.setMain`: `viewModelScope` захватывает
     * Main-диспетчер при конструировании, и в поле до подмены он был бы не тем планировщиком.
     */
    private lateinit var viewModel: SettingsViewModel

    @Test
    fun `addTag обрезает заголовок и нормализует цвет`() = settingsTest {
        viewModel.addTag(" Тег ", "#E5AD00")
        advanceUntilIdle()

        val created = api.lastCreatedTag!!
        assertThat(created.title).isEqualTo("Тег")
        assertThat(created.color).isEqualTo("#e5ad00")
        // Успех — рефетч снапшота: UI увидит новый тег без ручного обновления.
        assertThat(api.calls).isEqualTo(1)
        assertThat(store.stored).isNotNull()
    }

    @Test
    fun `addTag с пустым именем не вызывает API`() = settingsTest {
        viewModel.addTag("   ", "#e5ad00")
        advanceUntilIdle()

        assertThat(api.lastCreatedTag).isNull()
        assertThat(api.calls).isEqualTo(0)
    }

    @Test
    fun `addTag с мусорным цветом берёт цвет по умолчанию`() = settingsTest {
        viewModel.addTag("Тег", "не-цвет")
        advanceUntilIdle()

        // Мусор не уходит на сервер: подставляем мягкий синий по умолчанию.
        assertThat(api.lastCreatedTag!!.color).isEqualTo(CatalogColor.DEFAULT)
    }

    @Test
    fun `updateTag нормализует трёхзначный цвет`() = settingsTest {
        viewModel.updateTag("tag-1", "Новое", "#abc")
        advanceUntilIdle()

        assertThat(api.lastUpdatedTagId).isEqualTo("tag-1")
        assertThat(api.lastUpdatedTag!!.title).isEqualTo("Новое")
        assertThat(api.lastUpdatedTag!!.color).isEqualTo("#aabbcc")
    }

    @Test
    fun `addCategory отправляет категорию с нормализованным цветом`() = settingsTest {
        viewModel.addCategory(" Категория ", "#2C52FF")
        advanceUntilIdle()

        assertThat(api.lastCreatedCategory!!.title).isEqualTo("Категория")
        assertThat(api.lastCreatedCategory!!.color).isEqualTo("#2c52ff")
    }

    @Test
    fun `deleteCategory удаляет категорию по id`() = settingsTest {
        viewModel.deleteCategory("cat-1")
        advanceUntilIdle()

        assertThat(api.lastDeletedCategoryId).isEqualTo("cat-1")
    }

    @Test
    fun `ошибка сервера приходит сообщением`() = settingsTest {
        // Подписка встаёт до действия: SharedFlow без replay не отдаёт сообщение задним числом.
        val message = nextMessage()
        api.createTagOutcome = { errorResponseOf(500, """{"error":"boom"}""") }

        viewModel.addTag("Тег", "#abc")
        advanceUntilIdle()

        val failed = message.await()
        assertThat(failed).isInstanceOf(SettingsMessage.Failed::class.java)
        val failure = (failed as SettingsMessage.Failed).failure
        assertThat(failure).isInstanceOf(ApiFailure.Http::class.java)
        assertThat((failure as ApiFailure.Http).code).isEqualTo(500)
    }

    @Test
    fun `loadExportSnapshot отдаёт снапшот из хранилища`() = settingsTest {
        val snapshot = ExportSnapshot(tasks = listOf(taskEntity(id = "task-1", title = "Задача")))
        store.exportSnapshot = snapshot
        var received: ExportSnapshot? = null

        viewModel.loadExportSnapshot { received = it }
        advanceUntilIdle()

        assertThat(received).isEqualTo(snapshot)
    }

    @Test
    fun `setSortOrder сохраняет порядок в локальных настройках`() = runBlocking {
        // `SettingsStore.saveSortOrder` уходит на Dispatchers.IO, а виртуальное время runTest
        // такую работу не ждёт: ждём реальное завершение записи, иначе тест стал бы плавающим.
        Dispatchers.setMain(Dispatchers.Unconfined)
        val local = newViewModel()
        try {
            local.setSortOrder(TaskSortOrder.TITLE)

            val saved = withTimeout(SORT_ORDER_TIMEOUT_MILLIS) {
                preferences.data.first { it[SORT_ORDER_KEY] == TaskSortOrder.TITLE.key }
            }
            assertThat(saved[SORT_ORDER_KEY]).isEqualTo(TaskSortOrder.TITLE.key)
            assertThat(local.sortOrder.first { it == TaskSortOrder.TITLE })
                .isEqualTo(TaskSortOrder.TITLE)
        } finally {
            local.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `списки и категория по умолчанию приходят из хранилища`() = settingsTest {
        store.employeesFlow.value = listOf(Employee(id = "emp-1", name = "Иван"))
        store.tagsFlow.value = listOf(Tag(id = "tag-1", title = "Тег 1", color = "#e5ad00"))
        store.categoriesFlow.value = listOf(Category(id = "cat-1", title = "Категория 1", color = "#f63c00"))
        store.defaultCategoryFlow.value = "cat-1"

        assertThat(viewModel.employeeList.first { it.isNotEmpty() }).hasSize(1)
        assertThat(viewModel.tagList.first { it.isNotEmpty() })
            .containsExactly(Tag(id = "tag-1", title = "Тег 1", color = "#e5ad00"))
        assertThat(viewModel.categoryList.first { it.isNotEmpty() }).hasSize(1)
        assertThat(viewModel.defaultCategoryId.first { it != null }).isEqualTo("cat-1")
    }

    private fun newViewModel(): SettingsViewModel = SettingsViewModel(
        employees = EmployeeRepository(api = api, tasks = tasks),
        catalog = CatalogRepository(api = api, tasks = tasks),
        store = store,
        settings = settings,
    )

    /**
     * Запускает тест с подменённым `Dispatchers.Main`: `viewModelScope` живёт на Main, и без
     * `setMain` `advanceUntilIdle()` не дождался бы корутин ViewModel.
     */
    private fun settingsTest(body: suspend TestScope.() -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        viewModel = newViewModel()
        try {
            body()
        } finally {
            // Короткое затухание `stateIn` (`WhileSubscribed`) живёт после тела теста; гасим
            // его, пока Main ещё подменён, иначе `resetMain` уронит отложенную доставку.
            viewModel.viewModelScope.cancel()
            advanceUntilIdle()
            Dispatchers.resetMain()
        }
    }

    /** Ловит следующее сообщение экрана настроек. */
    private fun TestScope.nextMessage(): CompletableDeferred<SettingsMessage> {
        val received = CompletableDeferred<SettingsMessage>()
        backgroundScope.launch { received.complete(viewModel.messages.first()) }
        advanceUntilIdle()
        return received
    }

    /** Ключ тот же, что `SettingsStore.Keys.SORT_ORDER`; литерал зафиксирован осознанно. */
    private companion object {
        val SORT_ORDER_KEY = stringPreferencesKey("sort_order")

        /** Запись в DataStore ждёт реальный `Dispatchers.IO`, поэтому таймаут реальный. */
        const val SORT_ORDER_TIMEOUT_MILLIS = 5_000L
    }
}

/** Подставной DataStore: настройки живут в памяти, без файлов и Android-контекста. */
private class FakePreferencesDataStore(
    initial: Preferences = emptyPreferences(),
) : DataStore<Preferences> {

    private val state = MutableStateFlow(initial)

    override val data: Flow<Preferences> = state

    override suspend fun updateData(
        transform: suspend (t: Preferences) -> Preferences,
    ): Preferences {
        val updated = transform(state.value)
        state.value = updated
        return updated
    }
}

/** Шифрование не нужно: тест проверяет сортировку, а она хранится открытым текстом. */
private object FakeTokenCodec : TokenCodec {

    override fun encrypt(plainText: String): String = "plain:$plainText"

    override fun decrypt(cipherText: String): String? = cipherText.removePrefix("plain:")
}
