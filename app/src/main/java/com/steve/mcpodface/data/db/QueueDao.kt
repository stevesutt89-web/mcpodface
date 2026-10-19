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

    /** Up Next tab (PLAN.md §8.8): queued episodes in queue order, with the list-row columns. */
    @Query(
        """
        SELECT e.*,
               p.title AS podcastTitle,
               p.artworkUrl AS podcastArtworkUrl,
               d.state AS downloadState,
               COALESCE(d.totalBytes, d.bytesDownloaded) AS downloadBytes,
               1 AS inQueue
        FROM queue_items q
        INNER JOIN episodes e ON e.id = q.episodeId
        INNER JOIN podcasts p ON p.id = e.podcastId
        LEFT JOIN downloads d ON d.episodeId = e.id
        ORDER BY q.position
        """,
    )
    fun observeQueueItems(): Flow<List<EpisodeListItem>>

    @Upsert
    suspend fun upsertAll(items: List<QueueItemEntity>)

    @Query("DELETE FROM queue_items WHERE episodeId = :episodeId")
    suspend fun remove(episodeId: Long)

    @Query("DELETE FROM queue_items")
    suspend fun clear()

    /** Queue in order, keyed by feed URL + guid for the backup (PLAN.md §9). */
    @Query(
        """
        SELECT p.feedUrl AS feedUrl, e.guid AS guid
        FROM queue_items q
        INNER JOIN episodes e ON e.id = q.episodeId
        INNER JOIN podcasts p ON p.id = e.podcastId
        ORDER BY q.position
        """,
    )
    suspend fun backupRows(): List<QueueBackupRow>

    /** Replaces the whole queue with episodeIds, in order. */
    @Transaction
    suspend fun replaceAll(episodeIds: List<Long>) {
        clear()
        upsertAll(episodeIds.mapIndexed { i, id -> QueueItemEntity(id, i) })
    }
}

/** One queued episode, keyed by feed URL + guid. Its position is its index in the list. */
data class QueueBackupRow(val feedUrl: String, val guid: String)
