package io.github.bigsprut.taskman.ui.settings

import android.content.ClipData
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.bigsprut.taskman.BuildConfig
import io.github.bigsprut.taskman.R
import io.github.bigsprut.taskman.domain.Employee
import io.github.bigsprut.taskman.ui.session.ConnectionUiModel
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

/**
 * Экран настроек: подключение, справка и справочник сотрудников (§5.3-Ф3).
 *
 * CRUD сотрудников — часть Ф3: приложение умеет добавлять/переименовывать/удалять имена,
 * которые потом выбираются в редакторе задачи. Имена в задачах хранятся строкой, поэтому
 * переименование в задачах не переписывается — это сказано в диалоге удаления.
 */
@Composable
fun SettingsContent(
    connection: ConnectionUiModel?,
    employees: List<Employee>,
    onAddEmployee: (String) -> Unit,
    onRenameEmployee: (String, String) -> Unit,
    onDeleteEmployee: (String) -> Unit,
    onCopied: () -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    var renameTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteTarget by rememberSaveable { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
    ) {
        SectionLabel(stringResource(R.string.settings_section_connection))

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

        HorizontalDivider()

        SectionLabel(stringResource(R.string.settings_employees))

        employees.forEach { employee ->
            ListItem(
                headlineContent = { Text(employee.name) },
                trailingContent = {
                    Row {
                        IconButton(onClick = { renameTarget = employee.id }) {
                            Icon(
                                imageVector = Icons.Filled.Edit,
                                contentDescription = stringResource(R.string.settings_employee_rename),
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        IconButton(onClick = { deleteTarget = employee.id }) {
                            Icon(
                                imageVector = Icons.Filled.Delete,
                                contentDescription = stringResource(R.string.settings_employee_remove),
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                },
            )
            HorizontalDivider()
        }

        EmployeeAddRow(onAdd = onAddEmployee)

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

    val renamedEmployee = employees.firstOrNull { it.id == renameTarget }
    if (renamedEmployee != null) {
        EmployeeNameDialog(
            title = stringResource(R.string.settings_employee_rename),
            initialName = renamedEmployee.name,
            confirmLabel = stringResource(R.string.common_save),
            onConfirm = { name ->
                onRenameEmployee(renamedEmployee.id, name)
                renameTarget = null
            },
            onDismiss = { renameTarget = null },
        )
    }

    val deletedEmployee = employees.firstOrNull { it.id == deleteTarget }
    if (deletedEmployee != null) {
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.settings_employee_delete_title)) },
            text = {
                Text(stringResource(R.string.settings_employee_delete_text, deletedEmployee.name))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteEmployee(deletedEmployee.id)
                        deleteTarget = null
                    },
                ) {
                    Text(stringResource(R.string.settings_employee_remove))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
    )
}

@Composable
private fun EmployeeAddRow(onAdd: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            modifier = Modifier.weight(1f),
            label = { Text(stringResource(R.string.settings_employee_name)) },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = {
                    if (name.isNotBlank()) {
                        onAdd(name)
                        name = ""
                    }
                },
            ),
        )
        Button(
            onClick = {
                if (name.isNotBlank()) {
                    onAdd(name)
                    name = ""
                }
            },
            enabled = name.isNotBlank(),
        ) {
            Text(stringResource(R.string.settings_employee_add))
            Spacer(Modifier.width(4.dp))
        }
    }
}

@Composable
private fun EmployeeNameDialog(
    title: String,
    initialName: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by rememberSaveable { mutableStateOf(initialName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(stringResource(R.string.settings_employee_name)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (value.isNotBlank()) onConfirm(value)
                },
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}

/** Метка ClipData для системного менеджера буфера. */
private const val CLIP_LABEL = "taskman-device-id"
