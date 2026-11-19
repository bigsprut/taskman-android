package io.github.bigsprut.taskman

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import io.github.bigsprut.taskman.di.AppContainer

/**
 * Точка входа приложения: создаёт DI-контейнер и настраивает загрузчик картинок.
 *
 * Контейнер ленивый — при холодном старте не открывается база и не читается DataStore,
 * пока их не запросит первый экран (ТЗ §5.4: холодный старт < 1.5 с).
 *
 * Coil работает через клиент **без** интерсептора заголовков: превью вложения грузится по
 * абсолютной ссылке `?token=…` (ТЗ §4), и лишние заголовки/подмена хоста там не нужны.
 */
class TaskmanApp : Application(), SingletonImageLoader.Factory {

    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        // Ф4: периодическое обновление раз в 15 минут и отправка очереди по возвращению сети.
        container.syncScheduler.schedulePeriodicSync()
        container.syncScheduler.observeNetworkAndFlush()
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { container.fileHttpClient }))
            }
            .build()
}
