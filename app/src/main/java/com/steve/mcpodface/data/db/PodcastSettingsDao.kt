package com.steve.mcpodface.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface PodcastSettingsDao {
    @Upsert
    suspend fun upsert(settings: PodcastSettingsEntity)

    @Query("SELECT * FROM podcast_settings WHERE podcastId = :podcastId")
    suspend fun get(podcastId: Long): PodcastSettingsEntity?

    @Query("SELECT * FROM podcast_settings WHERE podcastId = :podcastId")
    fun observe(podcastId: Long): Flow<PodcastSettingsEntity?>

    @Query("SELECT * FROM podcast_settings")
    suspend fun getAll(): List<PodcastSettingsEntity>
}
