package com.steve.mcpodface.ui.settings

import com.google.common.truth.Truth.assertThat
import com.steve.mcpodface.data.prefs.GlobalSettings
import org.junit.Test

class CleanupChangeTest {

    private val base = GlobalSettings()

    @Test fun loweringKeepLatestReducesRetention() {
        assertThat(CleanupChange.reducesRetention(base.copy(keepLatestCount = 5), base.copy(keepLatestCount = 3))).isTrue()
    }

    @Test fun raisingKeepLatestDoesNot() {
        assertThat(CleanupChange.reducesRetention(base.copy(keepLatestCount = 5), base.copy(keepLatestCount = 10))).isFalse()
    }

    @Test fun shorteningMaxAgeReducesRetention() {
        assertThat(CleanupChange.reducesRetention(base.copy(maxAgeDays = 30), base.copy(maxAgeDays = 14))).isTrue()
        assertThat(CleanupChange.reducesRetention(base.copy(maxAgeDays = 30), base.copy(maxAgeDays = 7))).isTrue()
    }

    @Test fun neverToALimitReducesRetentionButLimitToNeverDoesNot() {
        assertThat(CleanupChange.reducesRetention(base.copy(maxAgeDays = 0), base.copy(maxAgeDays = 90))).isTrue()
        assertThat(CleanupChange.reducesRetention(base.copy(maxAgeDays = 30), base.copy(maxAgeDays = 0))).isFalse()
    }

    @Test fun lengtheningMaxAgeDoesNot() {
        assertThat(CleanupChange.reducesRetention(base.copy(maxAgeDays = 30), base.copy(maxAgeDays = 90))).isFalse()
    }

    @Test fun turningOnDeletePlayedReducesRetention() {
        val off = base.copy(deletePlayed = false)
        assertThat(CleanupChange.reducesRetention(off, off.copy(deletePlayed = true))).isTrue()
    }

    @Test fun turningOffDeletePlayedDoesNot() {
        assertThat(CleanupChange.reducesRetention(base.copy(deletePlayed = true), base.copy(deletePlayed = false))).isFalse()
    }

    @Test fun shorteningPlayedDelayReducesRetentionOnlyWhileOn() {
        assertThat(CleanupChange.reducesRetention(base.copy(deletePlayedDelayHours = 168), base.copy(deletePlayedDelayHours = 24))).isTrue()
        assertThat(CleanupChange.reducesRetention(base.copy(deletePlayed = false, deletePlayedDelayHours = 168), base.copy(deletePlayed = false, deletePlayedDelayHours = 24))).isFalse()
    }

    @Test fun unrelatedChangesDoNotReduceRetention() {
        assertThat(CleanupChange.reducesRetention(base, base.copy(skipBackSec = 15, pureBlack = false))).isFalse()
    }

    @Test fun formatsSizesInDecimalUnits() {
        assertThat(CleanupChange.formatSize(412_000_000L)).isEqualTo("412 MB")
        assertThat(CleanupChange.formatSize(412_600_000L)).isEqualTo("413 MB")
        assertThat(CleanupChange.formatSize(980_000L)).isEqualTo("980 KB")
        assertThat(CleanupChange.formatSize(1_200_000_000L)).isEqualTo("1.2 GB")
        assertThat(CleanupChange.formatSize(0L)).isEqualTo("0 KB")
    }

    @Test fun messageNamesCountAndSize() {
        assertThat(CleanupChange.message(7, 412_000_000L)).isEqualTo("This will remove 7 episodes (412 MB).")
        assertThat(CleanupChange.message(1, 50_000_000L)).isEqualTo("This will remove 1 episode (50 MB).")
    }
}
