package io.github.bigsprut.taskman.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.bigsprut.taskman.ui.common.LoadingState
import io.github.bigsprut.taskman.ui.device.DeviceApprovalScreen
import io.github.bigsprut.taskman.ui.list.TaskListViewModel
import io.github.bigsprut.taskman.ui.login.LoginScreen
import io.github.bigsprut.taskman.ui.session.SessionUiState
import io.github.bigsprut.taskman.ui.session.SessionViewModel

/**
 * Корень UI: одно из пяти состояний, без «промежуточных» экранов.
 *
 * - `Restoring` — читаются настройки (первый кадр);
 * - `NeedsLogin` — нет токена или сервер ответил 401;
 * - `DeviceApprovalPending` — 403 `device`, экран сам повторяет запрос раз в 30 с;
 * - `Ready` — список + настройки.
 *
 * Состояния взаимоисключающие (§5.2: «нет данных» ≠ «нет сети» ≠ «устройство не подтверждено»).
 */
@Composable
fun AppRoot(
    sessionViewModel: SessionViewModel,
    taskListViewModel: TaskListViewModel,
    modifier: Modifier = Modifier,
) {
    val sessionState by sessionViewModel.state.collectAsStateWithLifecycle()

    when (val state = sessionState) {
        SessionUiState.Restoring -> Surface(
            modifier = modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            LoadingState()
        }

        SessionUiState.NeedsLogin -> {
            val loginState by sessionViewModel.login.collectAsStateWithLifecycle()
            LoginScreen(
                loginState = loginState,
                onLogin = sessionViewModel::login,
                modifier = modifier,
            )
        }

        is SessionUiState.DeviceApprovalPending -> {
            val syncStatus by taskListViewModel.syncStatus.collectAsStateWithLifecycle()
            DeviceApprovalScreen(
                deviceId = state.deviceId,
                isChecking = syncStatus.isRefreshing,
                failure = syncStatus.lastFailure,
                onRetry = sessionViewModel::retryDeviceCheck,
                onChangeToken = sessionViewModel::logout,
                modifier = modifier,
            )
        }

        SessionUiState.Ready -> MainScreen(
            sessionViewModel = sessionViewModel,
            taskListViewModel = taskListViewModel,
            modifier = modifier,
        )
    }
}
