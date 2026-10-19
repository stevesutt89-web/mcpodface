package com.steve.mcpodface.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Index
import androidx.room.PrimaryKey

// Schema from PLAN.md §4. Any change here needs a version bump and a real Migration in AppDatabase.
// Child tables cascade on podcast/episode delete, so never write podcasts or episodes with
// OnConflictStrategy.REPLACE (it deletes the row first and wipes children). Use @Upsert or @Update.

@Entity(tableName = "podcasts", indices = [Index(value = ["feedUrl"], unique = true)])
data class PodcastEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val feedUrl: String,
    val title: String,
    val author: String? = null,
    val descriptionHtml: String? = null,
    val artworkUrl: String? = null,
    val websiteUrl: String? = null,
    val podcastIndexId: Long? = null,
    val podcastIndexEpisodeCount: Int? = null,
    val itunesId: Long? = null,
    val feedType: FeedType = FeedType.EPISODIC,
    val isSubscribed: Boolean = false,
    val subscribedAt: Long? = null,
    val lastRefreshAt: Long? = null,
    val lastNewEpisodeAt: Long? = null,
    val httpEtag: String? = null,
    val httpLastModified: String? = null,
    val authUser: String? = null,
    val authPassword: String? = null,
    /** Set when the feed returns 410 Gone (PLAN.md §5). */
    val feedGone: Boolean = false,
)

/** Per-podcast overrides. null = inherit the global setting. */
@Entity(
    tableName = "podcast_settings",
    foreignKeys = [ForeignKey(PodcastEntity::class, ["id"], ["podcastId"], onDelete = ForeignKey.CASCADE)],
)
data class PodcastSettingsEntity(
    @PrimaryKey val podcastId: Long,
    val autoDownload: Boolean? = null,
    val keepLatestCount: Int? = null,
    val deletePlayed: Boolean? = null,
    val maxAgeDays: Int? = null,
    val allowCellular: Boolean? = null,
    /** null = default by feed type (SERIAL → OLDEST_FIRST). */
    val sortOrder: SortOrder? = null,
    val playbackSpeed: Float? = null,
    val skipIntroSec: Int = 0,
    val skipOutroSec: Int = 0,
    val notifyNewEpisodes: Boolean = true,
    val autoAddToQueue: QueueAdd = QueueAdd.OFF,
    val backfillEnabled: Boolean? = null,
)

@Entity(
    tableName = "episodes",
    foreignKeys = [ForeignKey(PodcastEntity::class, ["id"], ["podcastId"], onDelete = ForeignKey.CASCADE)],
    indices = [
        Index(value = ["podcastId", "guid"], unique = true),
        Index("pubDate"),
        Index("enclosureUrl"),
    ],
)
data class EpisodeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val podcastId: Long,
    /** Fallback order: podcast:guid / guid, then enclosureUrl, then hash(title + pubDate). */
    val guid: String,
    val title: String,
    val descriptionHtml: String? = null,
    /** Full plain text (HTML stripped). Indexed for search. */
    val descriptionText: String = "",
    /** First ~300 chars of descriptionText, for list rows. */
    val summaryText: String = "",
    val pubDate: Long,
    val durationSec: Int? = null,
    val enclosureUrl: String,
    val enclosureType: String? = null,
    val enclosureLength: Long? = null,
    val artworkUrl: String? = null,
    val season: Int? = null,
    val episodeNumber: Int? = null,
    val chaptersUrl: String? = null,
    val transcriptUrl: String? = null,
    val origin: EpisodeOrigin = EpisodeOrigin.FEED,
    val inCurrentFeed: Boolean = true,
    val playState: PlayState = PlayState.UNPLAYED,
    val positionMs: Long = 0,
    val playedAt: Long? = null,
    val lastPlayedAt: Long? = null,
    val isStarred: Boolean = false,
    /** Tombstone: auto-download never picks this episode again (PLAN.md §6.4). */
    val autoDownloadBlocked: Boolean = false,
    val firstSeenAt: Long,
)

/** Full-text index over episodes (PLAN.md §7). Room creates the sync triggers. rowid == episodes.id. */
@Fts4(
    contentEntity = EpisodeEntity::class,
    tokenizer = FtsOptions.TOKENIZER_UNICODE61,
    tokenizerArgs = ["remove_diacritics=1"],
)
@Entity(tableName = "episode_fts")
data class EpisodeFts(
    val title: String,
    val descriptionText: String,
)

@Entity(
    tableName = "downloads",
    foreignKeys = [ForeignKey(EpisodeEntity::class, ["id"], ["episodeId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("state")],
)
data class DownloadEntity(
    @PrimaryKey val episodeId: Long,
    val state: DownloadState,
    val source: DownloadSource,
    val filePath: String? = null,
    val bytesDownloaded: Long = 0,
    val totalBytes: Long? = null,
    val downloadedAt: Long? = null,
    val workId: String? = null,
    val error: String? = null,
    val attempts: Int = 0,
)

@Entity(
    tableName = "backfill_state",
    foreignKeys = [ForeignKey(PodcastEntity::class, ["id"], ["podcastId"], onDelete = ForeignKey.CASCADE)],
)
data class BackfillStateEntity(
    @PrimaryKey val podcastId: Long,
    val status: BackfillStatus = BackfillStatus.PENDING,
    val rssNextPageUrl: String? = null,
    val rssPagingDone: Boolean = false,
    val indexDone: Boolean = false,
    val indexCursor: String? = null,
    val requestsMade: Int = 0,
    val episodesAdded: Int = 0,
    val oldestKnownPubDate: Long? = null,
    val lastAttemptAt: Long? = null,
    /** Backoff after errors / 429. */
    val nextEligibleAt: Long? = null,
    val lastError: String? = null,
)

@Entity(
    tableName = "queue_items",
    foreignKeys = [ForeignKey(EpisodeEntity::class, ["id"], ["episodeId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("position")],
)
data class QueueItemEntity(
    @PrimaryKey val episodeId: Long,
    val position: Int,
)

/** History and stats. */
@Entity(
    tableName = "listening_sessions",
    foreignKeys = [ForeignKey(EpisodeEntity::class, ["id"], ["episodeId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("episodeId"), Index("startedAt")],
)
data class ListeningSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val episodeId: Long,
    val startedAt: Long,
    val listenedMs: Long,
    val speed: Float,
)

@Entity(tableName = "recent_searches")
data class RecentSearchEntity(
    @PrimaryKey val query: String,
    val lastUsedAt: Long,
    val podcastId: Long? = null,
)

/** 24h cache of catalogue (Podcast Index / iTunes) responses, keyed by request. */
@Entity(tableName = "catalog_cache")
data class CatalogCacheEntity(
    @PrimaryKey val key: String,
    val json: String,
    val fetchedAt: Long,
)

/** A playlist (PLAN.md §8.11). SMART playlists keep their rules as JSON and have no items. */
@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val kind: PlaylistKind,
    /** [com.steve.mcpodface.data.playlist.SmartRules] as JSON; null unless SMART. */
    val rulesJson: String? = null,
    /** Order in the Library list. */
    val position: Int = 0,
    val createdAt: Long,
)

/** An episode in a MANUAL playlist, in [position] order. */
@Entity(
    tableName = "playlist_items",
    primaryKeys = ["playlistId", "episodeId"],
    foreignKeys = [
        ForeignKey(PlaylistEntity::class, ["id"], ["playlistId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(EpisodeEntity::class, ["id"], ["episodeId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("episodeId")],
)
data class PlaylistItemEntity(
    val playlistId: Long,
    val episodeId: Long,
    val position: Int,
    val addedAt: Long,
)
