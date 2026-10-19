package com.steve.mcpodface.ui.downloads

import com.google.common.truth.Truth.assertThat
import com.steve.mcpodface.data.db.DownloadEntity
import com.steve.mcpodface.data.db.DownloadSource
import com.steve.mcpodface.data.db.DownloadState
import com.steve.mcpodface.data.db.PlayState
import com.steve.mcpodface.data.db.SortOrder
import com.steve.mcpodface.domain.policy.EffectiveSettings
import org.junit.Test

private const val HOUR = 3_600_000L
private const val DAY = 24 * HOUR
private const val NOW = 1_792_411_200_000L // 2026-10-19T12:00Z

private fun settings(
    deletePlayed: Boolean = true,
    delayHours: Int = 24,
    maxAgeDays: Int = 30,
    sort: SortOrder = SortOrder.NEWEST_FIRST,
) = EffectiveSettings(
    autoDownload = true,
    keepLatestCount = 5,
    deletePlayed = deletePlayed,
    deletePlayedDelayHours = delayHours,
    maxAgeDays = maxAgeDays,
    allowCellular = false,
    sortOrder = sort,
    countManualTowardLimit = false,
)

private fun download(
    source: DownloadSource = DownloadSource.AUTO,
    downloadedAt: Long? = NOW - DAY,
) = DownloadEntity(episodeId = 1L, state = DownloadState.COMPLETED, source = source, downloadedAt = downloadedAt)

class DeletionEtaTest {

    @Test fun playedEpisodeIsDueDelayHoursAfterPlaying() {
        val playedAt = NOW - 2 * HOUR
        val eta = deletionEta(
            download(), PlayState.PLAYED, playedAt, pubDate = NOW - DAY, isStarred = false,
            settings = settings(delayHours = 24), now = NOW,
        )

        assertThat(eta).isEqualTo(DeletionEta.Scheduled(playedAt + 24 * HOUR, DeletionEta.Reason.PLAYED))
    }

    @Test fun zeroDelayIsDueImmediatelyAfterPlaying() {
        val eta = deletionEta(
            download(), PlayState.PLAYED, playedAt = NOW, pubDate = NOW, isStarred = false,
            settings = settings(delayHours = 0, maxAgeDays = 0), now = NOW,
        )

        assertThat(eta).isEqualTo(DeletionEta.Scheduled(NOW, DeletionEta.Reason.PLAYED))
    }

    @Test fun deletePlayedOffSchedulesNothingForPlayedEpisode() {
        val eta = deletionEta(
            download(), PlayState.PLAYED, playedAt = NOW - HOUR, pubDate = NOW - DAY, isStarred = false,
            settings = settings(deletePlayed = false, maxAgeDays = 0), now = NOW,
        )

        assertThat(eta).isNull()
    }

    @Test fun unplayedEpisodeIgnoresPlayedRule() {
        val eta = deletionEta(
            download(), PlayState.UNPLAYED, playedAt = null, pubDate = NOW - DAY, isStarred = false,
            settings = settings(maxAgeDays = 0), now = NOW,
        )

        assertThat(eta).isNull()
    }

    @Test fun autoNewestFirstMeasuresMaxAgeFromPubDate() {
        val pubDate = NOW - 10 * DAY
        val eta = deletionEta(
            download(DownloadSource.AUTO, downloadedAt = NOW - DAY), PlayState.UNPLAYED, null,
            pubDate = pubDate, isStarred = false, settings = settings(maxAgeDays = 30), now = NOW,
        )

        assertThat(eta).isEqualTo(DeletionEta.Scheduled(pubDate + 30 * DAY, DeletionEta.Reason.TOO_OLD, 30))
    }

    @Test fun manualDownloadMeasuresMaxAgeFromDownloadedAt() {
        val downloadedAt = NOW - 2 * DAY
        val eta = deletionEta(
            download(DownloadSource.MANUAL, downloadedAt), PlayState.UNPLAYED, null,
            pubDate = NOW - 400 * DAY, isStarred = false, settings = settings(maxAgeDays = 30), now = NOW,
        )

        assertThat(eta).isEqualTo(DeletionEta.Scheduled(downloadedAt + 30 * DAY, DeletionEta.Reason.TOO_OLD, 30))
    }

    @Test fun oldestFirstAutoMeasuresMaxAgeFromDownloadedAt() {
        val downloadedAt = NOW - 2 * DAY
        val eta = deletionEta(
            download(DownloadSource.AUTO, downloadedAt), PlayState.UNPLAYED, null,
            pubDate = NOW - 400 * DAY, isStarred = false,
            settings = settings(maxAgeDays = 30, sort = SortOrder.OLDEST_FIRST), now = NOW,
        )

        assertThat(eta).isEqualTo(DeletionEta.Scheduled(downloadedAt + 30 * DAY, DeletionEta.Reason.TOO_OLD, 30))
    }

    @Test fun manualWithoutDownloadedAtHasNoMaxAgeDate() {
        val eta = deletionEta(
            download(DownloadSource.MANUAL, downloadedAt = null), PlayState.UNPLAYED, null,
            pubDate = NOW, isStarred = false, settings = settings(maxAgeDays = 30), now = NOW,
        )

        assertThat(eta).isNull()
    }

    @Test fun maxAgeZeroNeverSchedulesByAge() {
        val eta = deletionEta(
            download(), PlayState.UNPLAYED, null, pubDate = NOW - 900 * DAY, isStarred = false,
            settings = settings(maxAgeDays = 0), now = NOW,
        )

        assertThat(eta).isNull()
    }

    @Test fun starredEpisodeIsKeptEvenWhenRulesWouldFire() {
        val eta = deletionEta(
            download(), PlayState.PLAYED, playedAt = NOW - 5 * DAY, pubDate = NOW - 900 * DAY, isStarred = true,
            settings = settings(), now = NOW,
        )

        assertThat(eta).isEqualTo(DeletionEta.Kept(DeletionEta.KeptReason.STARRED))
    }

    @Test fun earliestRuleWinsWhenPlayedIsSooner() {
        val playedAt = NOW - 2 * DAY // due at NOW - 1 day
        val eta = deletionEta(
            download(), PlayState.PLAYED, playedAt, pubDate = NOW - 29 * DAY, isStarred = false,
            settings = settings(delayHours = 24, maxAgeDays = 30), now = NOW,
        )

        assertThat(eta).isEqualTo(DeletionEta.Scheduled(NOW - DAY, DeletionEta.Reason.PLAYED))
    }

    @Test fun earliestRuleWinsWhenMaxAgeIsSooner() {
        val pubDate = NOW - 29 * DAY // too old at NOW + 1 day
        val eta = deletionEta(
            download(), PlayState.PLAYED, playedAt = NOW, pubDate = pubDate, isStarred = false,
            settings = settings(delayHours = 168, maxAgeDays = 30), now = NOW,
        )

        assertThat(eta).isEqualTo(DeletionEta.Scheduled(pubDate + 30 * DAY, DeletionEta.Reason.TOO_OLD, 30))
    }

    @Test fun tieGoesToPlayedRule() {
        val at = NOW + DAY
        val eta = deletionEta(
            download(), PlayState.PLAYED, playedAt = at - 24 * HOUR, pubDate = at - 30 * DAY, isStarred = false,
            settings = settings(delayHours = 24, maxAgeDays = 30), now = NOW,
        )

        assertThat(eta).isEqualTo(DeletionEta.Scheduled(at, DeletionEta.Reason.PLAYED))
    }

    // ---- Labels ----

    @Test fun labelForPlayedUnderAnHourSaysDeletesSoon() {
        val label = formatDeletionLabel(DeletionEta.Scheduled(NOW + 30 * 60_000L, DeletionEta.Reason.PLAYED), NOW)

        assertThat(label).isEqualTo("Deletes soon · played")
    }

    @Test fun labelForOverdueRuleSaysDeletesSoon() {
        val label = formatDeletionLabel(DeletionEta.Scheduled(NOW - HOUR, DeletionEta.Reason.PLAYED), NOW)

        assertThat(label).isEqualTo("Deletes soon · played")
    }

    @Test fun labelInHoursUsesWholeHours() {
        val label = formatDeletionLabel(DeletionEta.Scheduled(NOW + 18 * HOUR + 40 * 60_000L, DeletionEta.Reason.PLAYED), NOW)

        assertThat(label).isEqualTo("Deletes in 18h · played")
    }

    @Test fun exactlyOneHourIsHours() {
        val label = formatDeletionLabel(DeletionEta.Scheduled(NOW + HOUR, DeletionEta.Reason.PLAYED), NOW)

        assertThat(label).isEqualTo("Deletes in 1h · played")
    }

    @Test fun labelInDaysForMaxAge() {
        val label = formatDeletionLabel(DeletionEta.Scheduled(NOW + 3 * DAY + HOUR, DeletionEta.Reason.TOO_OLD, 30), NOW)

        assertThat(label).isEqualTo("Deletes in 3 days · older than 30 days")
    }

    @Test fun singularDayAndDayWording() {
        val label = formatDeletionLabel(DeletionEta.Scheduled(NOW + DAY, DeletionEta.Reason.TOO_OLD, 1), NOW)

        assertThat(label).isEqualTo("Deletes in 1 day · older than 1 day")
    }

    @Test fun keptLabelForStarred() {
        assertThat(formatDeletionLabel(DeletionEta.Kept(), NOW)).isEqualTo("Kept · starred")
    }

    @Test fun noLabelWhenNoRuleWillDelete() {
        assertThat(formatDeletionLabel(null, NOW)).isNull()
    }
}
