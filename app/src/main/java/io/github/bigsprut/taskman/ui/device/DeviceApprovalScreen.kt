package io.github.bigsprut.taskman.ui.device

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.bigsprut.taskman.R
import io.github.bigsprut.taskman.data.remote.ApiFailure
import kotlinx.coroutines.delay

/** Интервал автоповтора `/api/state` на этом экране — по контракту (ТЗ §4: 30 с). */
private const val RETRY_PERIOD_SECONDS = 30
private const val MILLIS_PER_SECOND = 1_000L

/**
 * Экран «устройство ждёт подтверждения» (403 `device`, TOFU).
 *
 * Сервер подтверждает новое устройство до 30 секунд, поэтому приложение не должно требовать
 * от пользователя ручных действий: экран сам повторяет запрос и разворачивается в список,
 * как только подтверждение появилось (§4, §9-Ф0). Кнопка «Проверить сейчас» — для нетерпеливых.
 *
 * Автоповтор привязан к жизненному циклу (`repeatOnLifecycle(RESUMED)`): в фоне приложение
 * не долбит сервер каждые 30 секунд (§5.4). Сетевой сбой показывается отдельной строкой —
 * «нет сети» и «устройство не подтверждено» не смешиваются (§5.2).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceApprovalScreen(
    deviceId: String,
    isChecking: Boolean,
    failure: ApiFailure?,
    onRetry: () -> Unit,
    onChangeToken: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var secondsLeft by rememberSaveable { mutableIntStateOf(RETRY_PERIOD_SECONDS) }
    // Колбэк может поменяться, а эффект — нет: без rememberUpdatedState вызовется старый.
    val latestOnRetry by rememberUpdatedState(onRetry)
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                delay(MILLIS_PER_SECOND)
                secondsLeft -= 1
                if (secondsLeft <= 0) {
                    secondsLeft = RETRY_PERIOD_SECONDS
                    latestOnRetry()
                }
            }
        }
    }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = { TopAppBar(title = { Text(stringResource(R.string.device_title)) }) },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .padding(contentPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Lock,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.device_headline),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.device_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.device_id_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = deviceId,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }

            if (failure is ApiFailure.Network) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.device_no_connection),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            }

            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.device_next_attempt, secondsLeft),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))

            Button(
                onClick = {
                    secondsLeft = RETRY_PERIOD_SECONDS
                    onRetry()
                },
                enabled = !isChecking,
            ) {
                if (isChecking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text(stringResource(R.string.device_retry_now))
                }
            }
            TextButton(onClick = onChangeToken) {
                Text(stringResource(R.string.device_change_token))
            }
        }
    }
}
