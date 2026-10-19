package com.steve.mcpodface.domain.policy

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.steve.mcpodface.data.db.DownloadState
import com.steve.mcpodface.data.db.PlayState
import com.steve.mcpodface.data.db.SortOrder
import org.junit.Test

class DownloadPolicyTest {

    private fun select(episodes: List<PolicyEpisode>, s: EffectiveSettings = settings()) =
        DownloadPolicy.select(episodes, s, NOW)

    // --- NEWEST_FIRST ---

    @Test fun firstSubscribeDownloadsOnlyRecentEpisodesInWindow() {
        // Window = ids 10..6 (0 to 28 days old); never the back catalogue.
        assertThat(select(weekly())).containsExactly(10L, 9L, 8L, 7L, 6L).inOrder()
    }

    @Test fun autoDownloadOffSelectsNothing() {
        assertThat(select(weekly(), settings(autoDownload = false))).isEmpty()
    }

    @Test fun playedEpisodesInWindowAreSkipped() {
        val eps = weekly().map { if (it.id == 9L) it.copy(playState = PlayState.PLAYED) else it }
        assertThat(select(eps)).containsExactly(10L, 8L, 7L, 6L).inOrder()
    }

    @Test fun playingTheNewestDoesNotPullInOlderEpisodes() {
        // Window stays the 5 newest whether played or not, so nothing beyond it is ever chosen.
        val eps = weekly().map {
            when (it.id) {
                10L -> it.copy(playState = PlayState.PLAYED, download = dl())
                in 7L..9L -> it.copy(download = dl())
                else -> it
            }
        }
        assertThat(select(eps, settings(maxAgeDays = 0))).containsExactly(6L)
        assertThat(select(eps, settings(maxAgeDays = 0))).doesNotContain(5L)
    }

    @Test fun tombstonedEpisodesAreSkipped() {
        val eps = weekly().map { if (it.id == 10L) it.copy(autoDownloadBlocked = true) else it }
        assertThat(select(eps)).containsExactly(9L, 8L, 7L, 6L).inOrder()
    }

    @Test fun anyExistingDownloadRowIsSkipped() {
        for (state in DownloadState.entries) {
            val eps = weekly().map { if (it.id == 10L) it.copy(download = dl(state = state)) else it }
            assertWithMessage("state=$state").that(select(eps)).doesNotContain(10L)
        }
    }

    @Test fun maxAgeExcludesOldEpisodes() {
        val eps = listOf(ep(1, days = 31.0), ep(2, days = 29.0))
        assertThat(select(eps)).containsExactly(2L)
    }

    @Test fun maxAgeZeroMeansNeverExpire() {
        val eps = listOf(ep(1, days = 400.0), ep(2, days = 29.0))
        assertThat(select(eps, settings(maxAgeDays = 0))).containsExactly(2L, 1L).inOrder()
    }

    @Test fun monthlyShowOlderThanMaxAgeDownloadsNothing() {
        val eps = listOf(ep(1, days = 70.0), ep(2, days = 40.0))
        assertThat(select(eps)).isEmpty()
    }

    @Test fun fewerEpisodesThanNSelectsAllEligible() {
        assertThat(select(weekly(2))).containsExactly(2L, 1L).inOrder()
    }

    @Test fun emptyPodcastSelectsNothing() {
        assertThat(select(emptyList())).isEmpty()
    }

    @Test fun samePubDateBreaksTiesByIdDescending() {
        val eps = listOf(ep(1, days = 1.0), ep(2, days = 1.0), ep(3, days = 1.0))
        assertThat(select(eps, settings(keep = 2))).containsExactly(3L, 2L).inOrder()
    }

    @Test fun windowRespectsKeepLatestCount() {
        assertThat(select(weekly(), settings(keep = 2))).containsExactly(10L, 9L).inOrder()
    }

    // --- OLDEST_FIRST ---

    private val serial = settings(sort = SortOrder.OLDEST_FIRST, keep = 3)

    @Test fun oldestFirstWithNothingPlayedStartsAtTheBeginning() {
        assertThat(select(weekly(), serial)).containsExactly(1L, 2L, 3L).inOrder()
    }

    @Test fun oldestFirstResumesAfterNewestPlayedEpisode() {
        val eps = weekly().map { if (it.id <= 4) it.copy(playState = PlayState.PLAYED) else it }
        assertThat(select(eps, serial)).containsExactly(5L, 6L, 7L).inOrder()
    }

    @Test fun oldestFirstIgnoresMaxAge() {
        val eps = listOf(ep(1, days = 900.0), ep(2, days = 800.0))
        assertThat(select(eps, serial.copy(maxAgeDays = 30))).containsExactly(1L, 2L).inOrder()
    }

    @Test fun backfilledEpisodesBehindResumePointNeverDownload() {
        // Listening at ep 5; backfill then adds much older episodes 101..103.
        val eps = weekly().map { if (it.id <= 5) it.copy(playState = PlayState.PLAYED) else it } +
            listOf(ep(101, days = 500.0), ep(102, days = 490.0), ep(103, days = 480.0))
        assertThat(select(eps, serial)).containsExactly(6L, 7L, 8L).inOrder()
    }

    @Test fun skippedUnplayedEpisodesBehindResumePointAreNotDownloaded() {
        val eps = weekly().map { if (it.id == 5L) it.copy(playState = PlayState.PLAYED) else it }
        assertThat(select(eps, serial)).containsExactly(6L, 7L, 8L).inOrder()
    }

    @Test fun oldestFirstWindowCountsAlreadyDownloadedEpisodes() {
        val eps = weekly().map { if (it.id <= 2) it.copy(download = dl()) else it }
        assertThat(select(eps, serial)).containsExactly(3L)
    }

    @Test fun oldestFirstWindowExtendsPastTombstones() {
        val eps = weekly().map { if (it.id == 2L) it.copy(autoDownloadBlocked = true) else it }
        assertThat(select(eps, serial)).containsExactly(1L, 3L, 4L).inOrder()
    }

    @Test fun inProgressCountsAsUnplayed() {
        val eps = weekly().map { if (it.id == 1L) it.copy(playState = PlayState.IN_PROGRESS) else it }
        assertThat(select(eps, serial)).containsExactly(1L, 2L, 3L).inOrder()
    }
}
