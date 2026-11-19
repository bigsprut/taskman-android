package io.github.bigsprut.taskman.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Вложение задачи. Файл лежит на сервере; локально храним только метаданные,
 * чтобы показывать счётчик/имя без запроса (превью и загрузка — Ф3).
 */
@Entity(tableName = "attachments", indices = [Index("taskId")])
data class AttachmentEntity(
    @PrimaryKey val id: String,
    val taskId: String,
    val origName: String,
    val mime: String?,
    val size: Long,
    val createdAt: Double,
    val deleted: Boolean,
)

/** Связь «задача ↔ тег» (`task.tags` в контракте — список id). */
@Entity(
    tableName = "task_tags",
    primaryKeys = ["taskId", "tagId"],
    indices = [Index("tagId")],
)
data class TaskTagEntity(
    val taskId: String,
    val tagId: String,
)

/**
 * История статусов задачи: источник `statusDateTs` и, в Ф2, экрана «История».
 * Суррогатный ключ вместо составного: `ts` — вещественное число, сравнивать его в PK
 * ненадёжно.
 */
@Entity(tableName = "task_history", indices = [Index("taskId")])
data class TaskStatusHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: String,
    val status: String,
    val ts: Double,
)
