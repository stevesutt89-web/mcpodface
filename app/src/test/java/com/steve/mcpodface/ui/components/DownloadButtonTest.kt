package com.steve.mcpodface.ui.components

import com.google.common.truth.Truth.assertThat
import com.steve.mcpodface.data.db.DownloadEntity
import com.steve.mcpodface.data.db.DownloadSource
import com.steve.mcpodface.data.db.DownloadState
import org.junit.Test

class DownloadButtonTest {
    private fun downloading(bytes: Long, total: Long?) = DownloadEntity(
        episodeId = 1,
        state = DownloadState.DOWNLOADING,
        source = DownloadSource.MANUAL,
        bytesDownloaded = bytes,
        totalBytes = total,
    )

    @Test fun progressIsTheFractionOfKnownSize() {
        assertThat(downloadProgressFraction(downloading(bytes = 25L, total = 100L))).isWithin(1e-6f).of(0.25f)
    }

    @Test fun unknownOrZeroSizeGivesIndeterminateProgress() {
        assertThat(downloadProgressFraction(downloading(bytes = 10L, total = null))).isNull()
        assertThat(downloadProgressFraction(downloading(bytes = 0L, total = 0L))).isNull()
    }

    @Test fun progressIsClampedToTheRing() {
        assertThat(downloadProgressFraction(downloading(bytes = 150L, total = 100L))).isEqualTo(1f)
        assertThat(downloadProgressFraction(downloading(bytes = 0L, total = 100L))).isEqualTo(0f)
    }
}
