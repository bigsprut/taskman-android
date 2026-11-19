package io.github.bigsprut.taskman.ui.common

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * Открытие и шаринг вложений (ТЗ §5.3-Ф3).
 *
 * Файл скачивается в приватный кэш, наружу отдаётся через [FileProvider] с временным правом
 * чтения — прямой `file://` чужим приложениям отдавать нельзя начиная с Android 7.
 */
object AttachmentIntents {

    enum class Action { OPEN, SHARE }

    /**
     * Запускает внешний просмотрщик ([Action.OPEN]) или лист «Поделиться» ([Action.SHARE]).
     *
     * @return `false`, если подходящего приложения нет или система отказала — вызывающий
     *   покажет сообщение, а не молча ничего не сделает.
     */
    fun launch(
        context: Context,
        file: File,
        mimeType: String?,
        action: Action,
    ): Boolean = runCatching {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}$AUTHORITY_SUFFIX",
            file,
        )
        val resolvedType = mimeType?.takeIf { it.isNotBlank() } ?: ANY_MIME

        val intent = when (action) {
            Action.OPEN -> Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, resolvedType)
            }

            Action.SHARE -> Intent(Intent.ACTION_SEND).apply {
                type = resolvedType
                putExtra(Intent.EXTRA_STREAM, uri)
            }
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)

        val chooser = Intent.createChooser(intent, null)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
        true
    }.getOrDefault(false)

    private const val AUTHORITY_SUFFIX = ".fileprovider"
    private const val ANY_MIME = "*/*"
}
