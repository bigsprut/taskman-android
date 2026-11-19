package io.github.bigsprut.taskman.data.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.bigsprut.taskman.TaskmanApp
import io.github.bigsprut.taskman.data.repo.MutationOutcome

/**
 * Отправляет офлайн-очередь (Ф4). Запускается после правки и по возвращению сети.
 *
 * `Result.retry()` — только когда сеть пропала (`MutationOutcome.Queued`); серверные отказы
 * и конфликты обрабатываются в репозитории (запись снимается), повторять их бессмысленно.
 */
class MutationSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as TaskmanApp).container
        return when (container.taskMutationRepository.flush()) {
            is MutationOutcome.Queued -> Result.retry()
            else -> Result.success()
        }
    }
}

/**
 * Периодическое обновление состояния раз в 15 минут (§5.4).
 *
 * Полезная работа возможна только при настроенном подключении; иначе просто выходим — будить
 * сеть без токена незачем.
 */
class RefreshWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as TaskmanApp).container
        if (container.connectionHolder.token().isNullOrBlank()) return Result.success()

        // Если есть неотправленные мутации — сначала отправляем их, затем читаем состояние.
        if (container.taskMutationRepository.flush() is MutationOutcome.Queued) {
            return Result.retry()
        }
        container.taskRepository.refresh()
        return Result.success()
    }
}
