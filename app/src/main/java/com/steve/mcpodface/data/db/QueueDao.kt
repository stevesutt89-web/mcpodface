package com.steve.mcpodface.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface QueueDao {
    @Query("SELECT * FROM queue_items ORDER BY position")
    fun observe(): Flow<List<QueueItemEntity>>

    @Query("SELECT * FROM queue_items ORDER BY position")
    suspend fun getAll(): List<QueueItemEntity>

    @Upsert
    suspend fun upsertAll(items: List<QueueItemEntity>)

    @Query("DELETE FROM queue_items WHERE episodeId = :episodeId")
    suspend fun remove(episodeId: Long)

    @Query("DELETE FROM queue_items")
    suspend fun clear()

    /** Replaces the whole queue with episodeIds, in order. */
    @Transaction
    suspend fun replaceAll(episodeIds: List<Long>) {
        clear()
        upsertAll(episodeIds.mapIndexed { i, id -> QueueItemEntity(id, i) })
    }
}
