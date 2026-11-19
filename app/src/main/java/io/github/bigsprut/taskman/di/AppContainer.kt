package io.github.bigsprut.taskman.di

import android.content.Context
import io.github.bigsprut.taskman.BuildConfig
import io.github.bigsprut.taskman.data.local.TaskmanDatabase
import io.github.bigsprut.taskman.data.remote.ConnectionHolder
import io.github.bigsprut.taskman.data.remote.NetworkFactory
import io.github.bigsprut.taskman.data.repo.DatabaseStateStore
import io.github.bigsprut.taskman.data.repo.StateStore
import io.github.bigsprut.taskman.data.repo.TaskRepository
import io.github.bigsprut.taskman.data.settings.AndroidKeystoreTokenCodec
import io.github.bigsprut.taskman.data.settings.ConnectionRepository
import io.github.bigsprut.taskman.data.settings.SettingsStore
import io.github.bigsprut.taskman.data.settings.taskmanSettingsDataStore

/**
 * Ручной DI-контейнер приложения.
 *
 * Почему не Hilt: один модуль и три-четыре зависимости — аннотационный процессор даёт больше
 * сборочной магии, чем пользы; решение и аргументы зафиксированы в `PHASE-REPORT.md` (ТЗ §5.1
 * требует обосновать выбор). Если к Ф4 граф вырастет, переход на Hilt — отдельный PR.
 */
class AppContainer(context: Context) {

    private val applicationContext: Context = context.applicationContext

    /** Текущие параметры подключения для сетевого слоя (обновляются при входе/выходе). */
    val connectionHolder = ConnectionHolder()

    private val tokenCodec = AndroidKeystoreTokenCodec()

    private val settingsStore = SettingsStore(
        dataStore = applicationContext.taskmanSettingsDataStore,
        tokenCodec = tokenCodec,
        // Адрес инстанса приходит из local.properties через BuildConfig (ТЗ §5.4);
        // в чистом клоне это placeholder, который пользователь меняет на экране входа.
        defaultBaseUrl = BuildConfig.TM_BASE_URL,
    )

    val connectionRepository = ConnectionRepository(
        settingsStore = settingsStore,
        holder = connectionHolder,
    )

    private val network = NetworkFactory(
        cacheDirectory = applicationContext.cacheDir,
        connection = connectionHolder,
    )

    private val database: TaskmanDatabase by lazy { TaskmanDatabase.build(applicationContext) }

    private val stateStore: StateStore by lazy { DatabaseStateStore(database) }

    val taskRepository: TaskRepository by lazy { TaskRepository(network.api, stateStore) }
}
