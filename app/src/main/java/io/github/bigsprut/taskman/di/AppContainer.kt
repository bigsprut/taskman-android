package io.github.bigsprut.taskman.di

import android.content.Context
import io.github.bigsprut.taskman.BuildConfig
import io.github.bigsprut.taskman.data.attachments.AttachmentDownloader
import io.github.bigsprut.taskman.data.attachments.AttachmentRepository
import io.github.bigsprut.taskman.data.attachments.ContentResolverFileSource
import io.github.bigsprut.taskman.data.local.TaskmanDatabase
import io.github.bigsprut.taskman.data.remote.ConnectionHolder
import io.github.bigsprut.taskman.data.remote.NetworkFactory
import io.github.bigsprut.taskman.data.repo.DatabaseLocalTaskWriter
import io.github.bigsprut.taskman.data.repo.DatabaseStateStore
import io.github.bigsprut.taskman.data.repo.LocalTaskWriter
import io.github.bigsprut.taskman.data.repo.OfflineMutationQueue
import io.github.bigsprut.taskman.data.repo.EmployeeRepository
import io.github.bigsprut.taskman.data.repo.StateStore
import io.github.bigsprut.taskman.data.repo.TaskMutationRepository
import io.github.bigsprut.taskman.data.repo.TaskRepository
import io.github.bigsprut.taskman.data.sync.SyncScheduler
import io.github.bigsprut.taskman.data.settings.AndroidKeystoreTokenCodec
import io.github.bigsprut.taskman.data.settings.ConnectionRepository
import io.github.bigsprut.taskman.data.settings.SettingsStore
import io.github.bigsprut.taskman.data.settings.taskmanSettingsDataStore

/**
 * Ручной DI-контейнер приложения.
 *
 * Почему не Hilt: один модуль и несколько зависимостей — аннотационный процессор даёт больше
 * сборочной магии, чем пользы; решение и аргументы зафиксированы в `PHASE-REPORT.md` (ТЗ §5.1
 * требует обосновать выбор). Если граф вырастет, переход на Hilt — отдельный PR.
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

    /** Планировщик фоновой синхронизации: очередь мутаций и периодический refresh (Ф4). */
    val syncScheduler by lazy { SyncScheduler(applicationContext) }

    /** Очередь неотправленных мутаций (Ф4). */
    val offlineQueue: OfflineMutationQueue by lazy {
        OfflineMutationQueue(database.pendingMutationDao())
    }

    private val localTaskWriter: LocalTaskWriter by lazy { DatabaseLocalTaskWriter(database) }

    /** Room-хранилище снапшота; оно же читает полную сущность задачи для мутаций (CAS-payload). */
    private val databaseStateStore: DatabaseStateStore by lazy { DatabaseStateStore(database) }

    /** Каталоги (теги/категории) для чипов фильтра. */
    val catalogStore: StateStore by lazy { databaseStateStore }

    val taskRepository: TaskRepository by lazy {
        TaskRepository(
            api = network.api,
            stateStore = databaseStateStore,
            offlineQueue = offlineQueue,
        )
    }

    val taskMutationRepository: TaskMutationRepository by lazy {
        TaskMutationRepository(
            api = network.api,
            reader = databaseStateStore,
            tasks = taskRepository,
            queue = offlineQueue,
            writer = localTaskWriter,
            requestBackgroundSync = syncScheduler::requestFlush,
        )
    }

    val employeeRepository: EmployeeRepository by lazy {
        EmployeeRepository(api = network.api, tasks = taskRepository)
    }

    /** Вложения: аплоад/удаление/скачивание (Ф3). */
    val attachmentRepository: AttachmentRepository by lazy {
        AttachmentRepository(
            api = network.api,
            store = databaseStateStore,
            files = ContentResolverFileSource(applicationContext),
            tasks = taskRepository,
            connection = connectionHolder,
            downloader = AttachmentDownloader(
                client = network.fileClient(),
                cacheDirectory = applicationContext.cacheDir,
            ),
        )
    }

    /** Очередь файлов из «Поделиться в Задачи» (Ф3). */
    val shareInbox = io.github.bigsprut.taskman.data.attachments.ShareInbox()

    /** Клиент без интерсептора — для Coil-превью по ссылке с токеном в query. */
    val fileHttpClient get() = network.fileClient()
}
