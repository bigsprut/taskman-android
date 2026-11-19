package io.github.bigsprut.taskman.data.attachments

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Приём файлов из «Поделиться в Задачи» (Ф3, §5.3).
 *
 * Activity читает `Intent.EXTRA_STREAM` и складывает uri сюда; UI показывает выбор задачи и
 * загружает файлы как вложения. Очередь живёт в контейнере, чтобы пережить пересоздание
 * Activity (поворот) — сами файлы приходят из чужого `content://`, поэтому достаточно uri.
 */
class ShareInbox {

    private val _uris = MutableStateFlow<List<String>>(emptyList())
    val uris: StateFlow<List<String>> = _uris.asStateFlow()

    fun add(newUris: List<String>) {
        val clean = newUris.filter { it.isNotBlank() }
        if (clean.isEmpty()) return
        _uris.update { current -> (current + clean).distinct() }
    }

    fun clear() {
        _uris.value = emptyList()
    }
}
