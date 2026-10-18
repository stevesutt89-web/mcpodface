package com.steve.mcpodface.data.prefs

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DataStoreSettingsRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun TestScope.repo() = DataStoreSettingsRepository(
        PreferenceDataStoreFactory.create(scope = backgroundScope) { tmp.newFile("test.preferences_pb") },
    )

    @Test fun emptyStoreEmitsDefaults() = runTest(UnconfinedTestDispatcher()) {
        assertThat(repo().settings.first()).isEqualTo(GlobalSettings())
    }

    @Test fun updateRoundTripsEveryField() = runTest(UnconfinedTestDispatcher()) {
        val repo = repo()
        val changed = GlobalSettings(
            theme = ThemeMode.DARK, pureBlack = false, tintPlayer = true, dynamicColor = true,
            lastTab = AppTab.SEARCH, searchMode = SearchMode.DISCOVER,
            librarySort = LibrarySort.ALPHABETICAL, libraryLayout = LibraryLayout.LIST,
            latestFilter = LatestFilter.DOWNLOADED, autoDownload = false, keepLatestCount = 9,
            allowCellularDownload = true, downloadOnlyWhileCharging = true, streamOnCellular = false,
            deletePlayed = false, deletePlayedDelayHours = 72, maxAgeDays = 0,
            countManualTowardLimit = true, markPlayedPercent = 98, skipBackSec = 15,
            skipForwardSec = 45, defaultSpeed = 1.3f, autoplayNext = true, resumeRewind = false,
            trimSilence = true, voiceBoost = true, refreshIntervalHours = 12,
            notifyNewEpisodes = false, backfillEnabled = false, backfillWifiOnly = false,
            backfillChargingOnly = true, apiDailyBudget = 250, apiRequestsToday = 17,
            apiBudgetDay = 20_000, apiPausedUntil = 123_456_789,
        )
        assertThat(repo.update { changed }).isEqualTo(changed)
        assertThat(repo.settings.first()).isEqualTo(changed)
    }
}
