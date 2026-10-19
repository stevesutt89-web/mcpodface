package com.steve.mcpodface.ui.settings

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.steve.mcpodface.data.backup.OpmlExporter
import com.steve.mcpodface.data.backup.OpmlImporter
import com.steve.mcpodface.data.prefs.GlobalSettings
import com.steve.mcpodface.data.prefs.SettingsRepository
import com.steve.mcpodface.refresh.PolicyRunner
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsViewModelTest {
    private val repo = FakeSettings()
    private val policyRunner = mockk<PolicyRunner>(relaxed = true)
    private lateinit var vm: SettingsViewModel

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        vm = SettingsViewModel(
            context = ApplicationProvider.getApplicationContext(),
            settingsRepository = repo,
            policyRunner = policyRunner,
            importer = mockk(relaxed = true),
            exporter = mockk<OpmlExporter>(relaxed = true),
            io = Dispatchers.Unconfined,
        )
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun previewReturns(count: Int, bytes: Long = 0L) {
        coEvery { policyRunner.previewCleanup(any()) } returns (count to bytes)
    }

    @Test fun changeThatDeletesNothingAppliesAtOnce() {
        previewReturns(0)

        vm.update { it.copy(keepLatestCount = 3) }

        assertThat(repo.current.keepLatestCount).isEqualTo(3)
        assertThat(vm.pendingCleanup.value).isNull()
        coVerify(exactly = 0) { policyRunner.runCleanup() }
    }

    @Test fun loweringKeepLatestWithFilesToRemoveAsksFirst() {
        previewReturns(7, 412_000_000L)

        vm.update { it.copy(keepLatestCount = 3) }

        // Nothing applied and nothing deleted until the user confirms.
        assertThat(repo.current.keepLatestCount).isEqualTo(GlobalSettings().keepLatestCount)
        assertThat(vm.pendingCleanup.value?.count).isEqualTo(7)
        assertThat(vm.pendingCleanup.value?.bytes).isEqualTo(412_000_000L)
        coVerify(exactly = 0) { policyRunner.runCleanup() }
    }

    @Test fun confirmAppliesSettingThenRunsCleanup() {
        previewReturns(7, 412_000_000L)
        vm.update { it.copy(keepLatestCount = 3) }

        vm.confirmCleanup()

        assertThat(repo.current.keepLatestCount).isEqualTo(3)
        assertThat(vm.pendingCleanup.value).isNull()
        coVerifyOrder {
            policyRunner.previewCleanup(any())
            policyRunner.runCleanup()
        }
    }

    @Test fun cancelLeavesSettingUnchangedAndDeletesNothing() {
        previewReturns(7, 412_000_000L)
        vm.update { it.copy(keepLatestCount = 3) }

        vm.cancelCleanup()

        assertThat(repo.current.keepLatestCount).isEqualTo(GlobalSettings().keepLatestCount)
        assertThat(vm.pendingCleanup.value).isNull()
        coVerify(exactly = 0) { policyRunner.runCleanup() }
    }

    @Test fun turningOnDeletePlayedAsksFirst() {
        previewReturns(2, 50_000_000L)
        vm.update { it.copy(deletePlayed = false) }
        // Turning it off never deletes, so it applies without a preview.
        assertThat(repo.current.deletePlayed).isFalse()

        vm.update { it.copy(deletePlayed = true) }

        assertThat(repo.current.deletePlayed).isFalse()
        assertThat(vm.pendingCleanup.value?.count).isEqualTo(2)
    }

    @Test fun shorteningMaxAgeAsksFirst() {
        previewReturns(4, 100_000_000L)

        vm.update { it.copy(maxAgeDays = 7) }

        assertThat(repo.current.maxAgeDays).isEqualTo(GlobalSettings().maxAgeDays)
        assertThat(vm.pendingCleanup.value?.count).isEqualTo(4)
    }

    @Test fun unrelatedChangeNeverPreviews() {
        vm.update { it.copy(skipBackSec = 15) }

        assertThat(repo.current.skipBackSec).isEqualTo(15)
        assertThat(vm.pendingCleanup.value).isNull()
        coVerify(exactly = 0) { policyRunner.previewCleanup(any()) }
    }

    @Test fun pendingChangeIsReplacedByTheLatestDestructiveOne() {
        previewReturns(7, 412_000_000L)
        vm.update { it.copy(keepLatestCount = 3) }
        previewReturns(9, 500_000_000L)
        vm.update { it.copy(keepLatestCount = 1) }

        assertThat(vm.pendingCleanup.value?.count).isEqualTo(9)
        vm.confirmCleanup()
        assertThat(repo.current.keepLatestCount).isEqualTo(1)
    }
}

private class FakeSettings : SettingsRepository {
    private val state = MutableStateFlow(GlobalSettings())
    val current: GlobalSettings get() = state.value
    override val settings: Flow<GlobalSettings> = state
    override suspend fun update(transform: (GlobalSettings) -> GlobalSettings): GlobalSettings =
        transform(state.value).also { state.value = it }
}
