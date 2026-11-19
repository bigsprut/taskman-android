package io.github.bigsprut.taskman.ui.list

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.google.common.truth.Truth.assertThat
import io.github.bigsprut.taskman.data.attachments.AttachmentDownloader
import io.github.bigsprut.taskman.data.attachments.AttachmentRepository
import io.github.bigsprut.taskman.data.attachments.ShareInbox
import io.github.bigsprut.taskman.data.remote.ConnectionHolder
import io.github.bigsprut.taskman.data.repo.OfflineMutationQueue
import io.github.bigsprut.taskman.data.repo.TaskMutationRepository
import io.github.bigsprut.taskman.data.repo.TaskRepository
import io.github.bigsprut.taskman.domain.StatusFilter
import io.github.bigsprut.taskman.testutil.FakeLocalFileSource
import io.github.bigsprut.taskman.testutil.FakeLocalTaskWriter
import io.github.bigsprut.taskman.testutil.FakePendingMutationDao
import io.github.bigsprut.taskman.testutil.FakeStateStore
import io.github.bigsprut.taskman.testutil.FakeTaskmanApi
import io.github.bigsprut.taskman.testutil.InMemoryPreferencesDataStore
import io.github.bigsprut.taskman.testutil.settingsStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.junit.Assert.fail
import org.junit.Test

/**
 * Скрываемый блок фильтров (§5.3-Ф1, решение владельца 20.11).
 *
 * Требование из живой приёмки: пользователь свернул фильтры — и они остаются свёрнутыми после
 * перезапуска приложения. Поэтому флаг живёт не в `rememberSaveable`, а в DataStore: здесь
 * проверяется весь путь «нажал → ViewModel → SettingsStore → DataStore → новый экземпляр
 * экрана снова видит свёрнутый блок».
 *
 * `filtersHidden` — это `stateIn(WhileSubscribed)`: без подписчика поток не работает, поэтому
 * в тесте, как и в UI, на него сначала подписываются. Сохранение уходит на `Dispatchers.IO`,
 * а виртуальное время `runTest` такую работу не ждёт — отсюда помощник [awaitFiltersHidden]
 * с реальным таймаутом (тот же приём, что у `SettingsViewModelTest` для `sort_order`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FilterCollapseTest {

    private val api = FakeTaskmanApi()
    private val store = FakeStateStore()
    private val tasks = TaskRepository(api = api, stateStore = store)
    private val mutations = TaskMutationRepository(
        api = api,
        reader = store,
        tasks = tasks,
        queue = OfflineMutationQueue(FakePendingMutationDao()),
        writer = FakeLocalTaskWriter(),
    )

    /** Одно хранилище настроек на все «запуски» экрана в тесте. */
    private val preferences = InMemoryPreferencesDataStore()
    private val settings = settingsStore(preferences)

    /**
     * Создаётся внутри [collapseTest], после подмены `Dispatchers.Main`: `viewModelScope`
     * захватывает Main-диспетчер при конструировании.
     */
    private lateinit var viewModel: TaskListViewModel

    @Test
    fun `по умолчанию блок фильтров развёрнут`() = collapseTest {
        assertThat(viewModel.filtersHidden.value).isFalse()
        // Умолчание приходит из кода, а не из сохранённого значения: ключа ещё нет.
        assertThat(settings.observeFiltersHidden().first()).isFalse()
        assertThat(preferences.data.first()[FILTERS_HIDDEN_KEY]).isNull()
    }

    @Test
    fun `toggleFilters сворачивает блок и сохраняет флаг в DataStore`() = collapseTest {
        viewModel.toggleFilters()
        awaitFiltersHidden(true)

        assertThat(viewModel.filtersHidden.value).isTrue()
        // Персистентность проверяется чтением хранилища, а не полем ViewModel.
        assertThat(settings.observeFiltersHidden().first()).isTrue()
        assertThat(preferences.data.first()[FILTERS_HIDDEN_KEY]).isTrue()
    }

    @Test
    fun `повторный toggleFilters разворачивает блок и снимает флаг`() = collapseTest {
        viewModel.toggleFilters()
        awaitFiltersHidden(true)

        viewModel.toggleFilters()
        awaitFiltersHidden(false)

        assertThat(viewModel.filtersHidden.value).isFalse()
        // Не «удалили ключ», а сохранили false: следующий запуск тоже увидит развёрнутый блок.
        assertThat(settings.observeFiltersHidden().first()).isFalse()
        assertThat(preferences.data.first()[FILTERS_HIDDEN_KEY]).isFalse()
    }

    @Test
    fun `свёрнутость переживает перезапуск экрана`() = collapseTest {
        viewModel.toggleFilters()
        awaitFiltersHidden(true)

        // «Второй запуск»: новый экземпляр ViewModel на тех же SettingsStore и DataStore.
        val restarted = newViewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            restarted.filtersHidden.collect {}
        }
        awaitFiltersHidden(true, restarted)

        assertThat(restarted.filtersHidden.value).isTrue()
        restarted.viewModelScope.cancel()
        advanceUntilIdle()
    }

    @Test
    fun `флаг хранится в DataStore, а не в памяти экрана`() = collapseTest {
        viewModel.toggleFilters()
        awaitFiltersHidden(true)

        // Ещё один запуск — уже без общего экземпляра SettingsStore: читаем тот же DataStore.
        val restartedSettings = settingsStore(preferences)

        assertThat(restartedSettings.observeFiltersHidden().first()).isTrue()
    }

    @Test
    fun `свёрнутый блок не сбрасывает выбранные фильтры`() = collapseTest {
        viewModel.setStatusFilter(StatusFilter.COMPLETED)
        viewModel.toggleCategory("cat-a")
        // Статус + категория — два условия для строки «Отборы · 2».
        assertThat(viewModel.filter.value.activeCount()).isEqualTo(2)

        viewModel.toggleFilters()
        awaitFiltersHidden(true)

        // Сворачивание — только видимость блока: отбор и его счётчик остаются прежними.
        assertThat(viewModel.filter.value.status).isEqualTo(StatusFilter.COMPLETED)
        assertThat(viewModel.filter.value.categoryIds).containsExactly("cat-a")
        assertThat(viewModel.filter.value.activeCount()).isEqualTo(2)
    }

    /**
     * Запускает тест с подменённым Main и подпиской на `filtersHidden`, как у Compose-экрана:
     * без подписчика `stateIn(WhileSubscribed)` ленив и поле никогда не обновится.
     */
    private fun collapseTest(body: suspend TestScope.() -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        viewModel = newViewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.filtersHidden.collect {}
        }
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

    /**
     * Ждёт, пока флаг станет [hidden] и во ViewModel, и в DataStore.
     *
     * Запись идёт через `Dispatchers.IO`, а `advanceUntilIdle()` виртуального времени такую
     * работу не ждёт: крутим Main-планировщик и коротко спим реальным временем, пока не
     * совпадут оба источника. Таймаут тоже реальный — иначе тест зависал бы вместо падения.
     */
    private suspend fun TestScope.awaitFiltersHidden(
        hidden: Boolean,
        vm: TaskListViewModel = viewModel,
    ) {
        val deadline = System.currentTimeMillis() + SAVE_TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            advanceUntilIdle()
            val persisted = settings.observeFiltersHidden().first()
            if (persisted == hidden && vm.filtersHidden.value == hidden) return
            withContext(Dispatchers.Default) { delay(POLL_INTERVAL_MILLIS) }
        }
        fail(
            "флаг filters_hidden=$hidden не появился в DataStore и ViewModel " +
                "за $SAVE_TIMEOUT_MILLIS мс",
        )
    }

    /** Полный набор подставных зависимостей — как в `CreateTaskMessageTest`. */
    private fun newViewModel(): TaskListViewModel = TaskListViewModel(
        repository = tasks,
        mutations = mutations,
        attachments = AttachmentRepository(
            api = api,
            store = store,
            files = FakeLocalFileSource(),
            tasks = tasks,
            connection = ConnectionHolder(),
            downloader = AttachmentDownloader(
                OkHttpClient(),
                File(System.getProperty("java.io.tmpdir")),
            ),
        ),
        shareInbox = ShareInbox(),
        settings = settings,
        store = store,
        savedState = SavedStateHandle(),
    )

    private companion object {
        /** Ключ DataStore — часть контракта, поэтому литерал зафиксирован осознанно. */
        val FILTERS_HIDDEN_KEY = booleanPreferencesKey("filters_hidden")

        /** Запись в DataStore ждёт реальный `Dispatchers.IO`, поэтому таймаут реальный. */
        const val SAVE_TIMEOUT_MILLIS = 5_000L
        const val POLL_INTERVAL_MILLIS = 5L
    }
}
