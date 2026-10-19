package com.steve.mcpodface.widget

import com.steve.mcpodface.playback.NowPlaying

/** The episode fields the widget shows. Plain data, so the rules below need no Android or Room. */
data class WidgetEpisode(
    val episodeId: Long,
    val title: String,
    val podcastTitle: String?,
    val artworkUrl: String?,
)

/** What the widget draws. */
sealed interface WidgetContent {
    /**
     * An episode to show. [isPlaying] is false when paused, or when this is the last played episode
     * and nothing is loaded (then the play button starts it).
     */
    data class Episode(
        val episode: WidgetEpisode,
        val isPlaying: Boolean,
        val isLastPlayed: Boolean,
    ) : WidgetContent

    data object Empty : WidgetContent
}

/**
 * Chooses what the widget shows.
 *
 * - Something loaded in the player: that episode, playing or paused.
 * - Nothing loaded: the last played episode, paused, so its play button can start it.
 * - Neither: [WidgetContent.Empty].
 *
 * [nowPlayingEpisode] is the loaded episode's row (null if it could not be read) and [lastPlayed]
 * the most recently played row.
 */
fun resolveWidgetContent(
    nowPlaying: NowPlaying?,
    nowPlayingEpisode: WidgetEpisode?,
    lastPlayed: WidgetEpisode?,
): WidgetContent = when {
    nowPlaying != null && nowPlayingEpisode != null ->
        WidgetContent.Episode(nowPlayingEpisode, isPlaying = nowPlaying.isPlaying, isLastPlayed = false)
    lastPlayed != null ->
        WidgetContent.Episode(lastPlayed, isPlaying = false, isLastPlayed = true)
    else -> WidgetContent.Empty
}

/**
 * The part of playback state that changes what the widget draws. Position, speed and buffering
 * are left out, so the widget is not redrawn on every position tick.
 */
data class WidgetPlaybackKey(val episodeId: Long, val isPlaying: Boolean)

fun widgetPlaybackKey(nowPlaying: NowPlaying?): WidgetPlaybackKey? =
    nowPlaying?.let { WidgetPlaybackKey(it.episodeId, it.isPlaying) }
