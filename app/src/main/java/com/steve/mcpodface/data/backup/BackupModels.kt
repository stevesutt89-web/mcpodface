package com.steve.mcpodface.data.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Backup format v1 (PLAN.md §9). These models are the format: they use plain types only and are
// deliberately decoupled from the Room schema. Field names are part of the file format; don't rename
// them. Enums are stored by their constant name, as in the database.

/** Current backup format version. Bump only with a new reader path in the importer. */
const val BACKUP_FORMAT_VERSION = 1

/** Zip entry names. */
object BackupEntries {
    const val MANIFEST = "manifest.json"
    const val DATA = "data.json"
    const val OPML = "subscriptions.opml"
    const val ARCHIVE = "archive.jsonl.gz"
}

/** JSON used for every backup file. Nulls are written explicitly and defaults are always encoded. */
internal val BackupJson = Json {
    encodeDefaults = true
}

/** manifest.json: what the backup contains. Readable without parsing data.json. */
@Serializable
data class BackupManifest(
    val formatVersion: Int,
    val appVersion: String,
    /** ISO-8601 UTC instant, from the injected Clock. */
    val createdAt: String,
    val counts: BackupCounts,
)

@Serializable
data class BackupCounts(
    val podcasts: Int,
    val episodes: Int,
    val queue: Int,
    val history: Int,
    val backfill: Int,
    /** Null when the archive was not included in this backup. */
    val archiveEpisodes: Int? = null,
)

/** data.json: all user data except the archive and the OPML subscriptions. */
@Serializable
data class BackupData(
    val formatVersion: Int,
    /** Every global setting, DataStore key name → string value (PLAN.md §10). */
    val settings: Map<String, String>,
    /** Subscribed podcasts only. */
    val podcasts: List<PodcastBackup>,
    /** Episodes with non-default state, queued, or with listening history. */
    val episodes: List<EpisodeBackup>,
    /** Up Next, in queue order. */
    val queue: List<QueueBackup>,
    /** Listening sessions, oldest first. */
    val history: List<HistoryBackup>,
    val backfill: List<BackfillBackup>,
)

@Serializable
data class PodcastBackup(
    val feedUrl: String,
    val title: String,
    val artworkUrl: String? = null,
    val subscribedAt: Long? = null,
    val podcastIndexId: Long? = null,
    /** HTTP basic auth user name for private feeds. */
    val authUser: String? = null,
    /** Always null in new backups: passwords are left out and re-entered after a restore. Kept so old files still read. */
    val authPassword: String? = null,
    /** Per-podcast overrides. Null means every setting is inherited. */
    val settings: PodcastSettingsBackup? = null,
)

/** Mirrors PodcastSettingsEntity. Enums are stored by name. */
@Serializable
data class PodcastSettingsBackup(
    val autoDownload: Boolean? = null,
    val keepLatestCount: Int? = null,
    val deletePlayed: Boolean? = null,
    val maxAgeDays: Int? = null,
    val allowCellular: Boolean? = null,
    val sortOrder: String? = null,
    val playbackSpeed: Float? = null,
    val skipIntroSec: Int = 0,
    val skipOutroSec: Int = 0,
    val notifyNewEpisodes: Boolean = true,
    val autoAddToQueue: String = "OFF",
    val backfillEnabled: Boolean? = null,
)

/**
 * One episode's user state. The key is feedUrl + guid, with enclosureUrl as the fallback on another
 * phone. The feed metadata lets the importer recreate the row if it has dropped out of the feed.
 */
@Serializable
data class EpisodeBackup(
    val feedUrl: String,
    val guid: String,
    val enclosureUrl: String,
    val title: String,
    val pubDate: Long,
    val durationSec: Int? = null,
    val playState: String,
    val positionMs: Long = 0,
    val playedAt: Long? = null,
    val lastPlayedAt: Long? = null,
    val isStarred: Boolean = false,
    val autoDownloadBlocked: Boolean = false,
)

/** One Up Next entry. Its position is its index in the list. */
@Serializable
data class QueueBackup(
    val feedUrl: String,
    val guid: String,
)

@Serializable
data class HistoryBackup(
    val feedUrl: String,
    val guid: String,
    val startedAt: Long,
    val listenedMs: Long,
    val speed: Float,
)

@Serializable
data class BackfillBackup(
    val feedUrl: String,
    val status: String,
    val rssNextPageUrl: String? = null,
    val rssPagingDone: Boolean = false,
    val indexDone: Boolean = false,
    val indexCursor: String? = null,
    val requestsMade: Int = 0,
    val episodesAdded: Int = 0,
    val oldestKnownPubDate: Long? = null,
)

/**
 * One line of archive.jsonl.gz: a single episode row from the feed (PLAN.md §9). The file holds every
 * episode row, so the importer doesn't need to backfill again.
 */
@Serializable
data class ArchiveEpisodeLine(
    val feedUrl: String,
    val guid: String,
    val title: String,
    val enclosureUrl: String,
    val pubDate: Long,
    val durationSec: Int? = null,
    val description: String? = null,
)
