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

    /** How many episodes we hold for a podcast, for the archive status line (PLAN.md §7.4). */
    @Query("SELECT COUNT(*) FROM episodes WHERE podcastId = :podcastId")
    fun observeCount(podcastId: Long): Flow<Int>

    @Query("SELECT * FROM episodes WHERE podcastId = :podcastId")
    suspend fun getForPodcast(podcastId: Long): List<EpisodeEntity>

    /** When each podcast was last played, for archive backfill's priority order. */
    @Query("SELECT podcastId, MAX(lastPlayedAt) AS lastPlayedAt FROM episodes WHERE lastPlayedAt IS NOT NULL GROUP BY podcastId")
    suspend fun lastPlayedPerPodcast(): List<PodcastLastPlayed>

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

    @Query("SELECT COUNT(*) FROM episodes")
    suspend fun countAll(): Int

    /**
     * One keyset page of every episode row, ordered by id, for the backup archive (PLAN.md §9).
     * Keyset paging (id > :afterId) keeps each page cheap however deep it is.
     */
    @Query(
        """
        SELECT e.id AS id, p.feedUrl AS feedUrl, e.guid AS guid, e.title AS title,
               e.enclosureUrl AS enclosureUrl, e.pubDate AS pubDate, e.durationSec AS durationSec,
               e.descriptionHtml AS descriptionHtml
        FROM episodes e
        INNER JOIN podcasts p ON p.id = e.podcastId
        WHERE e.id > :afterId
        ORDER BY e.id
        LIMIT :limit
        """,
    )
    suspend fun archivePage(afterId: Long, limit: Int): List<EpisodeArchiveRow>

    /**
     * Episodes whose user state differs from the default, plus queued episodes and episodes with
     * listening history (PLAN.md §9). Keyed by feed URL + guid so another phone can match them.
     */
    @Query(
        """
        SELECT p.feedUrl AS feedUrl, e.guid AS guid, e.enclosureUrl AS enclosureUrl,
               e.title AS title, e.pubDate AS pubDate, e.durationSec AS durationSec,
               e.playState AS playState, e.positionMs AS positionMs, e.playedAt AS playedAt,
               e.lastPlayedAt AS lastPlayedAt, e.isStarred AS isStarred,
               e.autoDownloadBlocked AS autoDownloadBlocked
        FROM episodes e
        INNER JOIN podcasts p ON p.id = e.podcastId
        WHERE e.playState != 'UNPLAYED' OR e.positionMs > 0 OR e.playedAt IS NOT NULL
           OR e.lastPlayedAt IS NOT NULL OR e.isStarred = 1 OR e.autoDownloadBlocked = 1
           OR EXISTS (SELECT 1 FROM queue_items q WHERE q.episodeId = e.id)
           OR EXISTS (SELECT 1 FROM listening_sessions s WHERE s.episodeId = e.id)
        ORDER BY e.id
        """,
    )
    suspend fun backupStateRows(): List<EpisodeStateRow>
}

data class PodcastLastPlayed(val podcastId: Long, val lastPlayedAt: Long)

/** One episode row as written to the backup archive (PLAN.md §9 `archive.jsonl.gz`). */
data class EpisodeArchiveRow(
    val id: Long,
    val feedUrl: String,
    val guid: String,
    val title: String,
    val enclosureUrl: String,
    val pubDate: Long,
    val durationSec: Int?,
    val descriptionHtml: String?,
)

/** Non-default user state for one episode, keyed by feed URL + guid (PLAN.md §9 `episodes`). */
data class EpisodeStateRow(
    val feedUrl: String,
    val guid: String,
    val enclosureUrl: String,
    val title: String,
    val pubDate: Long,
    val durationSec: Int?,
    val playState: PlayState,
    val positionMs: Long,
    val playedAt: Long?,
    val lastPlayedAt: Long?,
    val isStarred: Boolean,
    val autoDownloadBlocked: Boolean,
)
