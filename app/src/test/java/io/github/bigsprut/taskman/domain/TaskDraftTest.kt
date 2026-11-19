package io.github.bigsprut.taskman.domain

import com.google.common.truth.Truth.assertThat
import io.github.bigsprut.taskman.data.remote.TaskmanJson
import org.junit.Test

/**
 * Черновик редактора (`TaskDraft`) и его сериализация (Ф2, §5.3-Ф2).
 *
 * Черновик переживает убивание процесса через `SavedStateHandle`: ViewModel кладёт туда
 * JSON и при восстановлении читает его обратно. Поэтому главное здесь — **round-trip без
 * потерь**: если поле пропадёт при сериализации, пользователь потеряет несохранённую правку
 * молча, и никакой тест ViewModel этого не заметит.
 *
 * Проверяется именно боевой [TaskmanJson] (`explicitNulls = false`), а не отдельный Json:
 * иначе тест проверял бы не тот механизм, что работает в приложении.
 */
class TaskDraftTest {

    /** Черновик со всеми заполненными полями: эталон round-trip. */
    private val fullDraft = TaskDraft(
        id = "task-1",
        listId = "list-1",
        parentId = "parent-1",
        title = "Купить хлеб",
        content = "описание с **markdown**",
        status = TaskStatus.COMPLETED,
        categoryId = "cat-1",
        tagIds = setOf("tag-a", "tag-b"),
        source = "Иван",
        createdAt = 1_700_000_000.0,
        completedAt = 1_700_000_400.0,
        statusDateTs = 1_700_000_500.0,
        updatedAt = 1_700_000_900.0,
    )

    @Test
    fun `черновик целиком переживает сериализацию и десериализацию`() {
        val restored = roundTrip(fullDraft)

        // Полное совпадение, а не проверка полей по одному: так новое поле в TaskDraft
        // не сможет выпасть из сохранения незамеченным.
        assertThat(restored).isEqualTo(fullDraft)
        // Отдельно фиксируем «непростые» типы, ради которых и написана `@Serializable`-модель.
        assertThat(restored.status).isEqualTo(TaskStatus.COMPLETED)
        assertThat(restored.tagIds).containsExactly("tag-a", "tag-b")
        assertThat(restored.statusDateTs).isEqualTo(1_700_000_500.0)
    }

    @Test
    fun `null-поля не теряются и не ломают чтение`() {
        // Самая частая форма нового черновика: родителя, категории, сотрудника и дат нет.
        // `explicitNulls = false` в TaskmanJson выбрасывает их из JSON, поэтому важно, что
        // чтение отсутствующего поля даёт `null`, а не падение (в т.ч. `parentId`, у которого
        // нет значения по умолчанию).
        val draft = TaskDraft(id = "task-1", listId = "list-1", parentId = null)

        val json = TaskmanJson.encodeToString(TaskDraft.serializer(), draft)

        assertThat(json).doesNotContain("parentId")
        assertThat(json).doesNotContain("statusDateTs")
        assertThat(json).doesNotContain("updatedAt")

        val restored = TaskmanJson.decodeFromString(TaskDraft.serializer(), json)
        assertThat(restored).isEqualTo(draft)
        assertThat(restored.parentId).isNull()
        assertThat(restored.categoryId).isNull()
        assertThat(restored.source).isNull()
        assertThat(restored.statusDateTs).isNull()
        assertThat(restored.completedAt).isNull()
        assertThat(restored.updatedAt).isNull()
    }

    @Test
    fun `hasChangesComparedTo не видит расхождений у одинаковых черновиков`() {
        // Черновик, который ещё не правили, не должен сохраняться при закрытии листа.
        assertThat(fullDraft.hasChangesComparedTo(fullDraft)).isFalse()
        assertThat(fullDraft.hasChangesComparedTo(fullDraft.copy())).isFalse()
    }

    @Test
    fun `hasChangesComparedTo замечает правку названия, тегов и статуса`() {
        // Любое изменяемое поле — повод для автосохранения при закрытии (§5.3-Ф2).
        assertThat(fullDraft.copy(title = "Купить хлеб и молоко").hasChangesComparedTo(fullDraft))
            .isTrue()
        assertThat(fullDraft.copy(tagIds = setOf("tag-a")).hasChangesComparedTo(fullDraft)).isTrue()
        assertThat(fullDraft.copy(status = TaskStatus.CANCELED).hasChangesComparedTo(fullDraft))
            .isTrue()
        // Дата статуса «задним числом» — тоже правка, даже если остальное не меняли.
        assertThat(fullDraft.copy(statusDateTs = null).hasChangesComparedTo(fullDraft)).isTrue()
    }

    /** Сохранение и чтение тем же Json, что и в приложении. */
    private fun roundTrip(draft: TaskDraft): TaskDraft {
        val json = TaskmanJson.encodeToString(TaskDraft.serializer(), draft)
        return TaskmanJson.decodeFromString(TaskDraft.serializer(), json)
    }
}
