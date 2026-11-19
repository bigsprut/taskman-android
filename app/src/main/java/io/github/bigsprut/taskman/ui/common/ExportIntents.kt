package io.github.bigsprut.taskman.ui.common

import android.content.Context
import android.content.Intent

/**
 * Экспорт в markdown через системный лист «Поделиться» (§5.3-Ф5).
 *
 * Отправляем текст (`text/plain`), а не файл: пользователю проще вставить в заметки/чат,
 * а файл можно сохранить уже из принимающего приложения.
 */
object ExportIntents {

    /** @return `false`, если отправить не удалось (нет приложения) — UI покажет сообщение. */
    fun shareMarkdown(context: Context, text: String, subject: String): Boolean = runCatching {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = MIME_TEXT
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, text)
        }
        val chooser = Intent.createChooser(send, null).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
        true
    }.getOrDefault(false)

    private const val MIME_TEXT = "text/plain"
}
