package io.github.bigsprut.taskman.data.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Планировщик фоновой синхронизации (Ф4, §5.3).
 *
 * Две работы:
 * - **разовая отправка очереди** (`MutationSyncWorker`) с сетевым ограничением и
 *   экспоненциальным backoff; политика `KEEP` — параллельные копии не нужны, очередь и так
 *   сериализована внутри репозитория;
 * - **периодическое обновление состояния** раз в 15 минут (`RefreshWorker`) — реже нельзя
 *   (§5.4: фоновая синхронизация не чаще раза в 15 минут). Обновление дешёвое: сервер отдаёт
 *   `ETag`/304, поэтому «каждый раз» обычно не тратит трафик.
 *
 * Плюс слушатель сети: как только связь вернулась, ставим разовую отправку — так очередь уходит
 * сразу, а не через backoff. `ACCESS_NETWORK_STATE` возвращён в манифест (ответ на Q4 в Ф0).
 */
class SyncScheduler(private val context: Context) {

    /** Страховка: дошлёт очередь, если приложение закроют сразу после правки. */
    fun requestFlush() {
        WorkManager.getInstance(context).enqueueUniqueWork(
            FLUSH_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<MutationSyncWorker>()
                .setConstraints(networkConstraints())
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    BACKOFF_SECONDS,
                    TimeUnit.SECONDS,
                )
                .build(),
        )
    }

    /** Регистрируется один раз при старте приложения. */
    fun schedulePeriodicSync() {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<RefreshWorker>(PERIODIC_MINUTES, TimeUnit.MINUTES)
                .setConstraints(networkConstraints())
                .build(),
        )
    }

    /** Отправка сразу после возвращения сети. */
    fun observeNetworkAndFlush() {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return
        runCatching {
            connectivity.registerDefaultNetworkCallback(
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        requestFlush()
                    }
                },
            )
        }
    }

    private fun networkConstraints(): Constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    private companion object {
        const val FLUSH_WORK_NAME = "taskman-mutation-flush"
        const val PERIODIC_WORK_NAME = "taskman-periodic-refresh"
        const val BACKOFF_SECONDS = 30L
        const val PERIODIC_MINUTES = 15L
    }
}
