package io.github.bigsprut.taskman

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.core.content.IntentCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.bigsprut.taskman.ui.AppRoot
import io.github.bigsprut.taskman.ui.TaskmanViewModels
import io.github.bigsprut.taskman.ui.editor.TaskEditorViewModel
import io.github.bigsprut.taskman.ui.list.TaskListViewModel
import io.github.bigsprut.taskman.ui.session.SessionViewModel
import io.github.bigsprut.taskman.ui.settings.SettingsViewModel
import io.github.bigsprut.taskman.ui.theme.TaskmanTheme

/**
 * Единственная Activity: Compose-хост.
 *
 * Edge-to-edge включён (ТЗ §5.2), splash — core-splashscreen; фон окна задан XML-темой,
 * чтобы первый кадр не мигал белым в тёмной теме.
 *
 * Здесь же принимается «Поделиться в Задачи» (`ACTION_SEND` / `ACTION_SEND_MULTIPLE`):
 * uri складываются в общую очередь, а выбор задачи и загрузку делает UI.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // installSplashScreen вызывается до super.onCreate: иначе система успевает
        // показать свой фон.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        handleIncomingShare(intent)

        val container = (application as TaskmanApp).container
        val allowCleartextBaseUrl = BuildConfig.DEBUG

        setContent {
            TaskmanTheme {
                TaskmanAppContent(
                    container = container,
                    allowCleartextBaseUrl = allowCleartextBaseUrl,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Ф4: вернулись в приложение — сразу пытаемся отправить очередь, не дожидаясь backoff.
        (application as TaskmanApp).container.syncScheduler.requestFlush()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingShare(intent)
    }

    /** Достаёт uri из `ACTION_SEND` / `ACTION_SEND_MULTIPLE` и складывает в общую очередь. */
    private fun handleIncomingShare(intent: Intent?) {
        val uris: List<String> = when (intent?.action) {
            Intent.ACTION_SEND -> listOfNotNull(
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java),
            ).map(Uri::toString)

            Intent.ACTION_SEND_MULTIPLE -> IntentCompat
                .getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                ?.map(Uri::toString)
                .orEmpty()

            else -> emptyList()
        }
        if (uris.isNotEmpty()) {
            (application as TaskmanApp).container.shareInbox.add(uris)
        }
    }
}

@Composable
private fun TaskmanAppContent(
    container: io.github.bigsprut.taskman.di.AppContainer,
    allowCleartextBaseUrl: Boolean,
) {
    val sessionViewModel: SessionViewModel = viewModel(
        factory = TaskmanViewModels.session(container, allowCleartextBaseUrl),
    )
    val taskListViewModel: TaskListViewModel = viewModel(
        factory = TaskmanViewModels.taskList(container),
    )
    val editorViewModel: TaskEditorViewModel = viewModel(
        factory = TaskmanViewModels.taskEditor(container),
    )
    val settingsViewModel: SettingsViewModel = viewModel(
        factory = TaskmanViewModels.settings(container),
    )

    AppRoot(
        sessionViewModel = sessionViewModel,
        taskListViewModel = taskListViewModel,
        editorViewModel = editorViewModel,
        settingsViewModel = settingsViewModel,
    )
}
