package com.steve.mcpodface.ui.components

import com.google.common.truth.Truth.assertThat
import com.steve.mcpodface.data.db.DownloadState
import org.junit.Test

class EpisodeSpokenSummaryTest {

    @Test
    fun `joins title podcast date and state in order`() {
        val summary = episodeSpokenSummary(
            title = "The cold open",
            podcastTitle = "Radiolab",
            dateText = "3h ago",
            stateText = "45 min",
            downloadLabel = null,
            starred = false,
            inUpNext = false,
            archived = false,
        )
        assertThat(summary).isEqualTo("The cold open, Radiolab, 3h ago, 45 min")
    }

    @Test
    fun `appends download, starred, up next and archived flags`() {
        val summary = episodeSpokenSummary(
            title = "Part two",
            podcastTitle = "Radiolab",
            dateText = "2d ago",
            stateText = "Played",
            downloadLabel = downloadStateLabel(DownloadState.COMPLETED),
            starred = true,
            inUpNext = true,
            archived = true,
        )
        assertThat(summary).isEqualTo(
            "Part two, Radiolab, 2d ago, Played, Downloaded, Starred, In Up Next, Archived",
        )
    }

    @Test
    fun `skips blank parts and a hidden podcast title`() {
        val summary = episodeSpokenSummary(
            title = "Only a title",
            podcastTitle = null,
            dateText = "",
            stateText = "",
            downloadLabel = null,
            starred = false,
            inUpNext = false,
            archived = false,
        )
        assertThat(summary).isEqualTo("Only a title")
    }

    @Test
    fun `blank podcast title is skipped`() {
        val summary = episodeSpokenSummary(
            title = "Episode",
            podcastTitle = "  ",
            dateText = "Today",
            stateText = "",
            downloadLabel = null,
            starred = false,
            inUpNext = false,
            archived = false,
        )
        assertThat(summary).isEqualTo("Episode, Today")
    }

    @Test
    fun `every download state has a spoken label`() {
        assertThat(downloadStateLabel(DownloadState.COMPLETED)).isEqualTo("Downloaded")
        assertThat(downloadStateLabel(DownloadState.DOWNLOADING)).isEqualTo("Downloading")
        assertThat(downloadStateLabel(DownloadState.QUEUED)).isEqualTo("Download queued")
        assertThat(downloadStateLabel(DownloadState.FAILED)).isEqualTo("Download failed")
        assertThat(downloadStateLabel(DownloadState.PAUSED_NETWORK)).isEqualTo("Paused, waiting for network")
    }
}
