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

    /** Starred episodes of any podcast, newest first (Library → Starred). */
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
        WHERE e.isStarred = 1
        ORDER BY e.pubDate DESC, e.id DESC
        """,
    )
    fun observeStarred(): Flow<List<EpisodeListItem>>

    /** Most recently played episode, for playback resumption (Bluetooth play, notification resume). */
    @Query("SELECT * FROM episodes WHERE lastPlayedAt IS NOT NULL ORDER BY lastPlayedAt DESC LIMIT 1")
    suspend fun getLastPlayed(): EpisodeEntity?

    /**
     * The next unplayed episode after (pubDate, id) in the podcast's sort order: older episodes for
     * newest-first, newer ones for oldest-first. Used by "autoplay next from same podcast".
     */
    @Query(
        """
        SELECT * FROM episodes
        WHERE podcastId = :podcastId AND playState != 'PLAYED' AND id != :afterId
          AND ((:newestFirst = 1 AND (pubDate < :afterPubDate OR (pubDate = :afterPubDate AND id < :afterId)))
            OR (:newestFirst = 0 AND (pubDate > :afterPubDate OR (pubDate = :afterPubDate AND id > :afterId))))
        ORDER BY
          CASE WHEN :newestFirst = 1 THEN pubDate ELSE -pubDate END DESC,
          CASE WHEN :newestFirst = 1 THEN id ELSE -id END DESC
        LIMIT 1
        """,
    )
    suspend fun getNextUnplayedInPodcast(podcastId: Long, afterPubDate: Long, afterId: Long, newestFirst: Boolean): EpisodeEntity?

    /** positionMs = null keeps the current position. */
    @Query("UPDATE episodes SET playState = :playState, playedAt = :playedAt, positionMs = COALESCE(:positionMs, positionMs) WHERE id = :id")
    suspend fun setPlayState(id: Long, playState: PlayState, playedAt: Long?, positionMs: Long?)

    /** Tombstone (PLAN.md §6.4): set when a download is deleted, cleared by a manual download. */
    @Query("UPDATE episodes SET autoDownloadBlocked = :blocked WHERE id = :id")
    suspend fun setAutoDownloadBlocked(id: Long, blocked: Boolean)

    @Query("UPDATE episodes SET isStarred = :starred WHERE id = :id")
    suspend fun setStarred(id: Long, starred: Boolean)

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
