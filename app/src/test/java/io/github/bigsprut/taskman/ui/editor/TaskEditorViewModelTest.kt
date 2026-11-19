package io.github.bigsprut.taskman.ui.editor

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import io.github.bigsprut.taskman.data.remote.ApiFailure
import io.github.bigsprut.taskman.data.remote.TaskmanJson
import io.github.bigsprut.taskman.data.attachments.AttachmentDownloader
import io.github.bigsprut.taskman.data.attachments.AttachmentRepository
import io.github.bigsprut.taskman.data.remote.ConnectionHolder
import io.github.bigsprut.taskman.data.repo.TaskMutationRepository
import io.github.bigsprut.taskman.data.repo.TaskRepository
import io.github.bigsprut.taskman.domain.TaskDraft
import io.github.bigsprut.taskman.domain.TaskStatus
import io.github.bigsprut.taskman.testutil.FakeStateStore
import io.github.bigsprut.taskman.testutil.FakeLocalFileSource
import io.github.bigsprut.taskman.testutil.FakeTaskmanApi
import io.github.bigsprut.taskman.testutil.errorResponseOf
import io.github.bigsprut.taskman.testutil.taskEntity
import java.io.File
import java.io.IOException
import okhttp3.OkHttpClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Test

/**
 * Редактор задачи (Ф2, ТЗ §5.3-Ф2, §9-Ф2).
 *
 * Проверяется поведение, а не реализация: какой черновик открывается, что считается правкой,
 * что уходит на сервер при закрытии листа и что остаётся у пользователя при ошибке.
 *
 * Главный тест фазы — «правка названия не теряется при добавлении подзадачи» (§9-Ф2): действие
 * с подзадачей перечитывает состояние с сервера, и черновик родителя не должен при этом
 * пересобраться из снапшота, иначе несохранённые поля пропадут молча.
 *
 * Второй механизм — `SavedStateHandle`: черновик сериализуется туда на каждое изменение и
 * восстанавливается после убивания процесса. Проверяется и запись, и восстановление.
 *
 * `viewModelScope` живёт на `Dispatchers.Main`, поэтому планировщиком для VM служит тот же
 * `testScheduler`, что у `runTest`: иначе `advanceUntilIdle()` не дождался бы её корутин.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TaskEditorViewModelTest {

    private val api = FakeTaskmanApi()
    private val store = FakeStateStore()
    private val tasks = TaskRepository(api = api, stateStore = store)
    private val mutations = TaskMutationRepository(api = api, reader = store, tasks = tasks)

    /** Состояние экрана, которое переживает поворот и убивание процесса. */
    private val savedState = SavedStateHandle()
private val attachmentsRepo = AttachmentRepository(
    api = api,
    store = store,
    files = FakeLocalFileSource(),
    tasks = tasks,
    connection = ConnectionHolder(),
    downloader = AttachmentDownloader(OkHttpClient(), File(System.getProperty("java.io.tmpdir"))),
)

    /** Задача в локальном снапшоте — тот же объект, из которого собирается payload (§4). */
    private val entity = taskEntity(
        id = TASK_ID,
        listId = "list-1",
        title = "Помыть посуду",
        content = "с вечера",
        status = "created",
        createdAt = 1_700_000_000.0,
        updatedAt = SERVER_VERSION,
    )

    @Test
    fun `open подтягивает черновик из снапшота`() = editorTest {
        store.putTask(entity, tagIds = listOf("tag-a"))
        val vm = newViewModel()

        vm.open(TASK_ID)
        advanceUntilIdle()

        val state = vm.state.value
        assertThat(state.isOpen).isTrue()
        assertThat(state.isLoading).isFalse()
        assertThat(state.taskId).isEqualTo(TASK_ID)
        // Только что прочитанный снапшот — не правка: закрытие листа не должно слать upsert.
        assertThat(state.isDirty).isFalse()

        val draft = state.draft
        assertThat(draft).isNotNull()
        assertThat(draft!!.id).isEqualTo(TASK_ID)
        assertThat(draft.listId).isEqualTo("list-1")
        assertThat(draft.title).isEqualTo("Помыть посуду")
        assertThat(draft.content).isEqualTo("с вечера")
        assertThat(draft.status).isEqualTo(TaskStatus.CREATED)
        assertThat(draft.tagIds).containsExactly("tag-a")
        assertThat(draft.createdAt).isEqualTo(1_700_000_000.0)
        assertThat(draft.updatedAt).isEqualTo(SERVER_VERSION)
        // Дату статуса редактор не выдумывает: её выставляет сервер при смене статуса.
        assertThat(draft.statusDateTs).isNull()
        assertThat(savedState.get<String>(DRAFT_JSON_KEY)).isNull()
    }

    @Test
    fun `setTitle помечает черновик изменённым и кладёт JSON в SavedStateHandle`() = editorTest {
        store.putTask(entity)
        val vm = newViewModel()
        vm.open(TASK_ID)
        advanceUntilIdle()

        vm.setTitle("Купить молоко")

        assertThat(vm.state.value.isDirty).isTrue()
        assertThat(vm.state.value.draft!!.title).isEqualTo("Купить молоко")

        // Черновик пишется в handle на каждое изменение: иначе убивание процесса потеряет правку.
        val json = savedState.get<String>(DRAFT_JSON_KEY)
        assertThat(json).isNotNull()
        val persisted = TaskmanJson.decodeFromString(TaskDraft.serializer(), json!!)
        assertThat(persisted.id).isEqualTo(TASK_ID)
        assertThat(persisted.title).isEqualTo("Купить молоко")
        assertThat(savedState.get<String>(DRAFT_TASK_ID_KEY)).isEqualTo(TASK_ID)
    }

    @Test
    fun `правка названия не теряется при добавлении подзадачи`() = editorTest {
        // Регресс из §9-Ф2: подзадача — отдельная мутация на сервер, после успеха состояние
        // перечитывается. Если бы черновик родителя пересобирался из снапшота, правка бы исчезла.
        store.putTask(entity)
        val vm = newViewModel()
        vm.open(TASK_ID)
        advanceUntilIdle()

        vm.setTitle("черновик")
        vm.addSubtask("Подзадача")
        advanceUntilIdle()

        val state = vm.state.value
        assertThat(state.draft!!.title).isEqualTo("черновик")
        assertThat(state.isDirty).isTrue()

        // Подзадача уходит отдельным payload: правка родителя не «утекает» вместе с ней.
        assertThat(api.upsertCalls).isEqualTo(1)
        val payload = api.lastUpserted!!.single()
        assertThat(payload.title).isEqualTo("Подзадача")
        assertThat(payload.parentId).isEqualTo(TASK_ID)
        assertThat(payload.id).isNull()
        // Мутация успешна → был рефетч состояния; именно он и мог бы затереть черновик.
        assertThat(api.calls).isEqualTo(1)
        assertThat(store.stored).isNotNull()
    }

    @Test
    fun `renameSubtask отправляет новое название и не трогает черновик родителя`() = editorTest {
        store.putTask(entity)
        store.putTask(taskEntity(id = CHILD_ID, parentId = TASK_ID, title = "Старое имя"))
        val vm = newViewModel()
        vm.open(TASK_ID)
        advanceUntilIdle()

        vm.setTitle("черновик")
        vm.renameSubtask(CHILD_ID, "Новое имя")
        advanceUntilIdle()

        // Переименование — UPSERT полного объекта подзадачи, а не только названия (§4).
        val payload = api.lastUpserted!!.single()
        assertThat(payload.id).isEqualTo(CHILD_ID)
        assertThat(payload.parentId).isEqualTo(TASK_ID)
        assertThat(payload.title).isEqualTo("Новое имя")

        // Черновик родителя — по-прежнему правка пользователя, мутация его не касается.
        assertThat(vm.state.value.draft!!.title).isEqualTo("черновик")
        assertThat(vm.state.value.isDirty).isTrue()
    }

    @Test
    fun `close неизменённого черновика не отправляет upsert`() = editorTest {
        store.putTask(entity)
        val vm = newViewModel()
        vm.open(TASK_ID)
        advanceUntilIdle()

        vm.close()
        advanceUntilIdle()

        assertThat(vm.state.value.isOpen).isFalse()
        // Ничего не правили — нечего сохранять: лишний POST только сдвинул бы updated_at.
        assertThat(api.upsertCalls).isEqualTo(0)
    }

    @Test
    fun `close изменённого черновика сохраняет правку и очищает сохранённый черновик`() = editorTest {
        store.putTask(entity)
        val vm = newViewModel()
        vm.open(TASK_ID)
        advanceUntilIdle()

        vm.setTitle("Купить молоко")
        vm.close()
        advanceUntilIdle()

        assertThat(api.upsertCalls).isEqualTo(1)
        val payload = api.lastUpserted!!.single()
        assertThat(payload.id).isEqualTo(TASK_ID)
        assertThat(payload.title).isEqualTo("Купить молоко")
        // В payload — версия из снапшота: по ней сервер проверит конфликт (CAS, §4).
        assertThat(payload.updatedAt).isEqualTo(SERVER_VERSION)
        assertThat(payload.statusDate).isNull()

        assertThat(vm.state.value.isOpen).isFalse()
        // Сохранённый черновик больше не нужен: при следующем открытии данные придут из снапшота.
        assertThat(savedState.get<String>(DRAFT_JSON_KEY)).isNull()
        assertThat(savedState.get<String>(DRAFT_TASK_ID_KEY)).isNull()
    }

    @Test
    fun `close при 409 оставляет лист открытым и сообщает о конфликте`() = editorTest {
        store.putTask(entity)
        val vm = newViewModel()
        vm.open(TASK_ID)
        advanceUntilIdle()
        vm.setTitle("Купить молоко")

        // Подписка встаёт до действия: SharedFlow без replay не отдаёт сообщение задним числом.
        val message = nextMessage(vm)
        api.upsertOutcome = {
            errorResponseOf(409, """{"error":"conflict","actual_updated_at":1765843500.0}""")
        }

        vm.close()
        advanceUntilIdle()

        assertThat(message.await()).isEqualTo(EditorMessage.SaveConflict)
        assertThat(api.upsertCalls).isEqualTo(1)
        // Конфликт не лечится повтором и не должен молча затирать чужую правку: лист открыт.
        assertThat(vm.state.value.isOpen).isTrue()
        assertThat(vm.state.value.isDirty).isTrue()
        assertThat(vm.state.value.draft!!.title).isEqualTo("Купить молоко")
        // После 409 состояние перечитывается, иначе UI показал бы устаревшую версию (§4).
        assertThat(api.calls).isEqualTo(1)
    }

    @Test
    fun `close при сетевой ошибке оставляет лист открытым и не теряет правки`() = editorTest {
        store.putTask(entity)
        val vm = newViewModel()
        vm.open(TASK_ID)
        advanceUntilIdle()
        vm.setTitle("Купить молоко")

        val message = nextMessage(vm)
        api.upsertOutcome = { throw IOException("нет сети") }

        vm.close()
        advanceUntilIdle()

        val received = message.await()
        assertThat(received).isInstanceOf(EditorMessage.SaveFailed::class.java)
        assertThat((received as EditorMessage.SaveFailed).failure)
            .isInstanceOf(ApiFailure.Network::class.java)

        assertThat(vm.state.value.isOpen).isTrue()
        assertThat(vm.state.value.isDirty).isTrue()
        assertThat(vm.state.value.draft!!.title).isEqualTo("Купить молоко")
        // Правка остаётся и в handle: она переживёт и закрытие ошибкой, и убивание процесса.
        assertThat(savedState.get<String>(DRAFT_JSON_KEY)).isNotNull()
        // Сеть отвалилась до ответа сервера — рефетча не было.
        assertThat(api.calls).isEqualTo(0)
    }

    @Test
    fun `черновик восстанавливается после убивания процесса`() = editorTest {
        store.putTask(entity)
        val first = newViewModel()
        first.open(TASK_ID)
        advanceUntilIdle()
        first.setTitle("правка до смерти процесса")

        // Симуляция восстановления: система отдаёт новый ViewModel с теми же ключами и
        // значениями, что успела сохранить первая.
        val restoredHandle = SavedStateHandle(
            savedState.keys().associateWith { key -> savedState.get<Any?>(key) },
        )
        val second = newViewModel(restoredHandle)
        second.open(TASK_ID)
        advanceUntilIdle()

        val state = second.state.value
        assertThat(state.isOpen).isTrue()
        assertThat(state.draft!!.title).isEqualTo("правка до смерти процесса")
        // Восстановленный черновик отличается от снапшота — при закрытии его нужно сохранить.
        assertThat(state.isDirty).isTrue()
    }

    @Test
    fun `setStatus не навязывает дату статуса, если её не выбирали`() = editorTest {
        store.putTask(entity)
        val vm = newViewModel()
        vm.open(TASK_ID)
        advanceUntilIdle()

        vm.setStatus(TaskStatus.COMPLETED)

        assertThat(vm.state.value.draft!!.status).isEqualTo(TaskStatus.COMPLETED)
        // Дату смены статуса проставляет сервер; клиент выдумывать её не должен (§4).
        assertThat(vm.state.value.draft!!.statusDateTs).isNull()
    }

    @Test
    fun `выбранная дата статуса сохраняется при смене статуса`() = editorTest {
        store.putTask(entity)
        val vm = newViewModel()
        vm.open(TASK_ID)
        advanceUntilIdle()

        // Обычный порядок правки «задним числом»: сначала дата, потом статус.
        vm.setStatusDate(CHOSEN_STATUS_DATE)
        assertThat(vm.state.value.draft!!.statusDateTs).isEqualTo(CHOSEN_STATUS_DATE)

        vm.setStatus(TaskStatus.COMPLETED)

        assertThat(vm.state.value.draft!!.status).isEqualTo(TaskStatus.COMPLETED)
        // Введённую дату смена статуса не стирает: иначе пользователь терял бы ввод молча.
        assertThat(vm.state.value.draft!!.statusDateTs).isEqualTo(CHOSEN_STATUS_DATE)

        // А явный отказ от даты убирает поле из payload совсем.
        vm.setStatusDate(null)
        assertThat(vm.state.value.draft!!.statusDateTs).isNull()
    }

    @Test
    fun `toggleTag добавляет и убирает тег`() = editorTest {
        store.putTask(entity, tagIds = listOf("tag-b"))
        val vm = newViewModel()
        vm.open(TASK_ID)
        advanceUntilIdle()

        vm.toggleTag("tag-a")
        assertThat(vm.state.value.draft!!.tagIds).containsExactly("tag-a", "tag-b")

        vm.toggleTag("tag-a")
        assertThat(vm.state.value.draft!!.tagIds).containsExactly("tag-b")

        vm.toggleTag("tag-b")
        assertThat(vm.state.value.draft!!.tagIds).isEmpty()
    }

    @Test
    fun `setCategory и setEmployee снимают выбор повторным вызовом`() = editorTest {
        store.putTask(entity)
        val vm = newViewModel()
        vm.open(TASK_ID)
        advanceUntilIdle()

        vm.setCategory("cat-1")
        assertThat(vm.state.value.draft!!.categoryId).isEqualTo("cat-1")
        vm.setCategory("cat-1")
        assertThat(vm.state.value.draft!!.categoryId).isNull()

        // Сотрудник в контракте — имя строкой (`task.source`), поэтому выключатель тоже по имени.
        vm.setEmployee("Иван")
        assertThat(vm.state.value.draft!!.source).isEqualTo("Иван")
        vm.setEmployee("Иван")
        assertThat(vm.state.value.draft!!.source).isNull()
    }

    /** ViewModel с подставными зависимостями; [handle] можно подменить при восстановлении. */
    private fun newViewModel(handle: SavedStateHandle = savedState) = TaskEditorViewModel(
        tasks = tasks,
        mutations = mutations,
        attachmentsRepo = attachmentsRepo,
        store = store,
        savedState = handle,
    )

    /**
     * Запускает тест с подменённым `Dispatchers.Main`.
     *
     * Без `setMain` `viewModelScope` ушёл бы на `Dispatchers.Default`, и `advanceUntilIdle()`
     * не дождался бы ни открытия листа, ни сохранения — тесты стали бы плавающими.
     */
    private fun editorTest(body: suspend TestScope.() -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            body()
        } finally {
            Dispatchers.resetMain()
        }
    }

    /**
     * Ловит следующее сообщение редактора.
     *
     * `messages` — `SharedFlow` без replay, поэтому подписка обязана встать до действия:
     * сообщение, отправленное раньше, до слушателя не дойдёт.
     */
    private fun TestScope.nextMessage(vm: TaskEditorViewModel): CompletableDeferred<EditorMessage> {
        val received = CompletableDeferred<EditorMessage>()
        backgroundScope.launch { received.complete(vm.messages.first()) }
        advanceUntilIdle()
        return received
    }

    private companion object {
        const val TASK_ID = "task-1"
        const val CHILD_ID = "task-2"
        const val SERVER_VERSION = 1_765_843_200.0

        /** Дата статуса, выбранная пользователем «задним числом» (unix-секунды). */
        const val CHOSEN_STATUS_DATE = 1_795_120_000.0

        /**
         * Ключи `SavedStateHandle` — контракт с `TaskEditorViewModel` (там они в private
         * companion). Литералы продублированы осознанно: тест проверяет ровно тот ключ,
         * по которому черновик переживёт убивание процесса, поэтому ключ фиксируется здесь,
         * а не выводится из кода ViewModel.
         */
        const val DRAFT_JSON_KEY = "editor_draft_json"
        const val DRAFT_TASK_ID_KEY = "editor_draft_task_id"
    }
}
