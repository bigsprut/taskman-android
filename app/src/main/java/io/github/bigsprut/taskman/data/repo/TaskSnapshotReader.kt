package io.github.bigsprut.taskman.data.repo

import io.github.bigsprut.taskman.data.local.entity.TaskEntity

/**
 * Чтение локального снапшота для мутаций: чтобы собрать полный payload (§4), нужна вся
 * запись задачи и её теги, а не модель строки списка.
 *
 * Интерфейс, а не прямая ссылка на Room: репозиторий мутаций тестируется на подставном
 * источнике (§ «правила передачи» в TASKS.md).
 */
interface TaskSnapshotReader {

    suspend fun taskEntity(id: String): TaskEntity?

    suspend fun tagIds(taskId: String): List<String>

    /**
     * Список, в который попадает новая задача: контракт требует `list_id`, а списков в UI
     * пока один (позиция 0). Если снапшота ещё нет — `null`, создать задачу нельзя.
     */
    suspend fun firstListId(): String?
}
