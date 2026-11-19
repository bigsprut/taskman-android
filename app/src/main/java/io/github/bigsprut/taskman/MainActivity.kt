package io.github.bigsprut.taskman

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.bigsprut.taskman.ui.AppRoot
import io.github.bigsprut.taskman.ui.TaskmanViewModels
import io.github.bigsprut.taskman.ui.list.TaskListViewModel
import io.github.bigsprut.taskman.ui.session.SessionViewModel
import io.github.bigsprut.taskman.ui.theme.TaskmanTheme

/**
 * Единственная Activity: Compose-хост.
 *
 * Edge-to-edge включён (ТЗ §5.2), splash — core-splashscreen; фон окна задан XML-темой,
 * чтобы первый кадр не мигал белым в тёмной теме.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // installSplashScreen вызывается до super.onCreate: иначе система успевает
        // показать свой фон.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

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

    AppRoot(
        sessionViewModel = sessionViewModel,
        taskListViewModel = taskListViewModel,
    )
}
