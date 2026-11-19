package io.github.bigsprut.taskman.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import io.github.bigsprut.taskman.domain.TaskSortOrder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** Параметры подключения, с которыми работает приложение. */
data class ConnectionSettings(
    val baseUrl: String,
    /** `null` — токена нет или его не удалось расшифровать: нужен экран входа. */
    val token: String?,
    /** Постоянный UUID устройства для `X-Device-Id` (TOFU привязывает его навсегда, §4). */
    val deviceId: String,
) {
    val isAuthenticated: Boolean get() = token != null
}

/**
 * Единственный DataStore приложения (файл `taskman_settings`).
 *
 * Повреждённый файл не должен ронять приложение: `corruptionHandler` подменяет его пустыми
 * настройками, после чего пользователь просто входит заново.
 */
val Context.taskmanSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "taskman_settings",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * Хранение параметров подключения: адрес сервера, шифротекст токена, идентификатор устройства.
 *
 * Токен в открытом виде не покидает память процесса: на диск попадает только результат
 * [TokenCodec.encrypt] (§5.4). Устройство генерирует UUID один раз и больше не меняет —
 * иначе сервер будет считать клиент новым, а привязка TOFU необратима (§8, «Грабли»).
 */
class SettingsStore(
    private val dataStore: DataStore<Preferences>,
    private val tokenCodec: TokenCodec,
    private val defaultBaseUrl: String,
) {

    suspend fun load(): ConnectionSettings = withContext(Dispatchers.IO) {
        val preferences = dataStore.data.first()
        val deviceId = preferences[Keys.DEVICE_ID] ?: generateDeviceId()
        val baseUrl = preferences[Keys.BASE_URL]?.takeIf { it.isNotBlank() } ?: defaultBaseUrl

        val storedToken = preferences[Keys.TOKEN_CIPHER]
        val token = storedToken?.let(tokenCodec::decrypt)?.takeIf { it.isNotBlank() }
        if (storedToken != null && token == null) {
            // Ключ Keystore утерян (сброс настроек, восстановление из бэкапа, переустановка) —
            // битый шифротекст бесполезен, чистим и просим токен заново.
            clearToken()
        }

        ConnectionSettings(baseUrl = baseUrl, token = token, deviceId = deviceId)
    }

    /**
     * Сохраняет адрес и токен. Адрес сохраняется и тогда, когда сервер токен не принял:
     * пользователю не придётся вводить его повторно.
     */
    suspend fun save(baseUrl: String, token: String) = withContext(Dispatchers.IO) {
        val cipherText = tokenCodec.encrypt(token)
        dataStore.edit { preferences ->
            preferences[Keys.BASE_URL] = baseUrl
            preferences[Keys.TOKEN_CIPHER] = cipherText
        }
    }

    /** Сохраняет только адрес (смена сервера до ввода токена). */
    suspend fun saveBaseUrl(baseUrl: String) = withContext(Dispatchers.IO) {
        dataStore.edit { preferences -> preferences[Keys.BASE_URL] = baseUrl }
    }

    suspend fun clearToken() = withContext(Dispatchers.IO) {
        dataStore.edit { preferences -> preferences.remove(Keys.TOKEN_CIPHER) }
    }

    /**
     * Порядок сортировки списка (§5.3-Ф5) — локальная настройка устройства; сервер о ней
     * не знает, поэтому живёт рядом с адресом/токеном, а не в снапшоте.
     */
    fun observeSortOrder(): Flow<TaskSortOrder> = dataStore.data.map { preferences ->
        TaskSortOrder.fromKey(preferences[Keys.SORT_ORDER])
    }

    suspend fun saveSortOrder(order: TaskSortOrder) = withContext(Dispatchers.IO) {
        dataStore.edit { preferences -> preferences[Keys.SORT_ORDER] = order.key }
    }

    /**
     * Свёрнут ли блок фильтров (§5.3-Ф1, решение владельца 20.11) — как `taskman_filtershidden`
     * в PWA: состояние живёт на устройстве и сохраняется между запусками.
     */
    fun observeFiltersHidden(): Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[Keys.FILTERS_HIDDEN] ?: false
    }

    suspend fun saveFiltersHidden(hidden: Boolean) = withContext(Dispatchers.IO) {
        dataStore.edit { preferences -> preferences[Keys.FILTERS_HIDDEN] = hidden }
    }

    private suspend fun generateDeviceId(): String {
        val deviceId = UUID.randomUUID().toString()
        dataStore.edit { preferences -> preferences[Keys.DEVICE_ID] = deviceId }
        return deviceId
    }

    private object Keys {
        val BASE_URL = stringPreferencesKey("base_url")
        val TOKEN_CIPHER = stringPreferencesKey("token_cipher")
        val DEVICE_ID = stringPreferencesKey("device_id")
        val SORT_ORDER = stringPreferencesKey("sort_order")
        val FILTERS_HIDDEN = booleanPreferencesKey("filters_hidden")
    }
}
