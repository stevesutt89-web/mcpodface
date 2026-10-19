package com.steve.mcpodface.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ListeningSessionDao {
    @Insert
    suspend fun insert(session: ListeningSessionEntity): Long

    @Update
    suspend fun update(session: ListeningSessionEntity)

    @Query("SELECT * FROM listening_sessions ORDER BY startedAt DESC")
    suspend fun getAll(): List<ListeningSessionEntity>

    /**
     * History screen (PLAN.md §8.3): one row per episode with a session, most recently listened
     * first, capped at 500. [HistoryItem.lastListenedAt] is the latest session end
     * (startedAt + listenedMs); [HistoryItem.totalListenedMs] sums all its sessions.
     */
    @Query(
        """
        SELECT e.*,
               p.title AS podcastTitle,
               p.artworkUrl AS podcastArtworkUrl,
               d.state AS downloadState,
               COALESCE(d.totalBytes, d.bytesDownloaded) AS downloadBytes,
               EXISTS(SELECT 1 FROM queue_items q WHERE q.episodeId = e.id) AS inQueue,
               s.lastListenedAt AS lastListenedAt,
               s.totalListenedMs AS totalListenedMs
        FROM (
            SELECT episodeId,
                   MAX(startedAt + listenedMs) AS lastListenedAt,
                   SUM(listenedMs) AS totalListenedMs
            FROM listening_sessions
            GROUP BY episodeId
            ORDER BY lastListenedAt DESC
            LIMIT 500
        ) s
        INNER JOIN episodes e ON e.id = s.episodeId
        INNER JOIN podcasts p ON p.id = e.podcastId
        LEFT JOIN downloads d ON d.episodeId = e.id
        ORDER BY s.lastListenedAt DESC
        """,
    )
    fun observeRecentlyPlayed(): Flow<List<HistoryItem>>

    @Query("DELETE FROM listening_sessions")
    suspend fun clearHistory()

    /** Every listening session, oldest first, keyed by feed URL + guid for the backup (PLAN.md §9). */
    @Query(
        """
        SELECT p.feedUrl AS feedUrl, e.guid AS guid, s.startedAt AS startedAt,
               s.listenedMs AS listenedMs, s.speed AS speed
        FROM listening_sessions s
        INNER JOIN episodes e ON e.id = s.episodeId
        INNER JOIN podcasts p ON p.id = e.podcastId
        ORDER BY s.startedAt, s.id
        """,
    )
    suspend fun backupRows(): List<SessionBackupRow>
}

/** One listening session for the backup. */
data class SessionBackupRow(
    val feedUrl: String,
    val guid: String,
    val startedAt: Long,
    val listenedMs: Long,
    val speed: Float,
)
