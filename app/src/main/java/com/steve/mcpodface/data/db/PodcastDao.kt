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

    /** Removes a preview-only row (cascades to its episodes). Subscribed podcasts are never deleted. */
    @Query("DELETE FROM podcasts WHERE id = :id AND isSubscribed = 0")
    suspend fun deleteIfUnsubscribed(id: Long)

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

    /** Library grid (PLAN.md §8.3). Sorting by other modes happens in Kotlin. */
    @Query(
        """
        SELECT p.*,
               (SELECT COUNT(*) FROM episodes e
                 WHERE e.podcastId = p.id AND e.playState = 'UNPLAYED') AS unplayedCount
        FROM podcasts p
        WHERE p.isSubscribed = 1
        ORDER BY p.title COLLATE NOCASE
        """,
    )
    fun observeLibrary(): Flow<List<LibraryPodcast>>
}
