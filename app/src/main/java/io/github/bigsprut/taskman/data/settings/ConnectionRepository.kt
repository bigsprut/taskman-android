package io.github.bigsprut.taskman.data.settings

import io.github.bigsprut.taskman.data.remote.ConnectionHolder

/**
 * Связка «настройки на диске» ↔ «параметры в памяти для сетевого слоя».
 *
 * Правило, ради которого класс существует: **непроверенный токен не сохраняется**.
 * При входе токен сначала живёт только в памяти ([stageAttempt]) и попадает в DataStore
 * лишь после того, как сервер его принял — успешным `/api/state` или ответом
 * `403 {"error":"device"}` (это значит, что токен валиден, а устройство ждёт подтверждения).
 */
class ConnectionRepository(
    private val settingsStore: SettingsStore,
    private val holder: ConnectionHolder,
) {

    /** Читает настройки с диска и публикует их в сетевой слой. */
    suspend fun load(): ConnectionSettings = settingsStore.load().also(::publish)

    /**
     * Кладёт адрес и токен только в память — для проверки перед сохранением.
     * Идентификатор устройства уже известен из [load].
     */
    fun stageAttempt(baseUrl: String, token: String) {
        holder.set(baseUrl = baseUrl, token = token, deviceId = holder.deviceId())
    }

    /** Сохраняет подтверждённые сервером адрес и токен. */
    suspend fun persist(baseUrl: String, token: String): ConnectionSettings {
        settingsStore.save(baseUrl, token)
        val settings = settingsStore.load()
        publish(settings)
        return settings
    }

    /** Выход / смена токена: из памяти и с диска убирается только токен. */
    suspend fun forgetToken() {
        holder.clearToken()
        settingsStore.clearToken()
    }

    fun currentDeviceId(): String? = holder.deviceId()

    private fun publish(settings: ConnectionSettings) {
        holder.set(
            baseUrl = settings.baseUrl,
            token = settings.token,
            deviceId = settings.deviceId,
        )
    }
}
