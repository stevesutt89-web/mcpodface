package com.steve.mcpodface.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface EpisodeDao {
    /** Returns row ids; -1 for rows that already existed (same podcastId + guid). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(episodes: List<EpisodeEntity>): List<Long>

    @Update
    suspend fun updateAll(episodes: List<EpisodeEntity>)

    @Query("SELECT * FROM episodes WHERE id = :id")
    suspend fun getById(id: Long): EpisodeEntity?

    @Query("SELECT * FROM episodes WHERE id = :id")
    fun observeById(id: Long): Flow<EpisodeEntity?>

    @Query("SELECT * FROM episodes WHERE podcastId = :podcastId")
    suspend fun getForPodcast(podcastId: Long): List<EpisodeEntity>

    @Query("UPDATE episodes SET positionMs = :positionMs, playState = :playState, lastPlayedAt = :at WHERE id = :id")
    suspend fun updatePosition(id: Long, positionMs: Long, playState: PlayState, at: Long)
}
