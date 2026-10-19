package com.steve.mcpodface.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface BackfillStateDao {
    @Upsert
    suspend fun upsert(state: BackfillStateEntity)

    @Query("SELECT * FROM backfill_state WHERE podcastId = :podcastId")
    suspend fun get(podcastId: Long): BackfillStateEntity?

    @Query("SELECT * FROM backfill_state WHERE podcastId = :podcastId")
    fun observe(podcastId: Long): Flow<BackfillStateEntity?>

    @Query("SELECT * FROM backfill_state")
    suspend fun getAll(): List<BackfillStateEntity>

    /** Every archive row, for the overall progress in Settings → Search & archive. */
    @Query("SELECT * FROM backfill_state")
    fun observeAll(): Flow<List<BackfillStateEntity>>
}
