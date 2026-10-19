package com.steve.mcpodface.widget

import com.google.common.truth.Truth.assertThat
import com.steve.mcpodface.playback.NowPlaying
import org.junit.Test

class NowPlayingWidgetStateTest {

    private val loaded = WidgetEpisode(episodeId = 1, title = "Loaded", podcastTitle = "Show A", artworkUrl = null)
    private val last = WidgetEpisode(episodeId = 2, title = "Last", podcastTitle = "Show B", artworkUrl = "https://x/a.jpg")

    private fun playing(id: Long, isPlaying: Boolean) = NowPlaying(
        episodeId = id,
        isPlaying = isPlaying,
        positionMs = 12_000,
        durationMs = 60_000,
    )

    // ---- resolveWidgetContent ---------------------------------------------------------------

    @Test fun `playing episode shows as playing, not last played`() {
        val content = resolveWidgetContent(playing(1, isPlaying = true), loaded, last)
        assertThat(content).isEqualTo(WidgetContent.Episode(loaded, isPlaying = true, isLastPlayed = false))
    }

    @Test fun `paused loaded episode shows as paused`() {
        val content = resolveWidgetContent(playing(1, isPlaying = false), loaded, last)
        assertThat(content).isEqualTo(WidgetContent.Episode(loaded, isPlaying = false, isLastPlayed = false))
    }

    @Test fun `loaded episode whose row could not be read falls back to last played`() {
        val content = resolveWidgetContent(playing(1, isPlaying = true), null, last)
        assertThat(content).isEqualTo(WidgetContent.Episode(last, isPlaying = false, isLastPlayed = true))
    }

    @Test fun `nothing loaded shows last played, paused`() {
        val content = resolveWidgetContent(null, null, last)
        assertThat(content).isEqualTo(WidgetContent.Episode(last, isPlaying = false, isLastPlayed = true))
    }

    @Test fun `nothing loaded and nothing played shows empty`() {
        assertThat(resolveWidgetContent(null, null, null)).isEqualTo(WidgetContent.Empty)
    }

    @Test fun `loaded episode with no history still shows the loaded episode`() {
        val content = resolveWidgetContent(playing(1, isPlaying = true), loaded, null)
        assertThat(content).isEqualTo(WidgetContent.Episode(loaded, isPlaying = true, isLastPlayed = false))
    }

    @Test fun `nowPlaying without a row and no history is empty`() {
        assertThat(resolveWidgetContent(playing(1, isPlaying = true), null, null)).isEqualTo(WidgetContent.Empty)
    }

    // ---- widgetPlaybackKey -------------------------------------------------------------------

    @Test fun `key is null when nothing is loaded`() {
        assertThat(widgetPlaybackKey(null)).isNull()
    }

    @Test fun `key ignores position, duration and speed`() {
        val a = widgetPlaybackKey(playing(1, isPlaying = true).copy(positionMs = 1_000))
        val b = widgetPlaybackKey(playing(1, isPlaying = true).copy(positionMs = 9_000, speed = 2f))
        assertThat(a).isEqualTo(b)
    }

    @Test fun `key changes when the episode or play state changes`() {
        val base = widgetPlaybackKey(playing(1, isPlaying = true))
        assertThat(widgetPlaybackKey(playing(1, isPlaying = false))).isNotEqualTo(base)
        assertThat(widgetPlaybackKey(playing(2, isPlaying = true))).isNotEqualTo(base)
    }
}
