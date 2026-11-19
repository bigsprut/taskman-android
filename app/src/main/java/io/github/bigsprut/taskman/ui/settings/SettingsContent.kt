package io.github.bigsprut.taskman.ui.settings

import android.content.ClipData
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.bigsprut.taskman.BuildConfig
import io.github.bigsprut.taskman.R
import io.github.bigsprut.taskman.ui.session.ConnectionUiModel
import kotlinx.coroutines.launch

/**
 * Экран настроек Ф0 — минимальный: параметры подключения и выход.
 *
 * CRUD справочников (теги/категории/сотрудники) — Ф3/Ф5; здесь только то, что нужно,
 * чтобы проверить подключение и сменить инстанс без переустановки приложения.
 */
@Composable
fun SettingsContent(
    connection: ConnectionUiModel?,
    onCopied: () -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
    ) {
        Text(
            text = stringResource(R.string.settings_section_connection),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
        )

        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_server)) },
            supportingContent = {
                Text(
                    text = connection?.baseUrl.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
        )

        HorizontalDivider()

        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_device_id)) },
            supportingContent = {
                Text(
                    text = connection?.deviceId.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            },
            trailingContent = {
                OutlinedButton(
                    onClick = {
                        val deviceId = connection?.deviceId
                        if (!deviceId.isNullOrBlank()) {
                            scope.launch {
                                clipboard.setClipEntry(
                                    ClipEntry(ClipData.newPlainText(CLIP_LABEL, deviceId)),
                                )
                                onCopied()
                            }
                        }
                    },
                ) {
                    Text(stringResource(R.string.action_copy))
                }
            },
        )

        HorizontalDivider()

        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_version)) },
            supportingContent = {
                Text(stringResource(R.string.settings_app_version, BuildConfig.VERSION_NAME))
            },
        )

        Spacer(Modifier.height(16.dp))

        Button(
            onClick = onLogout,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            Text(stringResource(R.string.settings_change_credentials))
        }

        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.settings_security_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}

/** Метка ClipData для системного менеджера буфера. */
private const val CLIP_LABEL = "taskman-device-id"
