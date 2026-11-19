package io.github.bigsprut.taskman.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import io.github.bigsprut.taskman.data.local.entity.AttachmentEntity
import io.github.bigsprut.taskman.data.local.entity.CategoryEntity
import io.github.bigsprut.taskman.data.local.entity.EmployeeEntity
import io.github.bigsprut.taskman.data.local.entity.ServerSettingsEntity
import io.github.bigsprut.taskman.data.local.entity.TagEntity
import io.github.bigsprut.taskman.data.local.entity.TaskEntity
import io.github.bigsprut.taskman.data.local.entity.TaskListEntity
import io.github.bigsprut.taskman.data.local.entity.TaskStatusHistoryEntity
import io.github.bigsprut.taskman.data.local.entity.TaskTagEntity

/**
 * Локальная база: снапшот состояния сервера.
 *
 * В Ф0 база — кэш поверх источника правды (сервера). Схема экспортируется в `app/schemas`,
 * чтобы изменения БД были видны в диффе PR. Деструктивная миграция допустима только на этом
 * этапе: снапшот всегда восстанавливается из `/api/state`; начиная с Ф4, когда в базе
 * появятся локальные мутации, упадёт требование к настоящим миграциям.
 */
@Database(
    entities = [
        TaskEntity::class,
        TaskListEntity::class,
        CategoryEntity::class,
        TagEntity::class,
        EmployeeEntity::class,
        ServerSettingsEntity::class,
        AttachmentEntity::class,
        TaskTagEntity::class,
        TaskStatusHistoryEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class TaskmanDatabase : RoomDatabase() {

    abstract fun stateDao(): StateDao

    companion object {
        private const val DATABASE_NAME = "taskman.db"

        fun build(context: Context): TaskmanDatabase =
            Room.databaseBuilder(context, TaskmanDatabase::class.java, DATABASE_NAME)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
