package io.github.bigsprut.taskman.testutil

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import io.github.bigsprut.taskman.data.settings.SettingsStore
import io.github.bigsprut.taskman.data.settings.TokenCodec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Подставной DataStore: настройки живут в памяти, без файловой системы и Android-контекста.
 *
 * `SettingsStore` — конкретный класс, но его зависимость `DataStore` — интерфейс, поэтому
 * весь путь «ViewModel → SettingsStore → DataStore» проверяется на JVM. Два экземпляра
 * `SettingsStore` над одним объектом ведут себя как два запуска приложения над одним файлом
 * настроек — на этом строится проверка персистентности.
 */
class InMemoryPreferencesDataStore(
    initial: Preferences = emptyPreferences(),
) : DataStore<Preferences> {

    private val state = MutableStateFlow(initial)

    override val data: Flow<Preferences> = state

    override suspend fun updateData(
        transform: suspend (t: Preferences) -> Preferences,
    ): Preferences {
        val updated = transform(state.value)
        state.value = updated
        return updated
    }
}

/** Тестам, которые проверяют не токен, а остальные настройки, шифрование не нужно. */
object PlainTextTokenCodec : TokenCodec {

    override fun encrypt(plainText: String): String = "plain:$plainText"

    override fun decrypt(cipherText: String): String? = cipherText.removePrefix("plain:")
}

/**
 * `SettingsStore` поверх подставного DataStore.
 *
 * [dataStore] передаётся снаружи, когда тесту важно пережить «перезапуск»: новый экземпляр
 * `SettingsStore` над тем же хранилищем читает уже сохранённые значения.
 */
fun settingsStore(
    dataStore: DataStore<Preferences> = InMemoryPreferencesDataStore(),
    defaultBaseUrl: String = DEFAULT_TEST_BASE_URL,
): SettingsStore = SettingsStore(
    dataStore = dataStore,
    tokenCodec = PlainTextTokenCodec,
    defaultBaseUrl = defaultBaseUrl,
)

/** Адрес по умолчанию для тестов: реальная сеть в них не используется. */
const val DEFAULT_TEST_BASE_URL: String = "http://localhost:8443"
