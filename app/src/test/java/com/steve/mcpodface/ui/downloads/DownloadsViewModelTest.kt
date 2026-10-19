package com.steve.mcpodface.ui.downloads

import com.google.common.truth.Truth.assertThat
import com.steve.mcpodface.data.db.DownloadEntity
import com.steve.mcpodface.data.db.DownloadListItem
import com.steve.mcpodface.data.db.DownloadSource
import com.steve.mcpodface.data.db.DownloadState
import com.steve.mcpodface.data.db.DownloadTotals
import com.steve.mcpodface.data.db.FeedType
import com.steve.mcpodface.data.db.PlayState
import com.steve.mcpodface.data.db.PodcastSettingsEntity
import com.steve.mcpodface.data.prefs.GlobalSettings
import org.junit.Test

private const val NOW = 1_792_411_200_000L // 2026-10-19T12:00Z
private const val MB = 1024L * 1024L

private fun item(
    id: Long,
    podcastId: Long = 1L,
    podcast: String = "Show",
    title: String = "Episode $id",
    state: DownloadState = DownloadState.COMPLETED,
    playState: PlayState = PlayState.UNPLAYED,
    pubDate: Long = NOW,
    downloadedAt: Long? = NOW,
    totalMb: Long? = 10,
    bytesDownloaded: Long = 0,
    isStarred: Boolean = false,
    error: String? = null,
) = DownloadListItem(
    download = DownloadEntity(
        episodeId = id,
        state = state,
        source = DownloadSource.AUTO,
        filePath = "/x/$id.mp3",
        bytesDownloaded = bytesDownloaded,
        totalBytes = totalMb?.let { it * MB },
        downloadedAt = downloadedAt,
        error = error,
    ),
    episodeTitle = title,
    podcastId = podcastId,
    podcastTitle = podcast,
    feedType = FeedType.EPISODIC,
    artworkUrl = null,
    pubDate = pubDate,
    durationSec = 1800,
    playState = playState,
    playedAt = if (playState == PlayState.PLAYED) NOW - 3_600_000L else null,
    isStarred = isStarred,
)

private fun row(
    id: Long,
    podcast: String = "Show",
    podcastId: Long = 1L,
    pubDate: Long = NOW,
    downloadedAt: Long? = NOW,
    playState: PlayState = PlayState.UNPLAYED,
) = DownloadedRow(
    item = item(id, podcastId = podcastId, podcast = podcast, pubDate = pubDate, downloadedAt = downloadedAt, playState = playState),
    sizeBytes = 10 * MB,
    deletionLabel = null,
)

class DownloadsViewModelTest {

    // ---- Sorting and filtering ----

    @Test fun dateDownloadedSortsNewestFirst() {
        val rows = listOf(
            row(1, downloadedAt = NOW - 3_000L),
            row(2, downloadedAt = NOW - 1_000L),
            row(3, downloadedAt = NOW - 2_000L),
        )

        val sorted = arrangeDownloaded(rows, DownloadSort.DATE_DOWNLOADED, unplayedOnly = false)

        assertThat(sorted.map { it.item.download.episodeId }).containsExactly(2L, 3L, 1L).inOrder()
    }

    @Test fun publishedSortsNewestFirst() {
        val rows = listOf(row(1, pubDate = 100), row(2, pubDate = 300), row(3, pubDate = 200))

        val sorted = arrangeDownloaded(rows, DownloadSort.PUBLISHED, unplayedOnly = false)

        assertThat(sorted.map { it.item.download.episodeId }).containsExactly(2L, 3L, 1L).inOrder()
    }

    @Test fun podcastSortIsAlphabeticalIgnoringCaseThenNewestFirst() {
        val rows = listOf(
            row(1, podcast = "beta", podcastId = 2, pubDate = 100),
            row(2, podcast = "Alpha", podcastId = 1, pubDate = 100),
            row(3, podcast = "Alpha", podcastId = 1, pubDate = 500),
        )

        val sorted = arrangeDownloaded(rows, DownloadSort.PODCAST, unplayedOnly = false)

        assertThat(sorted.map { it.item.download.episodeId }).containsExactly(3L, 2L, 1L).inOrder()
    }

    @Test fun unplayedOnlyHidesPlayedButKeepsInProgress() {
        val rows = listOf(
            row(1, playState = PlayState.PLAYED),
            row(2, playState = PlayState.UNPLAYED),
            row(3, playState = PlayState.IN_PROGRESS),
        )

        val visible = arrangeDownloaded(rows, DownloadSort.DATE_DOWNLOADED, unplayedOnly = true)

        assertThat(visible.map { it.item.download.episodeId }).containsExactly(2L, 3L)
    }

    @Test fun filterOffKeepsEveryRow() {
        val rows = listOf(row(1, playState = PlayState.PLAYED), row(2))

        assertThat(arrangeDownloaded(rows, DownloadSort.DATE_DOWNLOADED, unplayedOnly = false)).hasSize(2)
    }

    // ---- Sizes and totals ----

    @Test fun sizeUsesTotalThenFallsBackToBytesDownloaded() {
        assertThat(downloadSizeBytes(item(1, totalMb = 5))).isEqualTo(5 * MB)
        assertThat(downloadSizeBytes(item(2, totalMb = null, bytesDownloaded = 7 * MB))).isEqualTo(7 * MB)
    }

    @Test fun displayedTotalPrefersDiskSumWhenItDiffers() {
        assertThat(displayedTotalBytes(databaseBytes = 100, diskBytes = 90)).isEqualTo(90L)
    }

    @Test fun displayedTotalIsDatabaseTotalUntilDiskIsKnown() {
        assertThat(displayedTotalBytes(databaseBytes = 100, diskBytes = null)).isEqualTo(100L)
    }

    @Test fun displayedTotalMatchesWhenSumsAgree() {
        assertThat(displayedTotalBytes(databaseBytes = 100, diskBytes = 100)).isEqualTo(100L)
    }

    // ---- Snapshot and UI state ----

    @Test fun snapshotSplitsSectionsByState() {
        val items = listOf(
            item(1, state = DownloadState.DOWNLOADING),
            item(2, state = DownloadState.QUEUED),
            item(3, state = DownloadState.PAUSED_NETWORK),
            item(4, state = DownloadState.FAILED, error = "HTTP 503"),
            item(5, state = DownloadState.COMPLETED),
        )

        val snap = buildSnapshot(items, GlobalSettings(), emptyMap(), NOW)

        assertThat(snap.downloading.map { it.download.episodeId }).containsExactly(1L)
        assertThat(snap.queued.map { it.download.episodeId }).containsExactly(2L, 3L).inOrder()
        assertThat(snap.failed.map { it.download.episodeId }).containsExactly(4L)
        assertThat(snap.completed.map { it.item.download.episodeId }).containsExactly(5L)
    }

    @Test fun completedRowsGetDeletionLabelFromPerPodcastSettings() {
        // Podcast 1 overrides maxAge to 2 days; the download is downloaded now, so it is due in 2 days.
        val overrides = mapOf(
            1L to PodcastSettingsEntity(podcastId = 1L, maxAgeDays = 2),
        )
        val snap = buildSnapshot(
            listOf(item(1, podcastId = 1L, playState = PlayState.UNPLAYED, downloadedAt = NOW)),
            GlobalSettings(maxAgeDays = 30),
            overrides,
            NOW,
        )

        assertThat(snap.completed.single().deletionLabel).isEqualTo("Deletes in 2 days · older than 2 days")
    }

    @Test fun uiStateDropsSelectionsThatAreNoLongerDownloaded() {
        val snap = buildSnapshot(
            listOf(item(1), item(2, state = DownloadState.QUEUED)),
            GlobalSettings(),
            emptyMap(),
            NOW,
        )

        val ui = buildUiState(
            snapshot = snap,
            totals = DownloadTotals(episodeCount = 1, totalBytes = 10 * MB),
            sizes = emptyList(),
            storage = StorageStats(diskBytes = 9 * MB, freeBytes = 1_000L),
            controls = DownloadsControls(selected = setOf(1L, 2L, 99L)),
        )

        assertThat(ui.selected).containsExactly(1L)
        assertThat(ui.totalBytes).isEqualTo(9 * MB)
        assertThat(ui.freeBytes).isEqualTo(1_000L)
        assertThat(ui.loaded).isTrue()
    }

    @Test fun uiStateAppliesSortAndFilterToDownloadedOnly() {
        val snap = buildSnapshot(
            listOf(
                item(1, playState = PlayState.PLAYED, pubDate = 100),
                item(2, playState = PlayState.UNPLAYED, pubDate = 300),
            ),
            GlobalSettings(),
            emptyMap(),
            NOW,
        )

        val ui = buildUiState(
            snapshot = snap,
            totals = DownloadTotals(episodeCount = 2, totalBytes = 20 * MB),
            sizes = emptyList(),
            storage = StorageStats(diskBytes = null, freeBytes = null),
            controls = DownloadsControls(sort = DownloadSort.PUBLISHED, unplayedOnly = true),
        )

        assertThat(ui.downloaded.map { it.item.download.episodeId }).containsExactly(2L)
        assertThat(ui.allDownloaded).hasSize(2)
        assertThat(ui.totalBytes).isEqualTo(20 * MB)
    }
}
