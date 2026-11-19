package io.github.bigsprut.taskman.data.repo

import com.google.common.truth.Truth.assertThat
import io.github.bigsprut.taskman.domain.TaskDraft
import io.github.bigsprut.taskman.domain.TaskStatus
import io.github.bigsprut.taskman.testutil.taskEntity
import org.junit.Test

/**
 * Payload редактора задачи (ТЗ §4, §5.3-Ф2, §6).
 *
 * Сервер делает UPSERT полного объекта: чего нет в payload, то перезапишется дефолтом,
 * поэтому проверяется полнота. Отдельно фиксируются два правила редактора:
 * - `status_date` уходит **только** если пользователь выбрал дату «задним числом» (§4:
 *   лишний `status_date` сдвигает даты в истории статусов);
 * - новая подзадача идёт без `id` — идентификатор выдаёт сервер.
 */
class TaskPayloadDraftTest {

    /** Черновик со всеми заполненными полями: так видно, что payload не теряет ничего. */
    private val draft = TaskDraft(
        id = "task-1",
        listId = "list-1",
        parentId = "parent-1",
        title = "Купить хлеб",
        content = "**две** булки",
        status = TaskStatus.IN_PROGRESS,
        categoryId = "cat-1",
        tagIds = linkedSetOf("tag-b", "tag-a"),
        source = "Иван",
        createdAt = 1_700_000_000.0,
        completedAt = 1_700_000_400.0,
        statusDateTs = 1_700_000_500.0,
        updatedAt = 1_700_000_900.0,
    )

    @Test
    fun `fromDraft переносит все поля черновика`() {
        val payload = TaskPayload.fromDraft(draft)

        assertThat(payload.id).isEqualTo("task-1")
        assertThat(payload.listId).isEqualTo("list-1")
        assertThat(payload.parentId).isEqualTo("parent-1")
        assertThat(payload.title).isEqualTo("Купить хлеб")
        assertThat(payload.content).isEqualTo("**две** булки")
        // В JSON уходит значение провода (`inprogress`), а не имя enum: контракт §6.
        assertThat(payload.status).isEqualTo(TaskStatus.IN_PROGRESS.wireValue)
        assertThat(payload.categoryId).isEqualTo("cat-1")
        assertThat(payload.source).isEqualTo("Иван")
        assertThat(payload.createdAt).isEqualTo(1_700_000_000.0)
        assertThat(payload.completedAt).isEqualTo(1_700_000_400.0)
        assertThat(payload.statusDate).isEqualTo(1_700_000_500.0)
        // `updated_at` — версия из снапшота, по ней сервер проверяет CAS (§4).
        assertThat(payload.updatedAt).isEqualTo(1_700_000_900.0)
    }

    @Test
    fun `fromDraft отдаёт теги отсортированными`() {
        // Порядок в Set не определён, а сервер заменяет набор целиком: без сортировки
        // тело запроса менялось бы от запуска к запуску и мешало сравнивать ответы/логи.
        val payload = TaskPayload.fromDraft(draft.copy(tagIds = linkedSetOf("tag-c", "tag-a", "tag-b")))

        assertThat(payload.tags).containsExactly("tag-a", "tag-b", "tag-c").inOrder()
    }

    @Test
    fun `fromDraft без выбранной даты не передаёт status_date`() {
        // Дату статуса не выбирали — значит поле не отправляем совсем (в JSON его не будет
        // из-за `explicitNulls = false`), сервер сам проставит дату смены статуса.
        val payload = TaskPayload.fromDraft(draft.copy(statusDateTs = null))

        assertThat(payload.statusDate).isNull()
    }

    @Test
    fun `fromDraft с выбранной датой передаёт ровно её в unix-секундах`() {
        // «Задним числом»: дата должна дойти до сервера без пересчёта в миллисекунды,
        // иначе история статусов уедет на десятки лет назад (§4).
        val chosen = 1_600_000_000.0

        val payload = TaskPayload.fromDraft(draft.copy(statusDateTs = chosen))

        assertThat(payload.statusDate).isEqualTo(chosen)
    }

    @Test
    fun `newSubtask создаёт payload без id и с родителем из контекста`() {
        val payload = TaskPayload.newSubtask(
            listId = "list-1",
            parentId = "parent-1",
            title = "Подзадача",
        )

        // `id = null` — сервер выдаст свой; иначе UPSERT попытался бы обновить чужую задачу.
        assertThat(payload.id).isNull()
        assertThat(payload.parentId).isEqualTo("parent-1")
        assertThat(payload.listId).isEqualTo("list-1")
        assertThat(payload.title).isEqualTo("Подзадача")
        assertThat(payload.status).isEqualTo(TaskStatus.CREATED.wireValue)
        assertThat(payload.tags).isEmpty()
        // У новой задачи версии для CAS ещё нет, а даты статуса и завершения ставит сервер.
        assertThat(payload.updatedAt).isNull()
        assertThat(payload.statusDate).isNull()
        assertThat(payload.completedAt).isNull()
    }

    @Test
    fun `fromEntity с новым названием переопределяет только название`() {
        val entity = taskEntity(
            id = "task-1",
            listId = "list-1",
            parentId = "parent-1",
            categoryId = "cat-1",
            title = "Старое название",
            content = "описание",
            status = "completed",
            source = "Иван",
            createdAt = 1_700_000_000.0,
            updatedAt = 1_700_000_900.0,
            completedAt = 1_700_000_400.0,
        )

        val payload = TaskPayload.fromEntity(entity, listOf("tag-a"), title = "новое")

        // Инлайн-переименование подзадачи: меняется название, остальное — как в снапшоте,
        // иначе UPSERT затрёт поля, которых в редакторе подзадачи нет.
        assertThat(payload.title).isEqualTo("новое")
        assertThat(payload.id).isEqualTo("task-1")
        assertThat(payload.listId).isEqualTo("list-1")
        assertThat(payload.parentId).isEqualTo("parent-1")
        assertThat(payload.categoryId).isEqualTo("cat-1")
        assertThat(payload.content).isEqualTo("описание")
        assertThat(payload.status).isEqualTo("completed")
        assertThat(payload.source).isEqualTo("Иван")
        assertThat(payload.tags).containsExactly("tag-a")
        assertThat(payload.createdAt).isEqualTo(1_700_000_000.0)
        assertThat(payload.completedAt).isEqualTo(1_700_000_400.0)
        assertThat(payload.updatedAt).isEqualTo(1_700_000_900.0)
        assertThat(payload.statusDate).isNull()
    }
}
