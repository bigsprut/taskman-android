package io.github.bigsprut.taskman.data.repo

import com.google.common.truth.Truth.assertThat
import io.github.bigsprut.taskman.data.local.entity.PendingMutationEntity
import io.github.bigsprut.taskman.data.remote.TaskmanJson
import io.github.bigsprut.taskman.data.remote.dto.TaskInDto
import io.github.bigsprut.taskman.testutil.FakePendingMutationDao
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Очередь неотправленных мутаций (Ф4, ТЗ §5.3-Ф4): что и как копится, пока сети нет.
 *
 * Проверяются правила, от которых зависит корректность всей офлайн-работы:
 * - **одна запись на задачу** — повторные правки схлопываются, очередь не растёт от каждого
 *   нажатия чекбокса;
 * - **версия для CAS фиксируется при первой правке** и переезжает в payload: иначе после
 *   успешной отправки сервер поднимет `updated_at` и следующая отправка получила бы ложный 409;
 * - **удаление заменяет upsert** — отправлять устаревшую правку задачи, которую уже удалили, нельзя;
 * - **задачу без id ставить нельзя** — id выдаёт сервер, сопоставлять такую запись не с чем.
 *
 * DAO подставной ([FakePendingMutationDao]): правила очереди проверяются без Room.
 */
class OfflineMutationQueueTest {

    private val dao = FakePendingMutationDao()
    private val queue = OfflineMutationQueue(dao)

    @Test
    fun `enqueueUpsert создаёт одну запись с payload и версией из payload`() = runTest {
        val payload = taskIn(title = "Помыть посуду", updatedAt = VERSION)

        queue.enqueueUpsert(payload, nowSeconds = NOW)

        val record = queue.pending().single()
        assertThat(record.taskId).isEqualTo(TASK_ID)
        assertThat(record.kind).isEqualTo(PendingMutationEntity.KIND_UPSERT)
        // Версия для CAS берётся из payload: это updated_at из последнего /api/state (§4).
        assertThat(record.baseUpdatedAt).isEqualTo(VERSION)
        assertThat(record.createdAt).isEqualTo(NOW)

        // Payload разбирается обратно в TaskInDto и несёт ровно то, что отправится на сервер.
        val decoded = record.payload()
        assertThat(decoded.id).isEqualTo(TASK_ID)
        assertThat(decoded.listId).isEqualTo("list-1")
        assertThat(decoded.title).isEqualTo("Помыть посуду")
        assertThat(decoded.status).isEqualTo("created")
        assertThat(decoded.updatedAt).isEqualTo(VERSION)
    }

    @Test
    fun `повторный enqueueUpsert схлопывает запись и хранит версию первой правки`() = runTest {
        queue.enqueueUpsert(taskIn(title = "Первая версия", updatedAt = VERSION), nowSeconds = NOW)
        queue.enqueueUpsert(
            taskIn(title = "Вторая версия", updatedAt = VERSION + NEXT_VERSION_DELTA),
            nowSeconds = NOW + STEP,
        )

        val record = queue.pending().single()
        // Одна запись на задачу: два нажатия чекбокса не превращаются в два POST.
        assertThat(record.baseUpdatedAt).isEqualTo(VERSION)
        // Время постановки тоже от первой правки: очередь не «омолаживается» повторами.
        assertThat(record.createdAt).isEqualTo(NOW)

        val decoded = record.payload()
        // Содержимое — последняя полная версия задачи...
        assertThat(decoded.title).isEqualTo("Вторая версия")
        // ...а версия при ней — та, с которой задача пришла в очередь в первый раз.
        assertThat(decoded.updatedAt).isEqualTo(VERSION)
        assertThat(decoded.updatedAt).isEqualTo(record.baseUpdatedAt)
    }

    @Test
    fun `enqueueDelete после upsert заменяет запись на delete без payload`() = runTest {
        queue.enqueueUpsert(taskIn(updatedAt = VERSION), nowSeconds = NOW)

        queue.enqueueDelete(TASK_ID, nowSeconds = NOW + STEP)

        val record = queue.pending().single()
        assertThat(record.kind).isEqualTo(PendingMutationEntity.KIND_DELETE)
        // Удалению payload не нужен: на сервер уходит только путь.
        assertThat(record.payloadJson).isNull()
        // Версия и время первой записи сохраняются — это всё та же правка одной задачи.
        assertThat(record.baseUpdatedAt).isEqualTo(VERSION)
        assertThat(record.createdAt).isEqualTo(NOW)
    }

    @Test
    fun `enqueueUpsert без id не создаёт запись`() = runTest {
        queue.enqueueUpsert(taskIn(id = null), nowSeconds = NOW)

        // id новой задачи выдаёт сервер: поставить такую запись в очередь не с чем сопоставить.
        assertThat(queue.pending()).isEmpty()
        assertThat(queue.observePendingCount().first()).isEqualTo(0)
    }

    @Test
    fun `pending отдаёт записи в порядке постановки по createdAt`() = runTest {
        queue.enqueueUpsert(taskIn(id = "task-2", updatedAt = VERSION), nowSeconds = NOW + STEP)
        queue.enqueueUpsert(taskIn(id = "task-1", updatedAt = VERSION), nowSeconds = NOW)

        // Порядок отправки — как ORDER BY createdAt в Room, а не порядок вставки.
        assertThat(queue.pending().map { it.taskId }).containsExactly("task-1", "task-2").inOrder()
    }

    @Test
    fun `observePendingCount меняется от нуля до двух и обратно`() = runTest {
        // Баннер «есть неотправленные изменения» живёт на этом потоке (§5.3-Ф4).
        assertThat(queue.observePendingCount().first()).isEqualTo(0)

        queue.enqueueUpsert(taskIn(id = "task-1"), nowSeconds = NOW)
        assertThat(queue.observePendingCount().first()).isEqualTo(1)

        queue.enqueueUpsert(taskIn(id = "task-2"), nowSeconds = NOW + STEP)
        assertThat(queue.observePendingCount().first()).isEqualTo(2)

        queue.remove("task-1")
        queue.remove("task-2")
        assertThat(queue.observePendingCount().first()).isEqualTo(0)
    }

    @Test
    fun `remove снимает только свою запись`() = runTest {
        queue.enqueueUpsert(taskIn(id = "task-1"), nowSeconds = NOW)
        queue.enqueueUpsert(taskIn(id = "task-2"), nowSeconds = NOW + STEP)

        queue.remove("task-1")

        assertThat(queue.pending().map { it.taskId }).containsExactly("task-2")
        assertThat(dao.find("task-1")).isNull()
        assertThat(dao.find("task-2")).isNotNull()
    }

    @Test
    fun `clear очищает очередь`() = runTest {
        queue.enqueueUpsert(taskIn(id = "task-1"), nowSeconds = NOW)
        queue.enqueueDelete("task-2", nowSeconds = NOW + STEP)
        assertThat(queue.pending()).hasSize(2)

        queue.clear()

        assertThat(queue.pending()).isEmpty()
        assertThat(queue.observePendingCount().first()).isEqualTo(0)
    }

    /** Payload записи очереди в виде DTO: то же, что уйдёт на сервер при отправке. */
    private fun PendingMutationEntity.payload(): TaskInDto =
        TaskmanJson.decodeFromString(payloadJson!!)

    private companion object {
        const val TASK_ID = "task-1"
        const val VERSION = 1_765_843_200.0
        const val NEXT_VERSION_DELTA = 100.0

        /** Фиксированное «сейчас» в unix-секундах: тест не зависит от системных часов. */
        const val NOW = 1_700_000_000.0
        const val STEP = 5.0
    }
}

/** DTO задачи с минимальным набором полей для записи в очередь. */
private fun taskIn(
    id: String? = "task-1",
    title: String = "Задача",
    updatedAt: Double? = 1_765_843_200.0,
): TaskInDto = TaskInDto(
    id = id,
    listId = "list-1",
    title = title,
    status = "created",
    createdAt = 1_765_800_000.0,
    updatedAt = updatedAt,
)
