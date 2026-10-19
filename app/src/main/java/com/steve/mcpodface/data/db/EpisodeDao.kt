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

    /** Latest tab (PLAN.md §8.2): subscribed podcasts only, newest first. */
    @Query(
        """
        SELECT e.*,
               p.title AS podcastTitle,
               p.artworkUrl AS podcastArtworkUrl,
               d.state AS downloadState,
               COALESCE(d.totalBytes, d.bytesDownloaded) AS downloadBytes,
               EXISTS (SELECT 1 FROM queue_items q WHERE q.episodeId = e.id) AS inQueue
        FROM episodes e
        INNER JOIN podcasts p ON p.id = e.podcastId
        LEFT JOIN downloads d ON d.episodeId = e.id
        WHERE p.isSubscribed = 1
          AND e.pubDate >= :since
          AND (:unplayedOnly = 0 OR e.playState != 'PLAYED')
          AND (:downloadedOnly = 0 OR d.state = 'COMPLETED')
        ORDER BY e.pubDate DESC, e.id DESC
        LIMIT :limit
        """,
    )
    fun observeLatest(
        since: Long,
        limit: Int,
        unplayedOnly: Boolean,
        downloadedOnly: Boolean,
    ): Flow<List<EpisodeListItem>>

    /**
     * Podcast detail (PLAN.md §8.4): every episode including archived ones. Direction is switched
     * by negating the sort key for ascending order, so one query serves both sorts.
     */
    @Query(
        """
        SELECT e.*,
               p.title AS podcastTitle,
               p.artworkUrl AS podcastArtworkUrl,
               d.state AS downloadState,
               COALESCE(d.totalBytes, d.bytesDownloaded) AS downloadBytes,
               EXISTS (SELECT 1 FROM queue_items q WHERE q.episodeId = e.id) AS inQueue
        FROM episodes e
        INNER JOIN podcasts p ON p.id = e.podcastId
        LEFT JOIN downloads d ON d.episodeId = e.id
        WHERE e.podcastId = :podcastId
          AND (:hidePlayed = 0 OR e.playState != 'PLAYED')
        ORDER BY
          CASE WHEN :newestFirst = 1 THEN e.pubDate ELSE -e.pubDate END DESC,
          CASE WHEN :newestFirst = 1 THEN e.id ELSE -e.id END DESC
        """,
    )
    fun observeForPodcast(
        podcastId: Long,
        newestFirst: Boolean,
        hidePlayed: Boolean,
    ): Flow<List<EpisodeListItem>>

    /** "Mark all played" on podcast detail. Already-played rows keep their original playedAt. */
    @Query("UPDATE episodes SET playState = 'PLAYED', playedAt = :at WHERE podcastId = :podcastId AND playState != 'PLAYED'")
    suspend fun markAllPlayed(podcastId: Long, at: Long)
}
