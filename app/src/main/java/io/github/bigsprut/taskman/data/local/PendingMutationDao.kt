package io.github.bigsprut.taskman.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.github.bigsprut.taskman.data.local.entity.PendingMutationEntity
import kotlinx.coroutines.flow.Flow

/**
 * Очередь неотправленных мутаций (Ф4). Ключ — `taskId`: запись на задачу, при повторной правке
 * заменяется (схлопывание).
 */
@Dao
interface PendingMutationDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(record: PendingMutationEntity)

    @Query("SELECT * FROM pending_mutations ORDER BY createdAt")
    suspend fun pending(): List<PendingMutationEntity>

    @Query("SELECT * FROM pending_mutations WHERE taskId = :taskId")
    suspend fun find(taskId: String): PendingMutationEntity?

    @Query("SELECT COUNT(*) FROM pending_mutations")
    fun observeCount(): Flow<Int>

    @Query("DELETE FROM pending_mutations WHERE taskId = :taskId")
    suspend fun remove(taskId: String)

    @Query("DELETE FROM pending_mutations")
    suspend fun clear()
}
