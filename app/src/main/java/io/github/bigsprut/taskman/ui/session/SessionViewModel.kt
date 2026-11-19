package io.github.bigsprut.taskman.ui.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.bigsprut.taskman.data.remote.ApiFailure
import io.github.bigsprut.taskman.data.remote.UrlNormalizer
import io.github.bigsprut.taskman.data.repo.SyncOutcome
import io.github.bigsprut.taskman.data.repo.TaskRepository
import io.github.bigsprut.taskman.data.settings.ConnectionRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Состояние подключения, которое определяет, какой экран показывать. */
sealed interface SessionUiState {

    /** Настройки читаются с диска (первый кадр). */
    data object Restoring : SessionUiState

    /** Токена нет или сервер его отверг — экран входа. */
    data object NeedsLogin : SessionUiState

    /** 403 `device`: устройство ждёт подтверждения на сервере (автоповтор раз в 30 с, §4). */
    data class DeviceApprovalPending(val deviceId: String) : SessionUiState

    /** Можно показывать список. */
    data object Ready : SessionUiState
}

/** Ошибка попытки входа — текст подбирает UI (строки в ресурсах, §5.4). */
sealed interface LoginError {
    data object InvalidUrl : LoginError
    data object EmptyToken : LoginError
    data object Unauthorized : LoginError
    data object Network : LoginError
    data object UnexpectedData : LoginError

    /** Токен принят сервером, но сохранить его на устройстве не удалось. */
    data object Storage : LoginError
    data class Http(val code: Int) : LoginError
}

/** Состояние формы входа. */
data class LoginUiState(
    /** Подставляется в поле адреса: из настроек или BuildConfig-placeholder. */
    val prefillBaseUrl: String = "",
    val isBusy: Boolean = false,
    val error: LoginError? = null,
)

/** Данные о подключении для экрана настроек. */
data class ConnectionUiModel(
    val baseUrl: String,
    val deviceId: String,
)

/**
 * Авторизация и привязка устройства (Ф0).
 *
 * Навигация выводится из [TaskRepository.syncStatus], а не из результата конкретного вызова:
 * `/api/state` вызывается и при старте, и по pull-to-refresh, и в цикле ожидания устройства —
 * и в любом случае 401/403 должны привести пользователя на правильный экран.
 *
 * @param allowCleartextBaseUrl `true` только в debug-сборке: локальный стенд работает по http.
 */
class SessionViewModel(
    private val connections: ConnectionRepository,
    private val taskRepository: TaskRepository,
    private val allowCleartextBaseUrl: Boolean,
) : ViewModel() {

    private val _state = MutableStateFlow<SessionUiState>(SessionUiState.Restoring)
    val state: StateFlow<SessionUiState> = _state.asStateFlow()

    private val _login = MutableStateFlow(LoginUiState())
    val login: StateFlow<LoginUiState> = _login.asStateFlow()

    private val _connection = MutableStateFlow<ConnectionUiModel?>(null)
    val connection: StateFlow<ConnectionUiModel?> = _connection.asStateFlow()

    init {
        restoreConnection()
        observeAuthFailures()
        observeDeviceApproval()
    }

    /**
     * Вход: адрес и токен сначала проверяются сервером и только потом сохраняются.
     *
     * `403 device` тоже считается успехом проверки: сервер узнал токен, но ждёт подтверждения
     * устройства — такой токен уже можно хранить.
     */
    fun login(rawBaseUrl: String, rawToken: String) {
        val baseUrl = UrlNormalizer.normalize(rawBaseUrl)
        val token = rawToken.trim()

        if (!UrlNormalizer.isValid(baseUrl, allowCleartext = allowCleartextBaseUrl)) {
            _login.update { it.copy(error = LoginError.InvalidUrl, isBusy = false) }
            return
        }
        if (token.isEmpty()) {
            _login.update { it.copy(error = LoginError.EmptyToken, isBusy = false) }
            return
        }
        if (_login.value.isBusy) return

        viewModelScope.launch {
            _login.update { it.copy(isBusy = true, error = null) }

            // Токен живёт в памяти до подтверждения сервером.
            connections.stageAttempt(baseUrl, token)
            when (val outcome = taskRepository.refresh()) {
                is SyncOutcome.Success, SyncOutcome.DeviceNotApproved -> {
                    val settings = runCatching { connections.persist(baseUrl, token) }.getOrNull()
                    if (settings == null) {
                        // Сервер токен принял, но на диск записать не смогли — не делаем вид,
                        // что вход сохранён: после перезапуска токена не будет.
                        restorePersistedConnection()
                        _login.update { it.copy(isBusy = false, error = LoginError.Storage) }
                        return@launch
                    }
                    _login.update {
                        it.copy(isBusy = false, prefillBaseUrl = settings.baseUrl, error = null)
                    }
                    _connection.value = ConnectionUiModel(settings.baseUrl, settings.deviceId)
                    _state.value = if (outcome == SyncOutcome.DeviceNotApproved) {
                        SessionUiState.DeviceApprovalPending(settings.deviceId)
                    } else {
                        SessionUiState.Ready
                    }
                }

                SyncOutcome.Unauthorized -> {
                    restorePersistedConnection()
                    _login.update { it.copy(isBusy = false, error = LoginError.Unauthorized) }
                }

                is SyncOutcome.Failed -> {
                    restorePersistedConnection()
                    _login.update { it.copy(isBusy = false, error = outcome.failure.toLoginError()) }
                }
            }
        }
    }

    /** Ручная проверка с экрана ожидания устройства (автоповтор делает сам экран). */
    fun retryDeviceCheck() {
        viewModelScope.launch { taskRepository.refresh() }
    }

    /** Выход: токен и локальный кэш удаляются, возвращаемся к экрану входа. */
    fun logout() {
        viewModelScope.launch {
            // Ошибку чтения/записи настроек не превращаем в зависание: в любом случае
            // показываем экран входа, а кэш пытаемся очистить.
            runCatching { connections.forgetToken() }
            runCatching { taskRepository.clearLocalData() }
            _login.update { LoginUiState(prefillBaseUrl = it.prefillBaseUrl, isBusy = false, error = null) }
            _state.value = SessionUiState.NeedsLogin
        }
    }

    private fun restoreConnection() {
        viewModelScope.launch {
            val settings = runCatching { connections.load() }.getOrNull()
            if (settings == null) {
                // Настройки не читаются (повреждённый файл/Keystore): corruptionHandler
                // восстановит DataStore, а пользователь войдёт заново.
                _state.value = SessionUiState.NeedsLogin
                return@launch
            }

            _connection.value = ConnectionUiModel(settings.baseUrl, settings.deviceId)
            _login.update { it.copy(prefillBaseUrl = settings.baseUrl) }

            if (settings.token == null) {
                _state.value = SessionUiState.NeedsLogin
                return@launch
            }

            // Room — источник для UI: список показывается сразу из кэша, синхронизация идёт
            // следом и сама переведёт приложение на нужный экран при 401/403.
            _state.value = SessionUiState.Ready
            taskRepository.refresh()
        }
    }

    /**
     * Отказы авторизации приходят и из синхронизации, и из мутаций — реакция одна:
     * 401 → вход, 403 `device` → ожидание подтверждения. 401 ловится и с экрана ожидания:
     * автоповтор там идёт раз в 30 с, и отозванный токен не должен запирать пользователя.
     */
    private fun observeAuthFailures() {
        viewModelScope.launch {
            taskRepository.authFailures.collect { failure ->
                val current = _state.value
                when {
                    failure is ApiFailure.Unauthorized &&
                        (current is SessionUiState.Ready ||
                            current is SessionUiState.DeviceApprovalPending) -> {
                        // Токен отозван или сервер сбросили: кэш не подтверждён — чистим.
                        runCatching { connections.forgetToken() }
                        runCatching { taskRepository.clearLocalData() }
                        _state.value = SessionUiState.NeedsLogin
                    }

                    failure is ApiFailure.DeviceNotApproved && current is SessionUiState.Ready -> {
                        _state.value = SessionUiState.DeviceApprovalPending(
                            deviceId = connections.currentDeviceId().orEmpty(),
                        )
                    }
                }
            }
        }
    }

    /** Устройство дождалось подтверждения: сервер ответил успехом — пускаем в список. */
    private fun observeDeviceApproval() {
        viewModelScope.launch {
            taskRepository.syncStatus.collect { status ->
                if (status.lastFailure == null &&
                    status.lastSuccessAtMillis != null &&
                    _state.value is SessionUiState.DeviceApprovalPending
                ) {
                    _state.value = SessionUiState.Ready
                }
            }
        }
    }

    /** Возвращает сетевому слою сохранённые значения (после неудачной попытки входа). */
    private suspend fun restorePersistedConnection() {
        val persisted = runCatching { connections.load() }.getOrNull() ?: return
        _connection.value = ConnectionUiModel(persisted.baseUrl, persisted.deviceId)
    }

    private fun ApiFailure.toLoginError(): LoginError = when (this) {
        is ApiFailure.Http -> LoginError.Http(code)
        is ApiFailure.Network -> LoginError.Network
        is ApiFailure.Malformed -> LoginError.UnexpectedData
        is ApiFailure.Conflict -> LoginError.UnexpectedData
        is ApiFailure.Internal -> LoginError.UnexpectedData
        ApiFailure.InvalidInput -> LoginError.UnexpectedData
        ApiFailure.FileAccess -> LoginError.UnexpectedData
        ApiFailure.Unauthorized -> LoginError.Unauthorized
        ApiFailure.DeviceNotApproved -> LoginError.Network
    }
}
