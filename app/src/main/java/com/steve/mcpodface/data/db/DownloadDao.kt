package com.steve.mcpodface.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {
    @Upsert
    suspend fun upsert(download: DownloadEntity)

    @Query("SELECT * FROM downloads WHERE episodeId = :episodeId")
    suspend fun get(episodeId: Long): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE episodeId = :episodeId")
    fun observe(episodeId: Long): Flow<DownloadEntity?>

    @Query("SELECT * FROM downloads")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("DELETE FROM downloads WHERE episodeId = :episodeId")
    suspend fun delete(episodeId: Long)

    /** Progress while downloading; also marks the row DOWNLOADING. */
    @Query("UPDATE downloads SET bytesDownloaded = :bytes, totalBytes = COALESCE(:total, totalBytes), state = 'DOWNLOADING' WHERE episodeId = :episodeId")
    suspend fun updateProgress(episodeId: Long, bytes: Long, total: Long?)

    @Query("UPDATE downloads SET state = :state, error = :error, attempts = :attempts WHERE episodeId = :episodeId")
    suspend fun updateState(episodeId: Long, state: DownloadState, error: String?, attempts: Int)

    @Query("SELECT * FROM downloads WHERE state = 'COMPLETED'")
    suspend fun getCompleted(): List<DownloadEntity>

    @Query("SELECT d.* FROM downloads d INNER JOIN episodes e ON e.id = d.episodeId WHERE e.podcastId = :podcastId")
    suspend fun getForPodcast(podcastId: Long): List<DownloadEntity>

    @Query("SELECT DISTINCT e.podcastId FROM downloads d INNER JOIN episodes e ON e.id = d.episodeId")
    suspend fun podcastIdsWithDownloads(): List<Long>

    @Query("SELECT * FROM downloads")
    suspend fun getAll(): List<DownloadEntity>

    /** Downloads storage header, per-podcast sizes (PLAN.md §8.7), largest first. */
    @Query(
        """
        SELECT p.id AS podcastId,
               p.title AS podcastTitle,
               COUNT(*) AS episodeCount,
               SUM(COALESCE(d.totalBytes, d.bytesDownloaded)) AS totalBytes
        FROM downloads d
        INNER JOIN episodes e ON e.id = d.episodeId
        INNER JOIN podcasts p ON p.id = e.podcastId
        WHERE d.state = 'COMPLETED'
        GROUP BY p.id
        ORDER BY totalBytes DESC, p.title COLLATE NOCASE
        """,
    )
    fun observeSizesByPodcast(): Flow<List<PodcastDownloadSize>>

    /** Completed downloads totals. Always one row; totals are 0 when there are none. */
    @Query(
        """
        SELECT COUNT(*) AS episodeCount,
               COALESCE(SUM(COALESCE(totalBytes, bytesDownloaded)), 0) AS totalBytes
        FROM downloads
        WHERE state = 'COMPLETED'
        """,
    )
    fun observeTotals(): Flow<DownloadTotals>

    @Query("SELECT * FROM downloads WHERE state = :state")
    suspend fun getByState(state: DownloadState): List<DownloadEntity>

    /** Every downloads row with its episode and podcast, for the Downloads tab (PLAN.md §8.7). */
    @Query(
        """
        SELECT d.episodeId AS episodeId,
               d.state AS state,
               d.source AS source,
               d.filePath AS filePath,
               d.bytesDownloaded AS bytesDownloaded,
               d.totalBytes AS totalBytes,
               d.downloadedAt AS downloadedAt,
               d.workId AS workId,
               d.error AS error,
               d.attempts AS attempts,
               e.title AS episodeTitle,
               p.id AS podcastId,
               p.title AS podcastTitle,
               p.feedType AS feedType,
               COALESCE(e.artworkUrl, p.artworkUrl) AS artworkUrl,
               e.pubDate AS pubDate,
               e.durationSec AS durationSec,
               e.playState AS playState,
               e.playedAt AS playedAt,
               e.isStarred AS isStarred
        FROM downloads d
        INNER JOIN episodes e ON e.id = d.episodeId
        INNER JOIN podcasts p ON p.id = e.podcastId
        """,
    )
    fun observeDownloadItems(): Flow<List<DownloadListItem>>

    /** Per-podcast overrides, observed so cleanup labels update when a podcast's settings change. */
    @Query("SELECT * FROM podcast_settings")
    fun observePodcastSettings(): Flow<List<PodcastSettingsEntity>>
}
