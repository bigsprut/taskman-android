package io.github.bigsprut.taskman.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.bigsprut.taskman.R
import io.github.bigsprut.taskman.ui.common.userMessage
import io.github.bigsprut.taskman.ui.list.TaskListContent
import io.github.bigsprut.taskman.ui.list.TaskListViewModel
import io.github.bigsprut.taskman.ui.session.SessionViewModel
import io.github.bigsprut.taskman.ui.settings.SettingsContent
import kotlinx.coroutines.launch

private const val TAB_TASKS = 0
private const val TAB_SETTINGS = 1

/**
 * Основной каркас: `Scaffold` + `TopAppBar` + `NavigationBar` (Список / Настройки), §5.2.
 *
 * Один `Scaffold` на оба раздела — так insets считаются один раз (`safeDrawing`), и нет
 * двойных отступов от вложенных Scaffold'ов. Снекбары показывает этот же Scaffold.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    sessionViewModel: SessionViewModel,
    taskListViewModel: TaskListViewModel,
    modifier: Modifier = Modifier,
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(TAB_TASKS) }

    val tasks by taskListViewModel.tasks.collectAsStateWithLifecycle()
    val syncStatus by taskListViewModel.syncStatus.collectAsStateWithLifecycle()
    val connection by sessionViewModel.connection.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val failure = syncStatus.lastFailure
    val failureText = failure?.userMessage()
    val cachedDataWarning = stringResource(R.string.list_cached_warning)
    val copiedMessage = stringResource(R.string.settings_copied)

    // Если данные есть (из Room), ошибку синхронизации показываем снекбаром и не стираем
    // список с экрана: «нет сети» не означает «нет данных».
    LaunchedEffect(failure) {
        if (failureText != null && tasks.isNotEmpty()) {
            snackbarHostState.showSnackbar("$cachedDataWarning · $failureText")
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (selectedTab == TAB_TASKS) R.string.list_title else R.string.settings_title,
                        ),
                    )
                },
                actions = {
                    if (selectedTab == TAB_TASKS) {
                        IconButton(
                            onClick = taskListViewModel::refresh,
                            enabled = !syncStatus.isRefreshing,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = stringResource(R.string.action_refresh),
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == TAB_TASKS,
                    onClick = { selectedTab = TAB_TASKS },
                    icon = {
                        Icon(Icons.AutoMirrored.Filled.List, contentDescription = null)
                    },
                    label = { Text(stringResource(R.string.nav_tasks)) },
                )
                NavigationBarItem(
                    selected = selectedTab == TAB_SETTINGS,
                    onClick = { selectedTab = TAB_SETTINGS },
                    icon = {
                        Icon(Icons.Filled.Settings, contentDescription = null)
                    },
                    label = { Text(stringResource(R.string.nav_settings)) },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { contentPadding ->
        Box(Modifier.padding(contentPadding)) {
            when (selectedTab) {
                TAB_TASKS -> TaskListContent(
                    tasks = tasks,
                    syncStatus = syncStatus,
                    onRefresh = taskListViewModel::refresh,
                )

                else -> SettingsContent(
                    connection = connection,
                    onCopied = {
                        scope.launch { snackbarHostState.showSnackbar(copiedMessage) }
                    },
                    onLogout = sessionViewModel::logout,
                )
            }
        }
    }
}
