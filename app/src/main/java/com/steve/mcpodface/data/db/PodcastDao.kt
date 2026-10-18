package com.steve.mcpodface.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface PodcastDao {
    /** Returns the new row id, or -1 if a podcast with this feedUrl already exists. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(podcast: PodcastEntity): Long

    @Update
    suspend fun update(podcast: PodcastEntity)

    @Query("SELECT * FROM podcasts WHERE id = :id")
    suspend fun getById(id: Long): PodcastEntity?

    @Query("SELECT * FROM podcasts WHERE id = :id")
    fun observeById(id: Long): Flow<PodcastEntity?>

    @Query("SELECT * FROM podcasts WHERE feedUrl = :feedUrl")
    suspend fun getByFeedUrl(feedUrl: String): PodcastEntity?

    @Query("SELECT * FROM podcasts WHERE isSubscribed = 1")
    suspend fun getSubscribed(): List<PodcastEntity>

    @Query("SELECT * FROM podcasts WHERE isSubscribed = 1 ORDER BY title COLLATE NOCASE")
    fun observeSubscribed(): Flow<List<PodcastEntity>>
}
