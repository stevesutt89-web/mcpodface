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

    /** Total wall-clock listening time across all sessions, in ms (PLAN.md §13 stats). */
    @Query("SELECT COALESCE(SUM(listenedMs), 0) FROM listening_sessions")
    fun observeTotalListenedMs(): Flow<Long>

    /**
     * Every session's start and listened time. Stats bucket these into local days in Kotlin (a
     * session counts toward the day it started), which also drives the streaks.
     */
    @Query("SELECT startedAt, listenedMs FROM listening_sessions ORDER BY startedAt")
    fun observeSessionTimes(): Flow<List<SessionTimeRow>>

    /** The ten podcasts with the most listening time, with their artwork (PLAN.md §13). */
    @Query(
        """
        SELECT p.id AS podcastId,
               p.title AS podcastTitle,
               p.artworkUrl AS artworkUrl,
               SUM(s.listenedMs) AS listenedMs
        FROM listening_sessions s
        INNER JOIN episodes e ON e.id = s.episodeId
        INNER JOIN podcasts p ON p.id = e.podcastId
        GROUP BY p.id
        ORDER BY listenedMs DESC, p.id
        LIMIT 10
        """,
    )
    fun observeTopPodcasts(): Flow<List<PodcastListeningTotal>>

    /**
     * Time saved by playing faster: listenedMs × (speed − 1), summed over sessions faster than 1x.
     * Fractional ms.
     */
    @Query("SELECT COALESCE(SUM(listenedMs * (speed - 1.0)), 0.0) FROM listening_sessions WHERE speed > 1.0")
    fun observeTimeSavedMs(): Flow<Double>

    /**
     * Episodes finished (PLAYED with a playedAt): all time, and those finished at or after [since]
     * (epoch ms, the start of the 30-day window).
     */
    @Query(
        """
        SELECT COUNT(*) AS total,
               COALESCE(SUM(CASE WHEN playedAt >= :since THEN 1 ELSE 0 END), 0) AS last30Days
        FROM episodes
        WHERE playState = 'PLAYED' AND playedAt IS NOT NULL
        """,
    )
    fun observeFinishedCounts(since: Long): Flow<FinishedCounts>

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
