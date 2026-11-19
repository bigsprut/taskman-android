package io.github.bigsprut.taskman.data.repo

import io.github.bigsprut.taskman.data.remote.ApiFailure
import io.github.bigsprut.taskman.data.remote.TaskmanApi
import io.github.bigsprut.taskman.domain.Employee
import io.github.bigsprut.taskman.domain.StatusOrder
import io.github.bigsprut.taskman.domain.Task
import io.github.bigsprut.taskman.domain.TaskDraft
import io.github.bigsprut.taskman.domain.TaskHistoryEntry
import io.github.bigsprut.taskman.domain.TaskStatus
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException

/** Итог синхронизации `/api/state` — то, на что опирается навигация приложения. */
sealed interface SyncOutcome {
    data class Success(val taskCount: Int) : SyncOutcome

    /** 401: токен больше не действует → экран входа. */
    data object Unauthorized : SyncOutcome

    /** 403 `device`: устройство ждёт подтверждения → экран ожидания с автоповтором (§4). */
    data object DeviceNotApproved : SyncOutcome

    /** Любой сбой: нет сети, 4xx/5xx, неразбираемый ответ, внутренняя ошибка. */
    data class Failed(val failure: ApiFailure) : SyncOutcome
}

/**
 * Состояние синхронизации для UI: спиннер обновления, наличие данных и последняя ошибка
 * (отдельно — «нет данных» и «нет сети», §5.2).
 */
data class SyncStatus(
    val isRefreshing: Boolean = false,
    /** Был ли хоть один запуск синхронизации: отличает «ещё грузим» от «пусто». */
    val hasAttempted: Boolean = false,
    val lastFailure: ApiFailure? = null,
    val lastSuccessAtMillis: Long? = null,
)

/**
 * Репозиторий задач: единственная точка синхронизации с сервером и чтения из кэша.
 *
 * @param now источник времени (подменяется в тестах).
 */
class TaskRepository(
    private val api: TaskmanApi,
    private val stateStore: StateStore,
    private val now: () -> Long = System::currentTimeMillis,
    /** Очередь неотправленных мутаций: чистится вместе с кэшем при выходе (Ф4). */
    private val offlineQueue: OfflineMutationQueue? = null,
) {

    /**
     * Синхронизация и очистка сериализуются одним мьютексом: параллельный pull-to-refresh
     * не дублирует запрос, а выход не оставляет «догоняющий» запрос, который после очистки
     * снова наполнит кэш данными другого сервера.
     */
    private val syncMutex = Mutex()

    private val _syncStatus = MutableStateFlow(SyncStatus())
    val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()

    /**
     * События авторизации — из синхронизации и из мутаций. Отдельный поток, потому что
     * мутация с 401 обязана увести пользователя на вход так же, как это делает polling.
     */
    private val _authFailures = MutableSharedFlow<ApiFailure>(
        extraBufferCapacity = AUTH_EVENTS_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val authFailures: SharedFlow<ApiFailure> = _authFailures.asSharedFlow()

    /** Задачи для списка, уже в порядке [StatusOrder]. */
    fun observeTasks(): Flow<List<Task>> = stateStore.observeTasks().map(StatusOrder::sort)

    /** Категория по умолчанию из настроек инстанса (§5.3-Ф5). */
    fun observeDefaultCategoryId(): Flow<String?> = stateStore.observeDefaultCategoryId()

    /** Снапшот для экспорта в markdown (§5.3-Ф5) — читает Room напрямую, без сервера. */
    suspend fun loadExportSnapshot(): io.github.bigsprut.taskman.data.export.ExportSnapshot =
        stateStore.loadExportSnapshot()

    /** Справочник сотрудников — для поля «сотрудник» в редакторе (§5.3-Ф2). */
    fun observeEmployees(): Flow<List<Employee>> = stateStore.observeEmployees()

    /** История статусов задачи — для блока «История» в редакторе. */
    fun observeTaskHistory(taskId: String): Flow<List<TaskHistoryEntry>> =
        stateStore.observeTaskHistory(taskId)

    /**
     * Черновик задачи для редактора из локального снапшота.
     *
     * Редактор не читает состояние сервера напрямую: пока лист открыт, фоновый `refresh()`
     * может обновить базу, но черновик остаётся нетронутым (регресс из §9-Ф2).
     */
    suspend fun loadDraft(taskId: String): TaskDraft? {
        val snapshot = stateStore.loadTaskDraft(taskId) ?: return null
        val entity = snapshot.entity
        return TaskDraft(
            id = entity.id,
            listId = entity.listId,
            parentId = entity.parentId,
            title = entity.title,
            content = entity.content,
            status = TaskStatus.fromWireValue(entity.status),
            categoryId = entity.categoryId,
            tagIds = snapshot.tagIds.toSet(),
            source = entity.source,
            createdAt = entity.createdAt,
            completedAt = entity.completedAt,
            statusDateTs = null,
            updatedAt = entity.updatedAt,
        )
    }

    /**
     * Забирает `/api/state` и атомарно заменяет локальный снапшот.
     *
     * 401 и 403 `device` возвращаются отдельными исходами: это не «ошибка загрузки», а
     * состояния авторизации, и UI реагирует на них по-разному (§6).
     */
    suspend fun refresh(): SyncOutcome = syncMutex.withLock {
        _syncStatus.update { it.copy(isRefreshing = true, hasAttempted = true) }

        val outcome = try {
            fetchAndStore()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (io: IOException) {
            SyncOutcome.Failed(ApiFailure.Network(io))
        } catch (serialization: SerializationException) {
            SyncOutcome.Failed(ApiFailure.Malformed(serialization))
        } catch (error: Exception) {
            // Сбой хранилища/диска — не «странный ответ сервера», показываем как внутреннюю
            // ошибку, а не маскируем под расхождение контракта.
            SyncOutcome.Failed(ApiFailure.Internal(error))
        } finally {
            _syncStatus.update { it.copy(isRefreshing = false) }
        }

        _syncStatus.update { status ->
            when (outcome) {
                is SyncOutcome.Success -> status.copy(lastFailure = null, lastSuccessAtMillis = now())
                SyncOutcome.Unauthorized -> status.copy(lastFailure = ApiFailure.Unauthorized)
                SyncOutcome.DeviceNotApproved -> status.copy(lastFailure = ApiFailure.DeviceNotApproved)
                is SyncOutcome.Failed -> status.copy(lastFailure = outcome.failure)
            }
        }
        when (outcome) {
            SyncOutcome.Unauthorized -> _authFailures.tryEmit(ApiFailure.Unauthorized)
            SyncOutcome.DeviceNotApproved -> _authFailures.tryEmit(ApiFailure.DeviceNotApproved)
            else -> Unit
        }
        outcome
    }

    /**
     * Удаляет локальные данные при выходе или смене сервера.
     *
     * Берёт тот же мьютекс, что и [refresh]: очистка ждёт завершения уже идущего запроса и
     * происходит после него, поэтому «догоняющий» ответ не может снова наполнить кэш.
     */
    suspend fun clearLocalData() = syncMutex.withLock {
        offlineQueue?.clear()
        stateStore.clear()
        _syncStatus.value = SyncStatus()
    }

    /** Сообщить сессии об отказе авторизации, полученном вне [refresh] (мутации). */
    fun reportAuthFailure(failure: ApiFailure) {
        _authFailures.tryEmit(failure)
    }

    private suspend fun fetchAndStore(): SyncOutcome {
        val response = api.getState()
        if (!response.isSuccessful) {
            return when (val failure = ApiFailure.fromHttpResponse(response.code(), response.errorBody())) {
                ApiFailure.Unauthorized -> SyncOutcome.Unauthorized
                ApiFailure.DeviceNotApproved -> SyncOutcome.DeviceNotApproved
                else -> SyncOutcome.Failed(failure)
            }
        }

        val body = response.body()
        // `server_time` есть в каждом настоящем снапшоте (§6), поэтому его отсутствие —
        // признак «не тот ответ»: 200 с `{}` или JSON ошибки от прокси не должен стирать кэш.
        if (body == null || body.serverTime == null) {
            return SyncOutcome.Failed(
                ApiFailure.Malformed(
                    IllegalStateException("/api/state payload is not a full state snapshot"),
                ),
            )
        }

        stateStore.replaceSnapshot(StateMapper.toSnapshot(body))
        return SyncOutcome.Success(body.tasks.size)
    }

    private companion object {
        const val AUTH_EVENTS_BUFFER = 8
    }
}
