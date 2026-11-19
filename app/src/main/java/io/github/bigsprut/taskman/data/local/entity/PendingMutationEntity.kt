package io.github.bigsprut.taskman.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Неотправленная мутация задачи (Ф4, §5.3).
 *
 * Одна запись на задачу: повторные правки схлопываются — хранится последняя полная версия
 * payload. `baseUpdatedAt` фиксируется на моменте **первой** локальной правки и служит
 * версией для CAS: после отправки сервер поднимет `updated_at`, и если бы мы брали версию
 * из каждой новой правки, следующая отправка получила бы ложный 409.
 *
 * @property kind `upsert` (создание/правка/переименование) или `delete`.
 * @property payloadJson сериализованный `TaskInDto` (для upsert; для delete `null`).
 */
@Entity(tableName = "pending_mutations")
data class PendingMutationEntity(
    @PrimaryKey val taskId: String,
    val kind: String,
    val payloadJson: String?,
    val baseUpdatedAt: Double?,
    val createdAt: Double,
) {
    companion object {
        const val KIND_UPSERT = "upsert"
        const val KIND_DELETE = "delete"
    }
}
