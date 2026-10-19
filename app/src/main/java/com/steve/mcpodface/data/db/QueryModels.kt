package com.steve.mcpodface.data.db

import androidx.room.Embedded

// Read models returned by DAO queries (task 1.3). Column names must match these property names.

/** An episode row for lists (Latest, podcast detail, search results). */
data class EpisodeListItem(
    @Embedded val episode: EpisodeEntity,
    val podcastTitle: String,
    val podcastArtworkUrl: String?,
    /** null when there is no downloads row. */
    val downloadState: DownloadState?,
    val downloadBytes: Long?,
    val inQueue: Boolean,
)

/** A subscribed podcast for the Library grid. */
data class LibraryPodcast(
    @Embedded val podcast: PodcastEntity,
    val unplayedCount: Int,
)

/** Completed downloads grouped by podcast, for the Downloads storage header. */
data class PodcastDownloadSize(
    val podcastId: Long,
    val podcastTitle: String,
    val episodeCount: Int,
    val totalBytes: Long,
)

data class DownloadTotals(
    val episodeCount: Int,
    val totalBytes: Long,
)

/**
 * A downloads row joined with its episode and podcast (PLAN.md §8.7). `artworkUrl` is the episode's
 * artwork, falling back to the podcast's. `feedType` feeds the per-podcast effective settings.
 */
data class DownloadListItem(
    @Embedded val download: DownloadEntity,
    val episodeTitle: String,
    val podcastId: Long,
    val podcastTitle: String,
    val feedType: FeedType,
    val artworkUrl: String?,
    val pubDate: Long,
    val durationSec: Int?,
    val playState: PlayState,
    val playedAt: Long?,
    val isStarred: Boolean,
)

/**
 * An episode with listening sessions, for the History screen (PLAN.md §8.3). Carries the list-row
 * columns of [EpisodeListItem] plus when it was last listened to and the total time listened.
 */
data class HistoryItem(
    @Embedded val episode: EpisodeEntity,
    val podcastTitle: String,
    val podcastArtworkUrl: String?,
    /** null when there is no downloads row. */
    val downloadState: DownloadState?,
    val downloadBytes: Long?,
    val inQueue: Boolean,
    /** Latest session end: max(startedAt + listenedMs). */
    val lastListenedAt: Long,
    val totalListenedMs: Long,
)

/** Listening time for one podcast, for the Stats top-podcasts list (PLAN.md §13). */
data class PodcastListeningTotal(
    val podcastId: Long,
    val podcastTitle: String,
    val artworkUrl: String?,
    val listenedMs: Long,
)

/** One listening session's start and wall-clock listened time, for the Stats day buckets. */
data class SessionTimeRow(
    val startedAt: Long,
    val listenedMs: Long,
)

/** Episodes finished (PLAYED with playedAt): [total] all time, [last30Days] since the window start. */
data class FinishedCounts(
    val total: Int,
    val last30Days: Int,
)
