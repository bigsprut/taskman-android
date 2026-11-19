package io.github.bigsprut.taskman.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * Шкала типографики M3 по умолчанию: экраны обязаны использовать `MaterialTheme.typography`,
 * а не «голые» `fontSize` (ТЗ §5.2), поэтому здесь нет собственных размеров.
 */
internal val TaskmanTypography = Typography()

/**
 * Тема приложения: Material 3, dynamic color на API 31+, фолбэк — своя схема.
 * Тёмная/светлая следуют системной настройке; фон окна под них задан в XML-теме,
 * поэтому первый кадр не мигает белым.
 */
@Composable
fun TaskmanTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = TaskmanTypography,
        content = content,
    )
}
