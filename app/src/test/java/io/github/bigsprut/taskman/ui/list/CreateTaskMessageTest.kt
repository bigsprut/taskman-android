package io.github.bigsprut.taskman.ui.list

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.google.common.truth.Truth.assertThat
import io.github.bigsprut.taskman.data.attachments.AttachmentDownloader
import io.github.bigsprut.taskman.data.attachments.AttachmentRepository
import io.github.bigsprut.taskman.data.attachments.ShareInbox
import io.github.bigsprut.taskman.data.remote.ApiFailure
import io.github.bigsprut.taskman.data.remote.ConnectionHolder
import io.github.bigsprut.taskman.data.repo.OfflineMutationQueue
import io.github.bigsprut.taskman.data.repo.TaskMutationRepository
import io.github.bigsprut.taskman.data.repo.TaskRepository
import io.github.bigsprut.taskman.data.settings.SettingsStore
import io.github.bigsprut.taskman.data.settings.TokenCodec
import io.github.bigsprut.taskman.testutil.FakeLocalFileSource
import io.github.bigsprut.taskman.testutil.FakeLocalTaskWriter
import io.github.bigsprut.taskman.testutil.FakePendingMutationDao
import io.github.bigsprut.taskman.testutil.FakeStateStore
import io.github.bigsprut.taskman.testutil.FakeTaskmanApi
import io.github.bigsprut.taskman.testutil.errorResponseOf
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import org.junit.Test

/**
 * Создание задачи из списка глазами ViewModel (§5.3-Ф2: кнопка «+»).
 *
 * Репозиторий уже покрыт `CreateTaskTest`; здесь проверяется последний шаг — какое сообщение
 * увидит пользователь. Молчание при ошибке недопустимо: человек нажал «+», ввёл название и
 * должен понять, создалась задача или нет. Пустое название, наоборот, не считается ошибкой —
 * ViewModel отсекает его до похода в сеть.
 *
 * Фикстура — как в `TaskEditorViewModelTest`: полный набор подставных зависимостей, чтобы
 * ViewModel собирался без Android-контекста и Room.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CreateTaskMessageTest {

    private val api = FakeTaskmanApi()
    private val store = FakeStateStore()
    private val tasks = TaskRepository(api = api, stateStore = store)
    private val queue = OfflineMutationQueue(FakePendingMutationDao())
    private val mutations = TaskMutationRepository(
        api = api,
        reader = store,
        tasks = tasks,
        queue = queue,
        writer = FakeLocalTaskWriter(),
    )

    /**
     * Создаётся внутри [listTest], после подмены `Dispatchers.Main`: `viewModelScope`
     * захватывает Main-диспетчер при конструировании.
     */
    private lateinit var viewModel: TaskListViewModel

    @Test
    fun `createTask при успехе сообщает о создании задачи`() = listTest { messages ->
        viewModel.createTask(" Новая ")
        advanceUntilIdle()

        assertThat(messages).containsExactly(ListMessage.TaskCreated)
        // Сообщение — следствие реального запроса, а не «успех ради галочки».
        assertThat(api.upsertCalls).isEqualTo(1)
    }

    @Test
    fun `createTask при ошибке сервера отдаёт причину`() = listTest { messages ->
        api.upsertOutcome = { errorResponseOf(500, """{"error":"boom"}""") }

        viewModel.createTask("Новая")
        advanceUntilIdle()

        // Пользователь должен увидеть точную причину, а не «что-то пошло не так».
        val message = messages.single()
        assertThat(message).isInstanceOf(ListMessage.CreateTaskFailed::class.java)
        val failure = (message as ListMessage.CreateTaskFailed).failure
        assertThat(failure).isInstanceOf(ApiFailure.Http::class.java)
        assertThat((failure as ApiFailure.Http).code).isEqualTo(500)
    }

    @Test
    fun `createTask без сети тоже доходит сообщением, а не молчанием`() = listTest { messages ->
        api.upsertOutcome = { throw IOException("нет сети") }

        viewModel.createTask("Новая")
        advanceUntilIdle()

        val message = messages.single()
        assertThat(message).isInstanceOf(ListMessage.CreateTaskFailed::class.java)
        assertThat((message as ListMessage.CreateTaskFailed).failure)
            .isInstanceOf(ApiFailure.Network::class.java)
        // Создание не буферизуется: id нет, а значит и очереди для него тоже.
        assertThat(queue.pending()).isEmpty()
    }

    @Test
    fun `createTask с пробельным названием не беспокоит сервер и не пугает пользователя`() =
        listTest { messages ->
            viewModel.createTask("   ")
            advanceUntilIdle()

            // Пустое название отсекается до репозитория: ни запроса, ни сообщения об ошибке.
            assertThat(api.upsertCalls).isEqualTo(0)
            assertThat(messages).isEmpty()
        }

    /** Запускает тест с подменённым Main и собранным сообщением коллектором сообщений списка. */
    private fun listTest(body: suspend TestScope.(MutableList<ListMessage>) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        viewModel = newViewModel()
        val messages = mutableListOf<ListMessage>()
        // Подписка встаёт до действия: SharedFlow без replay не отдаёт сообщения задним числом.
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.messages.collect { messages += it }
        }
        try {
            body(messages)
        } finally {
            // Короткое затухание `stateIn` (`WhileSubscribed`) живёт после тела теста; гасим
            // его, пока Main ещё подменён, иначе `resetMain` уронит отложенную доставку.
            viewModel.viewModelScope.cancel()
            advanceUntilIdle()
            Dispatchers.resetMain()
        }
    }

    private fun newViewModel(): TaskListViewModel {
        val settings = SettingsStore(
            dataStore = FakePreferencesDataStore(),
            tokenCodec = FakeTokenCodec,
            defaultBaseUrl = "http://localhost:8443",
        )
        return TaskListViewModel(
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

/** Шифрование не нужно: создание задачи не читает токен. */
private object FakeTokenCodec : TokenCodec {

    override fun encrypt(plainText: String): String = "plain:$plainText"

    override fun decrypt(cipherText: String): String? = cipherText.removePrefix("plain:")
}
