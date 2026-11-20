package io.github.bigsprut.taskman.ui.settings

import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.bigsprut.taskman.BuildConfig
import io.github.bigsprut.taskman.R
import io.github.bigsprut.taskman.domain.CatalogColor
import io.github.bigsprut.taskman.domain.Category
import io.github.bigsprut.taskman.domain.Employee
import io.github.bigsprut.taskman.domain.Tag
import io.github.bigsprut.taskman.domain.TaskSortOrder
import io.github.bigsprut.taskman.ui.common.SwipeToDeleteRow
import io.github.bigsprut.taskman.ui.common.parseTaskmanColor
import io.github.bigsprut.taskman.ui.session.ConnectionUiModel
import kotlinx.coroutines.launch

/**
 * Экран настроек: подключение, справочники (теги/категории/сотрудники), категория по
 * умолчанию, порядок сортировки и экспорт в markdown (§5.3-Ф3, §5.3-Ф5).
 *
 * Все строки — из ресурсов (§5.4); цвета вводятся как `#rrggbb` и нормализуются
 * (`CatalogColor`), а неверный ввод подсвечивается подсказкой.
 */
@Composable
fun SettingsContent(
    connection: ConnectionUiModel?,
    employees: List<Employee>,
    tags: List<Tag>,
    categories: List<Category>,
    defaultCategoryId: String?,
    sortOrder: TaskSortOrder,
    onAddEmployee: (String) -> Unit,
    onRenameEmployee: (String, String) -> Unit,
    onDeleteEmployee: (String) -> Unit,
    onAddTag: (String, String?) -> Unit,
    onUpdateTag: (String, String, String?) -> Unit,
    onDeleteTag: (String) -> Unit,
    onAddCategory: (String, String?) -> Unit,
    onUpdateCategory: (String, String, String?) -> Unit,
    onDeleteCategory: (String) -> Unit,
    onSetDefaultCategory: (String?) -> Unit,
    onSetSortOrder: (TaskSortOrder) -> Unit,
    onExport: () -> Unit,
    onCopied: () -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    var renameTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var editedTagId by rememberSaveable { mutableStateOf<String?>(null) }
    var editedCategoryId by rememberSaveable { mutableStateOf<String?>(null) }
    // 20.11: добавление в справочник — как добавление задачи: не поле прямо в списке,
    // а явное действие («+» в строке) → диалог с формой. Один паттерн на всё приложение.
    var addTarget by rememberSaveable { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            // 20.11: клавиатура. Без imePadding поле «Имя сотрудника» и кнопка
            // «Добавить» уезжали под клавиатуру — набираешь имя, жмёшь туда, где
            // кнопка была, а попадаешь по клавишам («сотрудники не добавляются»).
            .imePadding()
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

        // --- Теги (Ф5) ---
        SectionLabel(stringResource(R.string.settings_tags))
        tags.forEach { tag ->
            // 20.11: удаление — только смахиванием влево с подтверждением (ТЗ §5.2),
            // видимых корзин в настройках больше нет.
            SwipeToDeleteRow(
                title = stringResource(R.string.settings_catalog_delete_title),
                text = stringResource(R.string.settings_catalog_delete_text, tag.title),
                confirmLabel = stringResource(R.string.settings_employee_remove),
                cancelLabel = stringResource(R.string.common_cancel),
                onDelete = { onDeleteTag(tag.id) },
            ) {
                CatalogRow(
                    title = tag.title,
                    color = tag.color,
                    editContentDescription = stringResource(R.string.settings_employee_rename),
                    onEdit = { editedTagId = tag.id },
                )
            }
            HorizontalDivider()
        }
        AddListItem(
            label = stringResource(R.string.settings_tag_add),
            onClick = { addTarget = KIND_TAG },
        )

        HorizontalDivider()

        // --- Категории (Ф5) ---
        SectionLabel(stringResource(R.string.settings_categories))
        categories.forEach { category ->
            SwipeToDeleteRow(
                title = stringResource(R.string.settings_catalog_delete_title),
                text = stringResource(R.string.settings_catalog_delete_text, category.title),
                confirmLabel = stringResource(R.string.settings_employee_remove),
                cancelLabel = stringResource(R.string.common_cancel),
                onDelete = { onDeleteCategory(category.id) },
            ) {
                CatalogRow(
                    title = category.title,
                    color = category.color,
                    editContentDescription = stringResource(R.string.settings_employee_rename),
                    onEdit = { editedCategoryId = category.id },
                )
            }
            HorizontalDivider()
        }
        AddListItem(
            label = stringResource(R.string.settings_category_add),
            onClick = { addTarget = KIND_CATEGORY },
        )

        HorizontalDivider()

        // --- Категория по умолчанию (Ф5) ---
        SectionLabel(stringResource(R.string.settings_default_category))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = defaultCategoryId == null,
                onClick = { onSetDefaultCategory(null) },
                label = { Text(stringResource(R.string.settings_default_category_none)) },
            )
            categories.take(2).forEach { category ->
                FilterChip(
                    selected = defaultCategoryId == category.id,
                    onClick = { onSetDefaultCategory(category.id) },
                    label = { Text(category.title) },
                )
            }
        }

        HorizontalDivider()

        // --- Сортировка (Ф5) ---
        SectionLabel(stringResource(R.string.settings_sort_order))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = sortOrder == TaskSortOrder.STATUS,
                onClick = { onSetSortOrder(TaskSortOrder.STATUS) },
                label = { Text(stringResource(R.string.sort_status)) },
            )
            FilterChip(
                selected = sortOrder == TaskSortOrder.FRESHNESS,
                onClick = { onSetSortOrder(TaskSortOrder.FRESHNESS) },
                label = { Text(stringResource(R.string.sort_freshness)) },
            )
            FilterChip(
                selected = sortOrder == TaskSortOrder.TITLE,
                onClick = { onSetSortOrder(TaskSortOrder.TITLE) },
                label = { Text(stringResource(R.string.sort_title)) },
            )
        }

        HorizontalDivider()

        // --- Сотрудники (Ф3) ---
        SectionLabel(stringResource(R.string.settings_employees))

        employees.forEach { employee ->
            SwipeToDeleteRow(
                title = stringResource(R.string.settings_employee_delete_title),
                text = stringResource(R.string.settings_employee_delete_text, employee.name),
                confirmLabel = stringResource(R.string.settings_employee_remove),
                cancelLabel = stringResource(R.string.common_cancel),
                onDelete = { onDeleteEmployee(employee.id) },
            ) {
                ListItem(
                    headlineContent = { Text(employee.name) },
                    trailingContent = {
                        IconButton(onClick = { renameTarget = employee.id }) {
                            Icon(
                                imageVector = Icons.Filled.Edit,
                                contentDescription = stringResource(R.string.settings_employee_rename),
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    },
                )
            }
            HorizontalDivider()
        }

        AddListItem(
            label = stringResource(R.string.settings_employee_add),
            onClick = { addTarget = KIND_EMPLOYEE },
        )

        HorizontalDivider()

        // --- Экспорт в markdown (Ф5) ---
        SectionLabel(stringResource(R.string.settings_export))
        Text(
            text = stringResource(R.string.settings_export_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        OutlinedButton(
            onClick = onExport,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(stringResource(R.string.settings_export_action))
        }

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

    // --- Диалоги ---

    val renamedEmployee = employees.firstOrNull { it.id == renameTarget }
    if (renamedEmployee != null) {
        CatalogItemDialog(
            title = stringResource(R.string.settings_employee_rename),
            initialTitle = renamedEmployee.name,
            showColor = false,
            confirmLabel = stringResource(R.string.common_save),
            onConfirm = { name, _ ->
                onRenameEmployee(renamedEmployee.id, name)
                renameTarget = null
            },
            onDismiss = { renameTarget = null },
        )
    }

    tags.firstOrNull { it.id == editedTagId }?.let { tag ->
        CatalogItemDialog(
            title = stringResource(R.string.settings_tag_edit),
            initialTitle = tag.title,
            initialColor = tag.color,
            confirmLabel = stringResource(R.string.common_save),
            onConfirm = { title, color ->
                onUpdateTag(tag.id, title, color)
                editedTagId = null
            },
            onDismiss = { editedTagId = null },
        )
    }

    categories.firstOrNull { it.id == editedCategoryId }?.let { category ->
        CatalogItemDialog(
            title = stringResource(R.string.settings_category_edit),
            initialTitle = category.title,
            initialColor = category.color,
            confirmLabel = stringResource(R.string.common_save),
            onConfirm = { title, color ->
                onUpdateCategory(category.id, title, color)
                editedCategoryId = null
            },
            onDismiss = { editedCategoryId = null },
        )
    }

    // Добавление в справочник: тот же диалог, что и редактирование (один паттерн с задачей —
    // явное действие открывает форму, а не поле, спрятанное в списке).
    when (addTarget) {
        KIND_TAG -> CatalogItemDialog(
            title = stringResource(R.string.settings_tag_new),
            initialTitle = "",
            confirmLabel = stringResource(R.string.common_add),
            onConfirm = { title, color ->
                onAddTag(title, color)
                addTarget = null
            },
            onDismiss = { addTarget = null },
        )

        KIND_CATEGORY -> CatalogItemDialog(
            title = stringResource(R.string.settings_category_new),
            initialTitle = "",
            confirmLabel = stringResource(R.string.common_add),
            onConfirm = { title, color ->
                onAddCategory(title, color)
                addTarget = null
            },
            onDismiss = { addTarget = null },
        )

        KIND_EMPLOYEE -> CatalogItemDialog(
            title = stringResource(R.string.settings_employee_new),
            initialTitle = "",
            showColor = false,
            confirmLabel = stringResource(R.string.common_add),
            onConfirm = { name, _ ->
                onAddEmployee(name)
                addTarget = null
            },
            onDismiss = { addTarget = null },
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

/** Строка тега/категории: цветная точка, название и переименование (удаление — свайпом). */
@Composable
private fun CatalogRow(
    title: String,
    color: String?,
    editContentDescription: String,
    onEdit: () -> Unit,
) {
    ListItem(
        leadingContent = {
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(parseTaskmanColor(color, MaterialTheme.colorScheme.primary)),
            )
        },
        headlineContent = { Text(title) },
        trailingContent = {
            IconButton(onClick = onEdit) {
                Icon(
                    imageVector = Icons.Filled.Edit,
                    contentDescription = editContentDescription,
                    modifier = Modifier.size(20.dp),
                )
            }
        },
    )
}

/** Строка добавления в справочник: пункт списка с «+», открывает диалог с формой. */
@Composable
private fun AddListItem(label: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = {
            Text(label, color = MaterialTheme.colorScheme.primary)
        },
        leadingContent = {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/** Диалог создания/переименования элемента справочника (заголовок + цвет). */
@Composable
private fun CatalogItemDialog(
    title: String,
    initialTitle: String,
    confirmLabel: String,
    onConfirm: (String, String?) -> Unit,
    onDismiss: () -> Unit,
    initialColor: String? = null,
    showColor: Boolean = true,
) {
    var value by rememberSaveable { mutableStateOf(initialTitle) }
    var color by rememberSaveable { mutableStateOf(initialColor ?: CatalogColor.DEFAULT) }
    val normalized = CatalogColor.normalize(color)
    val colorValid = normalized != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text(stringResource(R.string.settings_catalog_title)) },
                    singleLine = true,
                )
                if (showColor) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(MaterialTheme.shapes.small)
                                .background(
                                    parseTaskmanColor(normalized, MaterialTheme.colorScheme.primary),
                                ),
                        )
                        OutlinedTextField(
                            value = color,
                            onValueChange = { color = it },
                            label = { Text(stringResource(R.string.settings_catalog_color)) },
                            singleLine = true,
                            isError = !colorValid,
                            supportingText = if (!colorValid) {
                                { Text(stringResource(R.string.settings_catalog_color_hint)) }
                            } else {
                                null
                            },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (value.isNotBlank() && (normalized != null || !showColor)) {
                        onConfirm(value, if (showColor) normalized else null)
                    }
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

/** Какой справочник добавляем: тег, категория или сотрудник (диалог добавления общий). */
private const val KIND_TAG = "tag"
private const val KIND_CATEGORY = "category"
private const val KIND_EMPLOYEE = "employee"
