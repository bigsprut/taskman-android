package io.github.bigsprut.taskman.editor

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.bigsprut.taskman.data.attachments.AttachmentDownloader
import io.github.bigsprut.taskman.data.attachments.AttachmentRepository
import io.github.bigsprut.taskman.data.local.entity.TaskEntity
import io.github.bigsprut.taskman.data.remote.ConnectionHolder
import io.github.bigsprut.taskman.data.repo.OfflineMutationQueue
import io.github.bigsprut.taskman.data.repo.TaskMutationRepository
import io.github.bigsprut.taskman.data.repo.TaskRepository
import io.github.bigsprut.taskman.domain.Attachment
import io.github.bigsprut.taskman.ui.editor.EditorTestTags
import io.github.bigsprut.taskman.ui.editor.TaskEditorSheet
import io.github.bigsprut.taskman.ui.editor.TaskEditorViewModel
import io.github.bigsprut.taskman.ui.theme.TaskmanTheme
import java.io.File
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Ф8, ТЗ §5.2: удаление подзадач и вложений в редакторе — **только свайпом влево**
 * (`M3 SwipeToDismissBox` через общий `SwipeToDeleteRow`) с подтверждением диалогом.
 *
 * Проверяется то, что нельзя проверить JVM-тестами: настоящий жест по строке, настоящий
 * диалог подтверждения и **отсутствие видимых корзин** (их убрали из редактора).
 * Свайп по строке должен открыть диалог, «Удалить» — отправить DELETE на сервер,
 * «Отмена» — не отправлять ничего.
 *
 * Прогон: `./gradlew :app:connectedDebugAndroidTest` (эмулятор/устройство).
 */
@RunWith(AndroidJUnit4::class)
class EditorSwipeDeleteTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val api = FakeApi()
    private val store = FakeStore()
    private lateinit var writer: FakeLocalTaskWriter
    private lateinit var viewModel: TaskEditorViewModel

    @Before
    fun setUp() {
        store.putTask(parentTask())
        store.putTask(subtask())
        // Вложение — обычное, не картинка: Coil и сеть не нужны, строка рисуется по имени
        // файла (url() без ConnectionHolder даёт null, поэтому AsyncImage не подключается).
        store.putAttachments(TASK_ID, listOf(attachment()))

        val tasks = TaskRepository(api = api, stateStore = store)
        writer = FakeLocalTaskWriter()
        viewModel = TaskEditorViewModel(
            tasks = tasks,
            mutations = TaskMutationRepository(
                api = api,
                reader = store,
                tasks = tasks,
                queue = OfflineMutationQueue(FakePendingMutationDao()),
                writer = writer,
            ),
            attachmentsRepo = AttachmentRepository(
                api = api,
                store = store,
                files = FakeLocalFileSource(),
                tasks = tasks,
                connection = ConnectionHolder(),
                downloader = AttachmentDownloader(
                    client = OkHttpClient(),
                    cacheDirectory = File(System.getProperty("java.io.tmpdir")),
                ),
            ),
            store = store,
            savedState = SavedStateHandle(),
        )

        composeRule.setContent {
            TaskmanTheme(dynamicColor = false) {
                TaskEditorSheet(viewModel = viewModel)
            }
        }

        composeRule.runOnIdle { viewModel.open(TASK_ID) }
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            viewModel.state.value.isOpen && viewModel.state.value.draft != null
        }
        composeRule.onNodeWithTag(EditorTestTags.TITLE).assertExists()
    }

    @Test
    fun `подзадача_удаляется_свайпом_влево_после_подтверждения_корзины_нет`() {
        scrollTo(SUBTASK_TITLE)
        assertNoVisibleDeleteButtons()

        swipeLeftOnRow(SUBTASK_TITLE)
        assertDialogOpened(SUBTASK_DIALOG_TITLE)

        clickDialogButton(CONFIRM_LABEL)

        composeRule.waitUntil(TIMEOUT_MILLIS) { api.deleteTaskCalls.contains(SUBTASK_ID) }
        assertEquals(listOf(SUBTASK_ID), api.deleteTaskCalls)
        // Локальное удаление прошло по тому же id: строка и API не разошлись.
        assertEquals(1, writer.deletes)
    }

    @Test
    fun `отмена_в_диалоге_не_удаляет_подзадачу`() {
        scrollTo(SUBTASK_TITLE)
        swipeLeftOnRow(SUBTASK_TITLE)
        // Диалог действительно открылся — иначе «отмена» ниже ничего бы не проверяла.
        assertDialogOpened(SUBTASK_DIALOG_TITLE)

        clickDialogButton(CANCEL_LABEL)

        composeRule.waitUntil(TIMEOUT_MILLIS) { dialogNodes(SUBTASK_DIALOG_TITLE).isEmpty() }
        composeRule.onNodeWithText(SUBTASK_TITLE).assertExists()
        assertTrue(api.deleteTaskCalls.isEmpty())
        assertEquals(0, writer.deletes)
    }

    @Test
    fun `вложение_удаляется_свайпом_влево_после_подтверждения_корзины_нет`() {
        scrollTo(ATTACHMENT_NAME)
        assertNoVisibleDeleteButtons()

        swipeLeftOnRow(ATTACHMENT_NAME)
        assertDialogOpened(ATTACHMENT_DIALOG_TITLE)

        clickDialogButton(CONFIRM_LABEL)

        composeRule.waitUntil(TIMEOUT_MILLIS) { api.deleteAttachmentCalls.contains(ATTACHMENT_ID) }
        assertEquals(listOf(ATTACHMENT_ID), api.deleteAttachmentCalls)
        // Свайп по вложению не должен задеть задачу: это разные мутации.
        assertTrue(api.deleteTaskCalls.isEmpty())
    }

    /**
     * Прокручивает список редактора к строке с текстом: подзадачи и вложения лежат ниже сгиба,
     * а `LazyColumn` не держит их в дереве, пока они не скомпонованы.
     */
    private fun scrollTo(text: String) {
        // Список полей помечен стабильным тегом: выбор по «есть вложенный TITLE» мог поймать
        // узел, который Compose пересоздаёт при первой компоновке, и прокрутка падала.
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            composeRule
                .onAllNodesWithTag(EditorTestTags.CONTENT, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule
            .onNodeWithTag(EditorTestTags.CONTENT, useUnmergedTree = true)
            .performScrollToNode(hasText(text))
        composeRule.waitForIdle()
    }

    /** Штатный жест §5.2: смахивание влево по самой строке, кнопок для этого нет. */
    private fun swipeLeftOnRow(text: String) {
        composeRule
            .onNodeWithText(text)
            // Стартуем правее середины, но не с края: на краю вложения стоит «Поделиться».
            .performTouchInput { swipeLeft(startX = width * SWIPE_START_FRACTION, endX = 0f) }
        composeRule.waitForIdle()
    }

    /**
     * В редакторе нет ни одной видимой кнопки удаления (политика владельца): ни корзин
     * в строках подзадач и вложений, ни кнопки с подписью «Удалить». Свайп-жест не оставляет
     * узлов, которые можно было бы нажать, — только диалог подтверждения после жеста.
     */
    private fun assertNoVisibleDeleteButtons() {
        composeRule
            .onAllNodesWithContentDescription(SUBTASK_DELETE_DESCRIPTION)
            .assertCountEquals(0)
        composeRule
            .onAllNodesWithContentDescription(ATTACHMENT_DELETE_DESCRIPTION)
            .assertCountEquals(0)
        // Любая новая корзина почти наверняка подписана «Удалить …»: такой подписи быть не должно.
        composeRule
            .onAllNodes(hasContentDescription(DELETE_DESCRIPTION_PREFIX, substring = true))
            .assertCountEquals(0)
        composeRule
            .onAllNodesWithText(CONFIRM_LABEL)
            .assertCountEquals(0)
    }

    /** Диалог подтверждения появляется после сброса строки на место — ждём его явно. */
    private fun assertDialogOpened(title: String) {
        composeRule.waitUntil(TIMEOUT_MILLIS) { dialogNodes(title).isNotEmpty() }
        composeRule.onNodeWithText(title).assertExists()
    }

    private fun dialogNodes(title: String): List<SemanticsNode> =
        composeRule.onAllNodesWithText(title).fetchSemanticsNodes()

    /** Кнопка диалога по подписи: у подтверждения и отмены нет других семантических признаков. */
    private fun clickDialogButton(label: String) {
        composeRule.onNode(hasText(label) and hasClickAction()).performClick()
        composeRule.waitForIdle()
    }

    private fun parentTask() = TaskEntity(
        id = TASK_ID,
        listId = "list-1",
        parentId = null,
        categoryId = null,
        title = "Родительская задача",
        content = "",
        status = "created",
        source = null,
        createdAt = 1.0,
        updatedAt = 2.0,
        completedAt = null,
        statusDateTs = 1.0,
        deleted = false,
    )

    private fun subtask() = parentTask().copy(
        id = SUBTASK_ID,
        parentId = TASK_ID,
        title = SUBTASK_TITLE,
        updatedAt = 3.0,
    )

    private fun attachment() = Attachment(
        id = ATTACHMENT_ID,
        taskId = TASK_ID,
        origName = ATTACHMENT_NAME,
        mime = "application/pdf",
        size = 2_048L,
        createdAt = 4.0,
    )

    private companion object {
        const val TASK_ID = "task-1"
        const val SUBTASK_ID = "task-2"
        const val SUBTASK_TITLE = "Позвонить в сервис"
        const val ATTACHMENT_ID = "att-1"
        const val ATTACHMENT_NAME = "смета.pdf"

        /** Старые подписи корзин: строк таких ресурсов больше нет, узлов быть не должно. */
        const val SUBTASK_DELETE_DESCRIPTION = "Удалить подзадачу"
        const val ATTACHMENT_DELETE_DESCRIPTION = "Удалить вложение"
        const val DELETE_DESCRIPTION_PREFIX = "Удалить"

        /** Тексты диалога и кнопок — как их видит пользователь. */
        const val SUBTASK_DIALOG_TITLE = "Удалить подзадачу?"
        const val ATTACHMENT_DIALOG_TITLE = "Удалить вложение?"
        const val CONFIRM_LABEL = "Удалить"
        const val CANCEL_LABEL = "Отмена"

        /** Доля ширины строки, с которой начинается жест: порог срабатывания — треть ширины. */
        const val SWIPE_START_FRACTION = 0.75f
        const val TIMEOUT_MILLIS = 5_000L
    }
}
