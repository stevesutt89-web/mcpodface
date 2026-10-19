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
