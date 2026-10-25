package com.steve.mcpodface.playback.auto

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.steve.mcpodface.data.db.DownloadListItem
import com.steve.mcpodface.data.db.DownloadState
import com.steve.mcpodface.data.db.EpisodeListItem
import com.steve.mcpodface.data.db.PlayState
import java.time.ZoneId

/** What a car row needs to know about an episode. */
data class CarEpisode(
    val id: Long,
    val title: String,
    val podcastTitle: String,
    val artworkUrl: String?,
    val pubDate: Long,
    val durationSec: Int?,
    val positionMs: Long,
    val playState: PlayState,
    val downloaded: Boolean,
    val isStarred: Boolean,
    val inQueue: Boolean,
)

fun EpisodeListItem.toCarEpisode() = CarEpisode(
    id = episode.id,
    title = episode.title,
    podcastTitle = podcastTitle,
    artworkUrl = episode.artworkUrl ?: podcastArtworkUrl,
    pubDate = episode.pubDate,
    durationSec = episode.durationSec,
    positionMs = episode.positionMs,
    playState = episode.playState,
    downloaded = downloadState == DownloadState.COMPLETED,
    isStarred = episode.isStarred,
    inQueue = inQueue,
)

/** The downloads query has no position or queue flag; rows from it show played/unplayed only. */
fun DownloadListItem.toCarEpisode() = CarEpisode(
    id = download.episodeId,
    title = episodeTitle,
    podcastTitle = podcastTitle,
    artworkUrl = artworkUrl,
    pubDate = pubDate,
    durationSec = durationSec,
    positionMs = 0,
    playState = playState,
    downloaded = download.state == DownloadState.COMPLETED,
    isStarred = isStarred,
    inQueue = false,
)

/**
 * Builds the MediaItems the car shows (docs/ANDROID-AUTO.md, task A1). Episode items carry only the
 * episode id as mediaId; PlaybackService resolves the stream or file when one is played.
 */
object BrowseItems {

    /**
     * A browsable folder. [childrenAsGrid] shows its browsable children as a grid (the Podcasts tab);
     * A1 sets the content-style extras from it (playable children are always a list).
     */
    fun folder(
        id: String,
        title: String,
        artworkUrl: String? = null,
        isPodcast: Boolean = false,
        childrenAsGrid: Boolean = false,
    ): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(if (isPodcast) MediaMetadata.MEDIA_TYPE_PODCAST else MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .setArtworkUri(artworkUri(artworkUrl))
                    .build(),
            )
            .build()

    /**
     * An episode row. A1 adds the [subtitle], completion and download badges, the [groupTitle] extra
     * (when [groupTitle] is passed) and the row actions (AutoCommands.ROW_ACTIONS).
     */
    fun episodeItem(episode: CarEpisode, now: Long, groupTitle: String? = null): MediaItem = MediaItem.Builder()
        .setMediaId(episode.id.toString())
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(episode.title)
                .setArtist(episode.podcastTitle)
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE)
                .setArtworkUri(artworkUri(episode.artworkUrl))
                .build(),
        )
        .build()

    /** "Podcast · 23 min left", or "Podcast · 1 h 05 min" when unplayed. A1. */
    fun subtitle(episode: CarEpisode): String? = null

    /** "Today", "Yesterday", "This week" or "Earlier" for [pubDate], in [zone]. A1. */
    fun groupTitle(pubDate: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): String? = null
}
