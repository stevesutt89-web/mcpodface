package com.steve.mcpodface.ui.settings

import com.google.common.truth.Truth.assertThat
import com.steve.mcpodface.data.prefs.GlobalSettings
import org.junit.Test

class SettingsOptionsTest {

    private val base = GlobalSettings()

    /** Every option, written then read back, must be the one that was picked. */
    private fun <T> assertRoundTrips(setting: ChoiceSetting<T>, from: GlobalSettings = base) {
        setting.options.forEach { (value, _) ->
            assertThat(setting.selected(setting.apply(from, value))).isEqualTo(value)
        }
    }

    @Test fun keepLatestRoundTrips() {
        assertRoundTrips(SettingsOptions.keepLatest)
        assertThat(SettingsOptions.keepLatest.options.map { it.first })
            .containsExactly(1, 2, 3, 5, 10, 15, 20).inOrder()
    }

    @Test fun keepLatestLabelsAndNearestForUnknownValues() {
        assertThat(SettingsOptions.keepLatest.label(base.copy(keepLatestCount = 5))).isEqualTo("5 episodes")
        assertThat(SettingsOptions.keepLatest.label(base.copy(keepLatestCount = 1))).isEqualTo("1 episode")
        assertThat(SettingsOptions.keepLatest.label(base.copy(keepLatestCount = 6))).isEqualTo("5 episodes")
        assertThat(SettingsOptions.keepLatest.label(base.copy(keepLatestCount = 99))).isEqualTo("20 episodes")
    }

    @Test fun resumeRewindOptionsRoundTripAndDefaultToOneMinutePause() {
        assertRoundTrips(SettingsOptions.resumeRewindSec)
        assertRoundTrips(SettingsOptions.longPause)
        assertThat(SettingsOptions.longPause.label(base)).isEqualTo("1 minute")
        assertThat(SettingsOptions.longPause.label(base.copy(longPauseMinutes = 60))).isEqualTo("1 hour")
        assertThat(SettingsOptions.resumeRewindSec.label(base.copy(resumeRewindSec = 3))).isEqualTo("5 seconds")
    }

    @Test fun appearanceChoicesRoundTrip() {
        assertRoundTrips(SettingsOptions.glassStyle)
        assertRoundTrips(SettingsOptions.accentColor)
        assertThat(SettingsOptions.glassStyle.label(base)).isEqualTo("Balanced")
        assertThat(SettingsOptions.accentColor.label(base)).isEqualTo("Violet")
    }

    @Test fun downloadOnRoundTripsAndLabels() {
        assertRoundTrips(SettingsOptions.downloadOn)
        assertThat(SettingsOptions.downloadOn.label(base.copy(allowCellularDownload = false))).isEqualTo("Wi-Fi only")
        assertThat(SettingsOptions.downloadOn.label(base.copy(allowCellularDownload = true)))
            .isEqualTo("Wi-Fi and mobile data")
    }

    @Test fun deletePlayedRoundTripsForEveryOption() {
        assertRoundTrips(SettingsOptions.deletePlayed)
        assertRoundTrips(SettingsOptions.deletePlayed, from = base.copy(deletePlayed = false))
    }

    @Test fun deletePlayedNeverSetsDeletePlayedFalse() {
        val result = SettingsOptions.deletePlayed.apply(base.copy(deletePlayed = true), PlayedDeletion.NEVER)
        assertThat(result.deletePlayed).isFalse()
        assertThat(SettingsOptions.deletePlayed.label(result)).isEqualTo("Never")
    }

    @Test fun deletePlayedDelayIsStoredInHours() {
        val immediately = SettingsOptions.deletePlayed.apply(base, PlayedDeletion.IMMEDIATELY)
        assertThat(immediately.deletePlayed).isTrue()
        assertThat(immediately.deletePlayedDelayHours).isEqualTo(0)

        val week = SettingsOptions.deletePlayed.apply(base.copy(deletePlayed = false), PlayedDeletion.AFTER_1_WEEK)
        assertThat(week.deletePlayed).isTrue()
        assertThat(week.deletePlayedDelayHours).isEqualTo(168)
        assertThat(SettingsOptions.deletePlayed.label(week)).isEqualTo("After 1 week")
    }

    @Test fun deletePlayedOffShowsNeverWhateverTheDelay() {
        val stored = base.copy(deletePlayed = false, deletePlayedDelayHours = 72)
        assertThat(SettingsOptions.deletePlayed.label(stored)).isEqualTo("Never")
    }

    @Test fun deletePlayedUnknownDelayPicksNearestOption() {
        assertThat(PlayedDeletion.of(base.copy(deletePlayed = true, deletePlayedDelayHours = 50)))
            .isEqualTo(PlayedDeletion.AFTER_3_DAYS)
        assertThat(PlayedDeletion.of(base.copy(deletePlayed = true, deletePlayedDelayHours = 999)))
            .isEqualTo(PlayedDeletion.AFTER_1_WEEK)
    }

    @Test fun maxAgeRoundTripsWithZeroMeaningNever() {
        assertRoundTrips(SettingsOptions.maxAgeDays)
        assertThat(SettingsOptions.maxAgeDays.label(base.copy(maxAgeDays = 0))).isEqualTo("Never")
        assertThat(SettingsOptions.maxAgeDays.label(base.copy(maxAgeDays = 30))).isEqualTo("1 month")
    }

    @Test fun maxAgeUnknownValuesPickNearestNonZeroOption() {
        assertThat(SettingsOptions.maxAgeDays.label(base.copy(maxAgeDays = 45))).isEqualTo("1 month")
        assertThat(SettingsOptions.maxAgeDays.label(base.copy(maxAgeDays = 3))).isEqualTo("1 week")
    }

    @Test fun refreshIntervalRoundTripsWithZeroManual() {
        assertRoundTrips(SettingsOptions.refreshInterval)
        assertThat(SettingsOptions.refreshInterval.label(base.copy(refreshIntervalHours = 0)))
            .isEqualTo("Manually only")
        assertThat(SettingsOptions.refreshInterval.label(base.copy(refreshIntervalHours = 1)))
            .isEqualTo("Every hour")
        assertThat(SettingsOptions.refreshInterval.label(base.copy(refreshIntervalHours = 5)))
            .isEqualTo("Every 4 hours")
    }

    @Test fun skipIntervalsRoundTripAndOnlyChangeTheirOwnField() {
        assertRoundTrips(SettingsOptions.skipBack)
        assertRoundTrips(SettingsOptions.skipForward)
        val changed = SettingsOptions.skipBack.apply(base, 30)
        assertThat(changed.skipBackSec).isEqualTo(30)
        assertThat(changed.skipForwardSec).isEqualTo(base.skipForwardSec)
        assertThat(SettingsOptions.skipBack.label(base.copy(skipBackSec = 12))).isEqualTo("10 seconds")
    }

    @Test fun defaultSpeedRoundTripsAndPicksNearestForUnknownSpeed() {
        assertRoundTrips(SettingsOptions.defaultSpeed)
        assertThat(SettingsOptions.defaultSpeed.label(base.copy(defaultSpeed = 1.5f))).isEqualTo("1.5×")
        assertThat(SettingsOptions.defaultSpeed.label(base.copy(defaultSpeed = 1.6f))).isEqualTo("1.5×")
    }

    @Test fun markPlayedRoundTripsAndPicksNearestForUnknownPercent() {
        assertRoundTrips(SettingsOptions.markPlayed)
        assertThat(SettingsOptions.markPlayed.label(base.copy(markPlayedPercent = 96))).isEqualTo("95%")
    }

    @Test fun apiDailyBudgetRoundTripsAndFallsBackToNearest() {
        assertRoundTrips(SettingsOptions.apiDailyBudget)
        assertThat(SettingsOptions.apiDailyBudget.label(base.copy(apiDailyBudget = 0))).isEqualTo("25 requests")
    }

    @Test fun nearestIntPrefersExactMatchAndSkipsOffSentinel() {
        assertThat(nearestInt(0, listOf(7, 0), off = 0)).isEqualTo(0)
        assertThat(nearestInt(-3, listOf(7, 14, 0), off = 0)).isEqualTo(7)
    }
}
