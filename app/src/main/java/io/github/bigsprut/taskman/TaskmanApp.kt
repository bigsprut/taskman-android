package io.github.bigsprut.taskman

import android.app.Application
import io.github.bigsprut.taskman.di.AppContainer

/**
 * Точка входа приложения: создаёт DI-контейнер.
 *
 * Контейнер ленивый — при холодном старте не открывается база и не читается DataStore,
 * пока их не запросит первый экран (ТЗ §5.4: холодный старт < 1.5 с).
 */
class TaskmanApp : Application() {

    val container: AppContainer by lazy { AppContainer(this) }
}
