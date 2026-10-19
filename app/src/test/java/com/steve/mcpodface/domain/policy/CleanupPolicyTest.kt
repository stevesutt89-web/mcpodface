package com.steve.mcpodface.domain.policy

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.steve.mcpodface.data.db.DownloadSource
import com.steve.mcpodface.data.db.DownloadState
import com.steve.mcpodface.data.db.PlayState
import com.steve.mcpodface.data.db.SortOrder
import org.junit.Test

class CleanupPolicyTest {

    private fun select(
        episodes: List<PolicyEpisode>,
        s: EffectiveSettings = settings(),
        now: Long = NOW,
        playing: Long? = null,
    ) = CleanupPolicy.select(episodes, s, now, playing)

    private fun ids(episodes: List<PolicyEpisode>, s: EffectiveSettings = settings(), playing: Long? = null) =
        select(episodes, s, playing = playing).map { it.episodeId }

    private fun played(id: Long, hoursAgo: Double, source: DownloadSource = DownloadSource.AUTO) =
        ep(id, days = 2.0, playState = PlayState.PLAYED, playedAt = hoursAgo(hoursAgo), download = dl(source))

    // --- R1: played ---

    @Test fun playedMoreThan24hAgoIsDeleted() {
        assertThat(select(listOf(played(1, 25.0)))).containsExactly(Deletion(1, DeleteReason.PLAYED))
    }

    @Test fun playedLessThan24hAgoIsKept() {
        assertThat(ids(listOf(played(1, 23.0)))).isEmpty()
    }

    @Test fun playedExactlyAtTheDelayIsDeleted() {
        assertThat(ids(listOf(played(1, 24.0)))).containsExactly(1L)
    }

    @Test fun delayZeroDeletesImmediately() {
        assertThat(ids(listOf(played(1, 0.0)), settings(delayHours = 0))).containsExactly(1L)
    }

    @Test fun deletePlayedOffKeepsPlayedEpisodes() {
        assertThat(ids(listOf(played(1, 100.0)), settings(deletePlayed = false))).isEmpty()
    }

    @Test fun r1AppliesToManualDownloads() {
        assertThat(ids(listOf(played(1, 25.0, DownloadSource.MANUAL)))).containsExactly(1L)
    }

    @Test fun playedWithoutPlayedAtIsKept() {
        val e = ep(1, days = 2.0, playState = PlayState.PLAYED, playedAt = null, download = dl())
        assertThat(ids(listOf(e))).isEmpty()
    }

    @Test fun inProgressIsNotDeletedByR1() {
        val e = ep(1, days = 2.0, playState = PlayState.IN_PROGRESS, download = dl())
        assertThat(ids(listOf(e))).isEmpty()
    }

    // --- Exemptions ---

    @Test fun starredIsNeverDeleted() {
        val e = played(1, 500.0).copy(isStarred = true, pubDate = daysAgo(400.0))
        assertThat(ids(listOf(e))).isEmpty()
    }

    @Test fun currentlyPlayingIsNeverDeleted() {
        assertThat(ids(listOf(played(1, 500.0)), playing = 1L)).isEmpty()
    }

    @Test fun unfinishedDownloadsAreNeverDeleted() {
        for (state in DownloadState.entries - DownloadState.COMPLETED) {
            val e = played(1, 500.0).copy(download = dl(state = state))
            assertWithMessage("state=$state").that(ids(listOf(e))).isEmpty()
        }
    }

    @Test fun episodesWithoutDownloadsAreIgnored() {
        assertThat(ids(listOf(ep(1, days = 400.0, playState = PlayState.PLAYED)))).isEmpty()
    }

    // --- R2: age ---

    @Test fun autoNewestFirstAgeRunsFromPubDate() {
        val e = ep(1, days = 31.0, download = dl(downloadedAt = daysAgo(1.0)))
        assertThat(select(listOf(e))).containsExactly(Deletion(1, DeleteReason.TOO_OLD))
    }

    @Test fun autoNewestFirstWithinAgeIsKept() {
        assertThat(ids(listOf(ep(1, days = 29.0, download = dl())))).isEmpty()
    }

    @Test fun manualDownloadAgeRunsFromDownloadedAt() {
        val fresh = ep(1, days = 400.0, download = dl(DownloadSource.MANUAL, downloadedAt = daysAgo(2.0)))
        val stale = ep(2, days = 400.0, download = dl(DownloadSource.MANUAL, downloadedAt = daysAgo(31.0)))
        assertThat(ids(listOf(fresh, stale))).containsExactly(2L)
    }

    @Test fun oldestFirstAutoAgeRunsFromDownloadedAt() {
        val s = settings(sort = SortOrder.OLDEST_FIRST)
        val fresh = ep(1, days = 400.0, download = dl(downloadedAt = daysAgo(2.0)))
        val stale = ep(2, days = 390.0, download = dl(downloadedAt = daysAgo(31.0)))
        assertThat(ids(listOf(fresh, stale), s)).containsExactly(2L)
    }

    @Test fun maxAgeZeroNeverDeletesForAge() {
        assertThat(ids(listOf(ep(1, days = 999.0, download = dl())), settings(maxAgeDays = 0))).isEmpty()
    }

    @Test fun r2AppliesEvenWhenInUpNext() {
        val e = ep(1, days = 31.0, inQueue = true, download = dl())
        assertThat(ids(listOf(e))).containsExactly(1L)
    }

    @Test fun r1AppliesEvenWhenInUpNext() {
        assertThat(ids(listOf(played(1, 25.0).copy(inQueue = true)))).containsExactly(1L)
    }

    // --- R3: over the limit ---

    private fun allDownloaded(count: Int = 10, source: DownloadSource = DownloadSource.AUTO) =
        weekly(count).map { it.copy(pubDate = NOW - (count - it.id) * DAY_MS, download = dl(source)) }

    @Test fun overLimitDeletesOutsideWindowOldestFirst() {
        val d = select(allDownloaded(7))
        assertThat(d).containsExactly(
            Deletion(1, DeleteReason.OVER_LIMIT),
            Deletion(2, DeleteReason.OVER_LIMIT),
        ).inOrder()
    }

    @Test fun atTheLimitNothingIsDeleted() {
        assertThat(ids(allDownloaded(5))).isEmpty()
    }

    @Test fun manualDownloadsDoNotCountTowardLimitByDefault() {
        val eps = allDownloaded(5) + ep(50, days = 3.5, download = dl(DownloadSource.MANUAL))
        assertThat(ids(eps)).isEmpty()
    }

    @Test fun manualDownloadsCountWhenEnabled() {
        val eps = allDownloaded(5) + ep(50, days = 4.5, download = dl(DownloadSource.MANUAL))
        assertThat(ids(eps, settings(countManual = true))).containsExactly(50L)
    }

    @Test fun upNextIsExemptFromR3() {
        val eps = allDownloaded(7).map { if (it.id == 1L) it.copy(inQueue = true) else it }
        assertThat(ids(eps)).containsExactly(2L)
    }

    @Test fun r3CountsAfterR1Deletions() {
        // 6 downloads, one of them played 2 days ago: R1 removes it, leaving 5 = N, so R3 does nothing.
        val eps = allDownloaded(6).map {
            if (it.id == 6L) it.copy(playState = PlayState.PLAYED, playedAt = hoursAgo(48.0)) else it
        }
        assertThat(select(eps)).containsExactly(Deletion(6, DeleteReason.PLAYED))
    }

    @Test fun oldestFirstOverLimitDeletesFurthestFromFrontFirst() {
        // Resume after ep 2; window = 3,4,5. Downloaded: 1 (behind resume), 3,4,5, 9, 10.
        val s = settings(sort = SortOrder.OLDEST_FIRST, keep = 3, maxAgeDays = 0)
        val eps = weekly().map {
            when (it.id) {
                2L -> it.copy(playState = PlayState.PLAYED, playedAt = hoursAgo(1.0))
                1L, 3L, 4L, 5L, 9L, 10L -> it.copy(download = dl())
                else -> it
            }
        }
        assertThat(ids(eps, s)).containsExactly(10L, 9L, 1L).inOrder()
    }

    @Test fun loweringNPreviewsTheEpisodesToRemove() {
        assertThat(ids(allDownloaded(5), settings(keep = 2))).containsExactly(1L, 2L, 3L).inOrder()
    }

    // --- §6.4 tombstone loop ---

    @Test fun deletedEpisodeIsNotDownloadedAgainOnceTombstoned() {
        // Serial show: an unplayed back-catalogue download ages out (R2 from downloadedAt) while
        // still in the window. Without the tombstone the next pass would download it again.
        val s = settings(sort = SortOrder.OLDEST_FIRST, keep = 1)
        var episode = ep(1, days = 400.0, download = dl(downloadedAt = daysAgo(31.0)))

        assertThat(ids(listOf(episode), s)).containsExactly(1L)
        episode = episode.copy(download = null) // file deleted
        assertThat(DownloadPolicy.select(listOf(episode), s, NOW)).containsExactly(1L) // the loop

        episode = episode.copy(autoDownloadBlocked = true) // what the caller must do
        assertThat(DownloadPolicy.select(listOf(episode), s, NOW)).isEmpty()
    }

    @Test fun userDeletedEpisodeInWindowStaysDeleted() {
        val eps = weekly().map { if (it.id == 10L) it.copy(autoDownloadBlocked = true) else it }
        assertThat(DownloadPolicy.select(eps, settings(), NOW)).doesNotContain(10L)
    }

    // --- §6.0 + §6.3 end to end ---

    @Test fun episodePlayedTo96PercentIsDeleted24hLater() {
        val durationMs = 60 * 60_000L
        assertThat(PlayedRule.isPlayed((durationMs * 0.96).toLong(), durationMs, 95)).isTrue()

        val playedAt = NOW
        val e = ep(1, days = 1.0, playState = PlayState.PLAYED, playedAt = playedAt, download = dl())
        assertThat(select(listOf(e), now = playedAt + 23 * HOUR_MS)).isEmpty()
        assertThat(select(listOf(e), now = playedAt + 24 * HOUR_MS))
            .containsExactly(Deletion(1, DeleteReason.PLAYED))
    }
}
