package io.github.bigsprut.taskman.domain

import kotlinx.serialization.Serializable

/**
 * Черновик задачи в редакторе (Ф2, §5.3).
 *
 * Почему отдельная модель, а не `Task` из списка: редактору нужны поля, которых в списке нет
 * (`content`, `createdAt`, `updatedAt` для CAS, `statusDate` для «задним числом»), и он живёт
 * отдельно от снапшота — фоновый `refresh()` не должен затирать несохранённые правки.
 *
 * Модель [`Serializable`], потому что черновик переживает процесс: он кладётся в
 * `SavedStateHandle` в виде JSON и восстанавливается при возврате в приложение.
 *
 * @property statusDateTs явная дата статуса (unix-секунды) для правки «задним числом»;
 *   `null` — дату не трогаем (ТЗ §4: без необходимости `status_date` не слать).
 * @property updatedAt версия из последнего `/api/state` — для CAS при сохранении.
 */
@Serializable
data class TaskDraft(
    val id: String,
    val listId: String,
    val parentId: String?,
    val title: String = "",
    val content: String = "",
    val status: TaskStatus = TaskStatus.CREATED,
    val categoryId: String? = null,
    val tagIds: Set<String> = emptySet(),
    /** Сотрудник-источник; контракт отдаёт имя строкой, а не id. */
    val source: String? = null,
    val createdAt: Double? = null,
    val completedAt: Double? = null,
    val statusDateTs: Double? = null,
    val updatedAt: Double? = null,
) {
    /** Есть ли расхождения с сохранённой версией (для решения «сохранять ли на закрытии»). */
    fun hasChangesComparedTo(original: TaskDraft): Boolean = this != original
}
