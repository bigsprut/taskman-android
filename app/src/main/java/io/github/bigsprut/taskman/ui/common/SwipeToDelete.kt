package io.github.bigsprut.taskman.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Свайп-удаление влево с подтверждением (ТЗ §5.2: «удаление — M3 SwipeToDismissBox
 * (смахивание влево, с подтверждением диалогом) — ВЕЗДЕ»).
 *
 * Видимых кнопок удаления в продукте нет (политика владельца): удаление доступно только
 * смахиванием. Свайп не удаляет сразу — строка возвращается на место, а решение принимает
 * диалог; это защита от случайного жеста.
 *
 * @param title заголовок диалога подтверждения.
 * @param text описание удаляемого объекта.
 * @param onDelete действие удаления после подтверждения.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeToDeleteRow(
    title: String,
    text: String,
    confirmLabel: String,
    cancelLabel: String,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var pendingConfirmation by remember { mutableStateOf(false) }
    val dismissState = rememberSwipeToDismissBoxState(
        positionalThreshold = { totalDistance -> totalDistance * SWIPE_THRESHOLD_FRACTION },
    )

    SwipeToDismissBox(
        state = dismissState,
        modifier = modifier.fillMaxWidth(),
        enableDismissFromStartToEnd = false,
        onDismiss = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                // Сначала диалог; строку сразу возвращаем на место — если пользователь откажется,
                // она уже там, где была.
                pendingConfirmation = true
                scope.launch { dismissState.reset() }
            }
        },
        backgroundContent = { DeleteSwipeBackground() },
    ) {
        // Непрозрачный фон обязателен: иначе красный фон свайпа и иконка удаления
        // просвечивают в обычном состоянии строки (проверено живьём — «встроенные» корзины).
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            content()
        }
    }

    if (pendingConfirmation) {
        AlertDialog(
            onDismissRequest = { pendingConfirmation = false },
            title = { Text(title) },
            text = { Text(text) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingConfirmation = false
                        onDelete()
                    },
                ) {
                    Text(confirmLabel)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingConfirmation = false }) {
                    Text(cancelLabel)
                }
            },
        )
    }
}

/** Фон под смахиваемой строкой: красная заливка и иконка удаления (видна только в жесте). */
@Composable
fun DeleteSwipeBackground() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Icon(
            imageVector = Icons.Filled.Delete,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

/** Порог срабатывания — треть ширины строки: случайный короткий жест не открывает диалог. */
private const val SWIPE_THRESHOLD_FRACTION = 0.35f