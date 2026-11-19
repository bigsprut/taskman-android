package io.github.bigsprut.taskman.editor

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.bigsprut.taskman.data.local.entity.TaskEntity
import io.github.bigsprut.taskman.data.repo.TaskMutationRepository
import io.github.bigsprut.taskman.data.repo.TaskRepository
import io.github.bigsprut.taskman.ui.editor.EditorTestTags
import io.github.bigsprut.taskman.ui.editor.TaskEditorSheet
import io.github.bigsprut.taskman.ui.editor.TaskEditorViewModel
import io.github.bigsprut.taskman.ui.theme.TaskmanTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Обязательный регресс §9-Ф2: **несохранённые поля не теряются при действии с подзадачей
 * при открытом редакторе**.
 *
 * Тест настоящий UI-уровня: Compose-лист, ввод в поле названия, затем действие с подзадачей
 * (уходит на сервер как отдельная мутация) — поле сохраняет введённый текст, а черновик
 * остаётся «грязным». Прогон: `./gradlew connectedDebugAndroidTest` (эмулятор/устройство).
 */
@RunWith(AndroidJUnit4::class)
class EditorDraftRegressionTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun unsavedTitleSurvivesSubtaskAction() {
        val api = FakeApi()
        val store = FakeStore().apply {
            putTask(
                TaskEntity(
                    id = TASK_ID,
                    listId = "list-1",
                    parentId = null,
                    categoryId = null,
                    title = "Исходное название",
                    content = "",
                    status = "created",
                    source = null,
                    createdAt = 1.0,
                    updatedAt = 2.0,
                    completedAt = null,
                    statusDateTs = 1.0,
                    deleted = false,
                ),
            )
        }
        val tasks = TaskRepository(api = api, stateStore = store)
        val viewModel = TaskEditorViewModel(
            tasks = tasks,
            mutations = TaskMutationRepository(api = api, reader = store, tasks = tasks),
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

        // Пользователь правит название и НЕ закрывает лист.
        composeRule.onNodeWithTag(EditorTestTags.TITLE).performTextReplacement(EDITED_TITLE)
        composeRule.waitForIdle()

        // Действие с подзадачей: своя мутация на сервер, но черновик родителя не должен сброситься.
        composeRule.runOnIdle { viewModel.addSubtask("Новая подзадача") }
        composeRule.waitUntil(TIMEOUT_MILLIS) { api.upsertCalls > 0 }
        composeRule.waitForIdle()

        // Поле в UI и модель черновика сохранили правку.
        composeRule.onNodeWithTag(EditorTestTags.TITLE).assertTextContains(EDITED_TITLE)
        assertEquals(EDITED_TITLE, viewModel.state.value.draft?.title)
        assertTrue(viewModel.state.value.isDirty)
        assertTrue(viewModel.state.value.isOpen)
    }

    private companion object {
        const val TASK_ID = "task-1"
        const val EDITED_TITLE = "Черновик не должен потеряться"
        const val TIMEOUT_MILLIS = 5_000L
    }
}
