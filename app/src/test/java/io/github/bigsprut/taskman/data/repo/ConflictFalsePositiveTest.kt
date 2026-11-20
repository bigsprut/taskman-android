package io.github.bigsprut.taskman.data.repo

import com.google.common.truth.Truth.assertThat
import io.github.bigsprut.taskman.data.remote.dto.SavedTaskDto
import io.github.bigsprut.taskman.data.remote.dto.TaskInDto
import io.github.bigsprut.taskman.data.remote.dto.UpsertTasksResponse
import io.github.bigsprut.taskman.domain.TaskStatus
import io.github.bigsprut.taskman.testutil.FakeLocalTaskWriter
import io.github.bigsprut.taskman.testutil.FakePendingMutationDao
import io.github.bigsprut.taskman.testutil.FakeStateStore
import io.github.bigsprut.taskman.testutil.FakeTaskmanApi
import io.github.bigsprut.taskman.testutil.errorResponseOf
import io.github.bigsprut.taskman.testutil.successResponse
import io.github.bigsprut.taskman.testutil.taskEntity
import kotlinx.coroutines.test.runTest
import org.junit.Test
import retrofit2.Response

/**
 * Ложные 409 «задачу изменили на другом устройстве» (Ф7).
 *
 * Живой баг: после успешной отправки сервер возвращает новую версию задачи в `saved`, а клиент
 * её не запоминал. Следующая правка уходила со старой `updated_at` и получала 409 — хотя задачу
 * менял он сам, а не другое устройство.
 *
 * Здесь проверяются оба механизма исправления:
 * 1. версия из ответа применяется локально (`LocalTaskWriter.applyServerVersion`) и запоминается
 *    в `acknowledgedVersions`, а 409 с этой же версией означает «конфликт сам с собой» — он
 *    перечитывает состояние и один раз молча повторяется;
 * 2. no-op автосейв черновика: если полей не коснулись, upsert не уходит и очередь не пополняется.
 *
 * Сервер и хранилище подставные, как в [TaskMutationQueueTest]: проверяется диспетчер мутаций,
 * а не Room или Retrofit. `FakeLocalTaskWriter` и `FakeStateStore` в тесте — разные объекты,
 * поэтому там, где важно «версия дошла до Room», они связываются явно ([linkWriterToReader]).
 */
class ConflictFalsePositiveTest {

    private val api = FakeTaskmanApi()
    private val store = FakeStateStore()
    private val tasks = TaskRepository(api = api, stateStore = store, now = { NOW_MILLIS })
    private val dao = FakePendingMutationDao()
    private val queue = OfflineMutationQueue(dao)
    private val writer = FakeLocalTaskWriter()

    /** Сколько раз просили фоновую отправку: no-op автосейв не должен её провоцировать. */
    private var backgroundSyncCalls: Int = 0

    /** Подменяемые часы: версии в тестах не должны зависеть от системного времени. */
    private var nowMillis: Long = NOW_MILLIS

    private val mutations = TaskMutationRepository(
        api = api,
        reader = store,
        tasks = tasks,
        queue = queue,
        writer = writer,
        now = { nowMillis },
        requestBackgroundSync = { backgroundSyncCalls += 1 },
    )

    /** Задача в снапшоте: версия [STALE_VERSION] — та, с которой ушёл бы CAS без Ф7. */
    private val entity = taskEntity(
        id = TASK_ID,
        listId = "list-1",
        title = "Помыть посуду",
        content = "с вечера",
        status = "created",
        source = "Иван",
        createdAt = 1_765_800_000.0,
        updatedAt = STALE_VERSION,
        statusDateTs = STATUS_DATE,
    )

    // --- 1. Регресс из живого бага: версия из ответа сервера доходит до следующей правки ---

    @Test
    fun `регресс — после успеха следующая правка уходит с версией из ответа сервера`() = runTest {
        linkWriterToReader()
        store.putTask(entity, tagIds = listOf("tag-a"))
        val sent = mutableListOf<TaskInDto>()
        api.upsertOutcome = {
            sent += api.lastUpserted!!.single()
            // Первый ответ поднимает версию до 100.0 — её и обязана унести вторая правка.
            val version = if (sent.size == 1) SERVER_ACK_VERSION else NEXT_SERVER_VERSION
            successResponse(savedResponse(version))
        }

        assertThat(mutations.setCompleted(TASK_ID, completed = true))
            .isEqualTo(MutationOutcome.Success)
        // Версия из `saved` применена локально — в бою её тут же видит следующая правка.
        assertThat(writer.applyServerVersionCalls).isEqualTo(1)
        assertThat(writer.serverVersions[TASK_ID]).isEqualTo(SERVER_ACK_VERSION)

        assertThat(mutations.setCompleted(TASK_ID, completed = false))
            .isEqualTo(MutationOutcome.Success)

        // Раньше вторая правка несла старую версию и получала ложный 409 от собственного же
        // изменения. Теперь payload берёт версию, которую сервер подтвердил на первую отправку.
        assertThat(sent).hasSize(2)
        assertThat(sent[0].updatedAt).isEqualTo(STALE_VERSION)
        assertThat(sent[1].updatedAt).isEqualTo(SERVER_ACK_VERSION)
        assertThat(sent[1].updatedAt).isNotEqualTo(STALE_VERSION)
        assertThat(queue.pending()).isEmpty()
    }

    // --- 2-4. 409 с нашей версией: один тихий повтор и корректный исход ---

    @Test
    fun `409 с нашей подтверждённой версией повторяется молча и даёт успех`() = runTest {
        store.putTask(entity, tagIds = listOf("tag-a"))
        // Сервер уже подтверждал нашу мутацию версией 100.0.
        api.upsertOutcome = { successResponse(savedResponse(SERVER_ACK_VERSION)) }
        assertThat(mutations.setCompleted(TASK_ID, completed = true))
            .isEqualTo(MutationOutcome.Success)
        assertThat(queue.pending()).isEmpty()

        // В очереди осталась запись со старой версией: она поставлена раньше, чем сервер
        // подтвердил нашу новую. Это и есть «конфликт сам с собой».
        enqueueStaleUpsert(STALE_QUEUED_VERSION)
        assertThat(queue.pending().single().baseUpdatedAt).isEqualTo(STALE_QUEUED_VERSION)

        val attempt = api.upsertCalls + 1
        api.upsertOutcome = {
            if (api.upsertCalls == attempt) {
                conflictResponse(SERVER_ACK_VERSION)
            } else {
                successResponse(savedResponse(NEXT_SERVER_VERSION))
            }
        }
        val callsBeforeFlush = api.calls

        val outcome = mutations.flush()

        assertThat(outcome).isEqualTo(MutationOutcome.Success)
        assertThat(queue.pending()).isEmpty()
        // Ровно две попытки: сама отправка и один тихий повтор после рефетча.
        assertThat(api.upsertCalls).isEqualTo(attempt + 1)
        assertThat(api.lastUpserted!!.single().updatedAt).isEqualTo(SERVER_ACK_VERSION)
        // Рефетч перед повтором и штатный рефетч после успешного flush.
        assertThat(api.calls - callsBeforeFlush).isEqualTo(2)
        assertThat(writer.serverVersions[TASK_ID]).isEqualTo(NEXT_SERVER_VERSION)
    }

    @Test
    fun `повторный 409 после тихого повтора отдаёт конфликт и чистит очередь`() = runTest {
        store.putTask(entity)
        api.upsertOutcome = { successResponse(savedResponse(SERVER_ACK_VERSION)) }
        assertThat(mutations.setCompleted(TASK_ID, completed = true))
            .isEqualTo(MutationOutcome.Success)
        enqueueStaleUpsert(STALE_QUEUED_VERSION)

        val attempt = api.upsertCalls + 1
        // Оба ответа — 409: повтором конфликт не лечится, дальше — сообщение пользователю.
        api.upsertOutcome = { conflictResponse(SERVER_ACK_VERSION) }
        val callsBeforeFlush = api.calls

        val outcome = mutations.flush()

        assertThat(outcome).isInstanceOf(MutationOutcome.Conflict::class.java)
        assertThat((outcome as MutationOutcome.Conflict).actualUpdatedAt)
            .isEqualTo(SERVER_ACK_VERSION)
        assertThat(queue.pending()).isEmpty()
        // Больше одной повторной отправки не делаем: иначе очередь крутилась бы бесконечно.
        assertThat(api.upsertCalls).isEqualTo(attempt + 1)
        // Рефетч перед повтором и рефетч после второго 409.
        assertThat(api.calls - callsBeforeFlush).isEqualTo(2)
    }

    @Test
    fun `повторный 409 с чужой версией отдаёт её в конфликте`() = runTest {
        store.putTask(entity)
        api.upsertOutcome = { successResponse(savedResponse(SERVER_ACK_VERSION)) }
        assertThat(mutations.setCompleted(TASK_ID, completed = true))
            .isEqualTo(MutationOutcome.Success)
        enqueueStaleUpsert(STALE_QUEUED_VERSION)

        val attempt = api.upsertCalls + 1
        api.upsertOutcome = {
            if (api.upsertCalls == attempt) {
                conflictResponse(SERVER_ACK_VERSION)
            } else {
                // Пока мы повторяли, задачу успели изменить ещё раз — версия уже чужая.
                conflictResponse(FOREIGN_VERSION)
            }
        }

        val outcome = mutations.flush()

        assertThat(outcome).isInstanceOf(MutationOutcome.Conflict::class.java)
        assertThat((outcome as MutationOutcome.Conflict).actualUpdatedAt)
            .isEqualTo(FOREIGN_VERSION)
        assertThat(queue.pending()).isEmpty()
        assertThat(api.upsertCalls).isEqualTo(attempt + 1)
    }

    // --- 5-7. Настоящий конфликт: повтор не делается ---

    @Test
    fun `чужая версия без подтверждённой своей — сразу конфликт без повтора`() = runTest {
        store.putTask(entity)
        api.upsertOutcome = { conflictResponse(FOREIGN_VERSION) }

        val outcome = mutations.setCompleted(TASK_ID, completed = true)

        assertThat(outcome).isInstanceOf(MutationOutcome.Conflict::class.java)
        assertThat((outcome as MutationOutcome.Conflict).actualUpdatedAt)
            .isEqualTo(FOREIGN_VERSION)
        // Доказать «свой» конфликт нечем: сервер эту версию нам не подтверждал.
        assertThat(api.upsertCalls).isEqualTo(1)
        assertThat(queue.pending()).isEmpty()
        assertThat(api.calls).isEqualTo(1)
    }

    @Test
    fun `чужая версия при подтверждённой своей — тоже сразу конфликт`() = runTest {
        store.putTask(entity)
        api.upsertOutcome = { successResponse(savedResponse(SERVER_ACK_VERSION)) }
        assertThat(mutations.setCompleted(TASK_ID, completed = true))
            .isEqualTo(MutationOutcome.Success)

        api.upsertOutcome = { conflictResponse(FOREIGN_VERSION) }

        val outcome = mutations.setCompleted(TASK_ID, completed = false)

        assertThat(outcome).isInstanceOf(MutationOutcome.Conflict::class.java)
        assertThat((outcome as MutationOutcome.Conflict).actualUpdatedAt)
            .isEqualTo(FOREIGN_VERSION)
        // Наша версия 100.0 не совпала с фактической 777.0 — это реально другое устройство.
        assertThat(api.upsertCalls).isEqualTo(2)
        assertThat(queue.pending()).isEmpty()
    }

    @Test
    fun `409 на удалении не повторяется даже с нашей версией`() = runTest {
        store.putTask(entity)
        api.upsertOutcome = { successResponse(savedResponse(SERVER_ACK_VERSION)) }
        assertThat(mutations.setCompleted(TASK_ID, completed = true))
            .isEqualTo(MutationOutcome.Success)

        queue.enqueueDelete(TASK_ID, nowSeconds = NOW_SECONDS)
        api.deleteOutcome = { conflictResponse(SERVER_ACK_VERSION) }

        val outcome = mutations.flush()

        assertThat(outcome).isInstanceOf(MutationOutcome.Conflict::class.java)
        assertThat((outcome as MutationOutcome.Conflict).actualUpdatedAt)
            .isEqualTo(SERVER_ACK_VERSION)
        // У DELETE нет payload с версией: повторять его «с actual_updated_at» нечего.
        assertThat(api.deleteCalls).isEqualTo(1)
        assertThat(api.upsertCalls).isEqualTo(1)
        assertThat(queue.pending()).isEmpty()
    }

    // --- 8-13. No-op автосейв черновика ---

    @Test
    fun `no-op автосейв не ходит в сеть и не пополняет очередь`() = runTest {
        store.putTask(entity, tagIds = listOf("tag-b", "tag-a"))
        // Редактор открыли и закрыли, ничего не меняя: черновик совпадает со снапшотом.
        val draft = tasks.loadDraft(TASK_ID)!!

        val outcome = mutations.saveDraft(draft)

        assertThat(outcome).isEqualTo(MutationOutcome.Success)
        assertThat(api.upsertCalls).isEqualTo(0)
        assertThat(api.calls).isEqualTo(0)
        assertThat(queue.pending()).isEmpty()
        // Локально тоже нечего применять: правки нет.
        assertThat(writer.upsertCalls).isEqualTo(0)
        // Пустой автосейв не должен даже просить фоновую отправку очереди.
        assertThat(backgroundSyncCalls).isEqualTo(0)
    }

    @Test
    fun `no-op не мешает правке — upsert уходит с новым заголовком и версией из ответа`() = runTest {
        store.putTask(entity)
        api.upsertOutcome = { successResponse(savedResponse(SERVER_ACK_VERSION)) }
        val draft = tasks.loadDraft(TASK_ID)!!.copy(title = "Купить молоко")

        val outcome = mutations.saveDraft(draft)

        assertThat(outcome).isEqualTo(MutationOutcome.Success)
        assertThat(api.upsertCalls).isEqualTo(1)
        assertThat(api.lastUpserted!!.single().title).isEqualTo("Купить молоко")
        assertThat(queue.pending()).isEmpty()
        // Версия из `saved` применена локально: следующее сохранение не получит ложный 409.
        assertThat(writer.applyServerVersionCalls).isEqualTo(1)
        assertThat(writer.serverVersions[TASK_ID]).isEqualTo(SERVER_ACK_VERSION)
    }

    @Test
    fun `теги в другом порядке — не изменение и upsert не уходит`() = runTest {
        store.putTask(entity, tagIds = listOf("tag-b", "tag-a"))
        val draft = tasks.loadDraft(TASK_ID)!!.copy(tagIds = linkedSetOf("tag-a", "tag-b"))

        val outcome = mutations.saveDraft(draft)

        assertThat(outcome).isEqualTo(MutationOutcome.Success)
        assertThat(api.upsertCalls).isEqualTo(0)
        assertThat(queue.pending()).isEmpty()
    }

    @Test
    fun `изменённый набор тегов — это правка и upsert уходит`() = runTest {
        store.putTask(entity, tagIds = listOf("tag-a"))
        val draft = tasks.loadDraft(TASK_ID)!!.copy(tagIds = setOf("tag-b"))

        val outcome = mutations.saveDraft(draft)

        assertThat(outcome).isEqualTo(MutationOutcome.Success)
        assertThat(api.upsertCalls).isEqualTo(1)
        assertThat(api.lastUpserted!!.single().tags).containsExactly("tag-b")
    }

    @Test
    fun `status_date, совпадающая с локальной, не считается изменением`() = runTest {
        store.putTask(entity)
        val draft = tasks.loadDraft(TASK_ID)!!.copy(statusDateTs = entity.statusDateTs)

        val outcome = mutations.saveDraft(draft)

        assertThat(outcome).isEqualTo(MutationOutcome.Success)
        // Дата та же — это не правка, поэтому upsert не нужен вовсе.
        assertThat(api.upsertCalls).isEqualTo(0)
    }

    @Test
    fun `другая status_date — это правка и upsert уходит`() = runTest {
        store.putTask(entity)
        val draft = tasks.loadDraft(TASK_ID)!!.copy(statusDateTs = OTHER_STATUS_DATE)

        val outcome = mutations.saveDraft(draft)

        assertThat(outcome).isEqualTo(MutationOutcome.Success)
        assertThat(api.upsertCalls).isEqualTo(1)
        assertThat(api.lastUpserted!!.single().statusDate).isEqualTo(OTHER_STATUS_DATE)
    }

    // --- 9-10. Версия для черновика и защита от пустого ответа ---

    @Test
    fun `latestVersion сначала берёт версию из ответа сервера, а потом из снапшота`() = runTest {
        store.putTask(entity)
        // До мутации запасной источник — снапшот.
        assertThat(mutations.latestVersion(TASK_ID)).isEqualTo(STALE_VERSION)

        api.upsertOutcome = { successResponse(savedResponse(SERVER_ACK_VERSION)) }
        assertThat(mutations.setCompleted(TASK_ID, completed = true))
            .isEqualTo(MutationOutcome.Success)

        // Снапшот в тесте отстаёт (writer и reader — разные объекты), а версия уже свежая:
        // её взяли из подтверждений сервера. В бою Room обновится тем же вызовом.
        assertThat(store.entities.getValue(TASK_ID).updatedAt).isEqualTo(STALE_VERSION)
        assertThat(mutations.latestVersion(TASK_ID)).isEqualTo(SERVER_ACK_VERSION)
    }

    @Test
    fun `пустой saved в ответе не ломает отправку`() = runTest {
        store.putTask(entity)
        api.upsertOutcome = { successResponse(UpsertTasksResponse()) }

        val outcome = mutations.setCompleted(TASK_ID, completed = true)

        assertThat(outcome).isEqualTo(MutationOutcome.Success)
        assertThat(queue.pending()).isEmpty()
        // Применять нечего — и не нужно: версия остаётся прежней.
        assertThat(writer.applyServerVersionCalls).isEqualTo(0)
        assertThat(mutations.latestVersion(TASK_ID)).isEqualTo(STALE_VERSION)
    }

    @Test
    fun `saved с null updated_at не ломает отправку и не пишет версию`() = runTest {
        store.putTask(entity)
        api.upsertOutcome = {
            successResponse(
                UpsertTasksResponse(saved = listOf(SavedTaskDto(id = TASK_ID, updatedAt = null))),
            )
        }

        val outcome = mutations.setCompleted(TASK_ID, completed = true)

        assertThat(outcome).isEqualTo(MutationOutcome.Success)
        assertThat(queue.pending()).isEmpty()
        assertThat(writer.applyServerVersionCalls).isEqualTo(0)
        assertThat(mutations.latestVersion(TASK_ID)).isEqualTo(STALE_VERSION)
    }

    @Test
    fun `409 без actual_updated_at — сразу конфликт без версии`() = runTest {
        store.putTask(entity)
        api.upsertOutcome = { conflictResponse(actualUpdatedAt = null) }

        val outcome = mutations.setCompleted(TASK_ID, completed = true)

        assertThat(outcome).isInstanceOf(MutationOutcome.Conflict::class.java)
        assertThat((outcome as MutationOutcome.Conflict).actualUpdatedAt).isNull()
        // Версии нет — «свой» конфликт не доказать, поэтому повтора тоже нет.
        assertThat(api.upsertCalls).isEqualTo(1)
        assertThat(queue.pending()).isEmpty()
    }

    @Test
    fun `409 с фактической версией в пределах допуска считается своим`() = runTest {
        store.putTask(entity)
        api.upsertOutcome = { successResponse(savedResponse(SERVER_ACK_VERSION)) }
        assertThat(mutations.setCompleted(TASK_ID, completed = true))
            .isEqualTo(MutationOutcome.Success)
        enqueueStaleUpsert(STALE_QUEUED_VERSION)

        val attempt = api.upsertCalls + 1
        api.upsertOutcome = {
            if (api.upsertCalls == attempt) {
                // Дробная unix-версия «чуть-чуть» отличается: допуск 1e-6 не должен превращать
                // нашу же версию в чужой конфликт.
                conflictResponse(SERVER_ACK_VERSION + VERSION_EPSILON_HALF)
            } else {
                successResponse(savedResponse(NEXT_SERVER_VERSION))
            }
        }

        val outcome = mutations.flush()

        assertThat(outcome).isEqualTo(MutationOutcome.Success)
        assertThat(queue.pending()).isEmpty()
        assertThat(api.upsertCalls).isEqualTo(attempt + 1)
    }

    /**
     * В бою `writer` и `reader` — одна и та же Room: версия из `saved` сразу видна следующей
     * правке. В тесте это разные объекты, поэтому связываем их явно.
     */
    private fun linkWriterToReader() {
        writer.onServerVersion = { taskId, version ->
            store.entities[taskId]?.let { current ->
                store.putTask(current.copy(updatedAt = version), store.tagsByTask[taskId].orEmpty())
            }
        }
    }

    /**
     * Кладёт в очередь «залежавшуюся» правку: она поставлена, когда версия задачи была ещё
     * старой, а сервер тем временем уже подтвердил нашу более новую версию (сценарий Ф7).
     */
    private suspend fun enqueueStaleUpsert(version: Double) {
        val current = store.entities.getValue(TASK_ID)
        val payload = TaskPayload.forStatusToggle(
            entity = current,
            tagIds = store.tagIds(TASK_ID),
            target = TaskStatus.COMPLETED,
        ).copy(updatedAt = version)
        queue.enqueueUpsert(payload, nowSeconds = NOW_SECONDS)
    }

    /** Ответ upsert с конкретной версией сохранённой задачи (`saved`, §6). */
    private fun savedResponse(version: Double): UpsertTasksResponse =
        UpsertTasksResponse(saved = listOf(SavedTaskDto(id = TASK_ID, updatedAt = version)))

    /** Неуспешный ответ upsert|delete: 409 с фактической версией (или без неё). */
    private fun <T> conflictResponse(actualUpdatedAt: Double?): Response<T> {
        val json = if (actualUpdatedAt == null) {
            """{"error":"conflict"}"""
        } else {
            """{"error":"conflict","actual_updated_at":$actualUpdatedAt}"""
        }
        return errorResponseOf(409, json)
    }

    private companion object {
        const val TASK_ID = "task-1"

        /** Версия в снапшоте до правки: с ней клиент ушёл бы в CAS без Ф7. */
        const val STALE_VERSION = 50.0

        /** Версия, которую сервер подтвердил на нашу мутацию (`saved[].updated_at`). */
        const val SERVER_ACK_VERSION = 100.0

        /** Версия следующего подтверждения — проверяем, что применяется последняя. */
        const val NEXT_SERVER_VERSION = 101.0

        /** Версия «другого устройства»: её мы не подтверждали, значит конфликт настоящий. */
        const val FOREIGN_VERSION = 777.0

        /** Старая версия «залежавшейся» записи очереди. */
        const val STALE_QUEUED_VERSION = 5.0

        /** Половина допуска сравнения версий (`1e-6` в репозитории) — всё ещё «наша» версия. */
        const val VERSION_EPSILON_HALF = 5e-7

        /** Дата статуса локальной задачи: она же не должна считаться изменением. */
        const val STATUS_DATE = 1_765_800_500.0

        /** Другая дата статуса — явная правка «задним числом». */
        const val OTHER_STATUS_DATE = 1_795_120_000.0

        const val NOW_MILLIS = 1_700_000_000_000L
        const val NOW_SECONDS = 1_700_000_000.0
    }
}
