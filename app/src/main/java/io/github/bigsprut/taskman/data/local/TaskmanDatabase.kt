package io.github.bigsprut.taskman.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.github.bigsprut.taskman.data.local.entity.AttachmentEntity
import io.github.bigsprut.taskman.data.local.entity.CategoryEntity
import io.github.bigsprut.taskman.data.local.entity.EmployeeEntity
import io.github.bigsprut.taskman.data.local.entity.PendingMutationEntity
import io.github.bigsprut.taskman.data.local.entity.ServerSettingsEntity
import io.github.bigsprut.taskman.data.local.entity.TagEntity
import io.github.bigsprut.taskman.data.local.entity.TaskEntity
import io.github.bigsprut.taskman.data.local.entity.TaskListEntity
import io.github.bigsprut.taskman.data.local.entity.TaskStatusHistoryEntity
import io.github.bigsprut.taskman.data.local.entity.TaskTagEntity

/**
 * Локальная база: снапшот состояния сервера + очередь неотправленных мутаций (Ф4).
 *
 * С Ф4 в базе появляются данные, которых нет на сервере (очередь) и оптимистичные правки,
 * поэтому настоящие миграции обязательны: деструктивный пересоздание удалило бы очередь.
 * Схемы экспортируются в `app/schemas`.
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
        PendingMutationEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class TaskmanDatabase : RoomDatabase() {

    abstract fun stateDao(): StateDao

    abstract fun pendingMutationDao(): PendingMutationDao

    companion object {
        private const val DATABASE_NAME = "taskman.db"

        /**
         * v1 → v2: очередь мутаций (Ф4). Данные снапшота не трогаем — только новая таблица.
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `pending_mutations` (
                        `taskId` TEXT NOT NULL,
                        `kind` TEXT NOT NULL,
                        `payloadJson` TEXT,
                        `baseUpdatedAt` REAL,
                        `createdAt` REAL NOT NULL,
                        PRIMARY KEY(`taskId`)
                    )
                    """.trimIndent(),
                )
            }
        }

        fun build(context: Context): TaskmanDatabase =
            Room.databaseBuilder(context, TaskmanDatabase::class.java, DATABASE_NAME)
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
