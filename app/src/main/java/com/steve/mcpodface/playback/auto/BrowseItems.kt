package com.steve.mcpodface.playback.auto

import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaConstants
import com.steve.mcpodface.data.db.DownloadListItem
import com.steve.mcpodface.data.db.DownloadState
import com.steve.mcpodface.data.db.EpisodeListItem
import com.steve.mcpodface.data.db.PlayState
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

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
@OptIn(UnstableApi::class) // MediaConstants extras keys are UnstableApi
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
                    .setExtras(
                        Bundle().apply {
                            putInt(
                                MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
                                if (childrenAsGrid) MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM
                                else MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM,
                            )
                            putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
                        },
                    )
                    .build(),
            )
            .build()

    /**
     * An episode row. A1 adds the [subtitle], completion and download badges, the [groupTitle] extra
     * (when [groupTitle] is passed) and the row actions (AutoCommands.ROW_ACTIONS).
     */
    fun episodeItem(episode: CarEpisode, now: Long, groupTitle: String? = null): MediaItem {
        val durationMs = episode.durationSec?.let { it * 1000L }
        val extras = Bundle().apply {
            val status = completionStatus(episode)
            putInt(MediaConstants.EXTRAS_KEY_COMPLETION_STATUS, status)
            completionFraction(episode)?.let { putDouble(MediaConstants.EXTRAS_KEY_COMPLETION_PERCENTAGE, it) }
            putLong(
                MediaConstants.EXTRAS_KEY_DOWNLOAD_STATUS,
                if (episode.downloaded) MediaConstants.EXTRAS_VALUE_STATUS_DOWNLOADED
                else MediaConstants.EXTRAS_VALUE_STATUS_NOT_DOWNLOADED,
            )
            groupTitle?.let { putString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, it) }
        }
        return MediaItem.Builder()
            .setMediaId(episode.id.toString())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(episode.title)
                    .setArtist(episode.podcastTitle)
                    .setSubtitle(subtitle(episode))
                    .setDurationMs(durationMs)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE)
                    .setArtworkUri(artworkUri(episode.artworkUrl))
                    .setSupportedCommands(AutoCommands.ROW_ACTIONS)
                    .setExtras(extras)
                    .build(),
            )
            .build()
    }

    /** "Podcast · 23 min left" when part played, or "Podcast · 1 h 05 min" otherwise. A1. */
    fun subtitle(episode: CarEpisode): String? {
        val durationSec = episode.durationSec ?: return episode.podcastTitle
        val label = if (isPartlyPlayed(episode)) {
            val remainingMs = (durationSec * 1000L - episode.positionMs).coerceAtLeast(0L)
            val remainingMin = ((remainingMs + MS_PER_MINUTE - 1) / MS_PER_MINUTE).coerceAtLeast(1L)
            "${formatMinutes(remainingMin)} left"
        } else {
            formatMinutes(durationSec / 60L)
        }
        return "${episode.podcastTitle} · $label"
    }

    /** "Today", "Yesterday", "This week" or "Earlier" for [pubDate], in [zone]. A1. */
    fun groupTitle(pubDate: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): String? {
        val pubDay = Instant.ofEpochMilli(pubDate).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        // Future pubDates give a negative count, which counts as today.
        val daysAgo = ChronoUnit.DAYS.between(pubDay, today)
        return when {
            daysAgo <= 0L -> "Today"
            daysAgo == 1L -> "Yesterday"
            daysAgo <= 6L -> "This week"
            else -> "Earlier"
        }
    }

    private const val MS_PER_MINUTE = 60_000L

    private fun isPartlyPlayed(episode: CarEpisode) =
        episode.positionMs > 0 && episode.playState != PlayState.PLAYED

    private fun completionStatus(episode: CarEpisode): Int = when {
        episode.playState == PlayState.PLAYED -> MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_FULLY_PLAYED
        episode.positionMs > 0 -> MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_PARTIALLY_PLAYED
        else -> MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_NOT_PLAYED
    }

    /** 0.0 to 1.0 for a part-played episode with a known duration, otherwise null. */
    private fun completionFraction(episode: CarEpisode): Double? {
        if (completionStatus(episode) != MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_PARTIALLY_PLAYED) return null
        val durationMs = (episode.durationSec ?: return null).toLong() * 1000L
        if (durationMs <= 0L) return null
        return (episode.positionMs.toDouble() / durationMs).coerceIn(0.0, 1.0)
    }

    /** "45 min", or "1 h 05 min" from 60 minutes. */
    private fun formatMinutes(minutes: Long): String =
        if (minutes < 60L) "$minutes min"
        else "${minutes / 60L} h ${(minutes % 60L).toString().padStart(2, '0')} min"
}
