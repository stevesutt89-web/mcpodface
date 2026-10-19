package com.steve.mcpodface.ui.settings

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.steve.mcpodface.core.Clock
import com.steve.mcpodface.data.backup.ImportMode
import com.steve.mcpodface.data.backup.ImportResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.time.ZoneId
import java.time.ZoneOffset

@RunWith(AndroidJUnit4::class)
class BackupViewModelTest {
    private val file: Uri = Uri.parse("content://test/backup.zip")
    private val engine = FakeBackupEngine()
    private lateinit var vm: BackupViewModel

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        vm = BackupViewModel(engine, Clock { 1_800_000_000_000L }, UnconfinedTestDispatcher())
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    // --- export ---

    @Test fun exportOptionsStartWithArchiveOn() {
        vm.beginExport()

        assertThat(vm.state.value).isEqualTo(BackupState.ExportOptions(includeArchive = true))
    }

    @Test fun cancellingExportOptionsReturnsToIdleWithoutExporting() {
        vm.beginExport()

        vm.dismiss()

        assertThat(vm.state.value).isEqualTo(BackupState.Idle)
        assertThat(engine.exports).isEmpty()
    }

    @Test fun confirmingOptionsOpensPickerWithChosenArchiveSetting() {
        vm.beginExport()
        vm.setIncludeArchive(false)

        vm.confirmExportOptions()

        assertThat(vm.state.value).isEqualTo(BackupState.ChoosingExportLocation(includeArchive = false))
        assertThat(engine.exports).isEmpty()
    }

    @Test fun exportToPickedFileRunsThenReportsSaved() {
        vm.beginExport()
        vm.confirmExportOptions()

        vm.exportTo(file)

        assertThat(engine.exports).containsExactly(file to true)
        assertThat(vm.state.value).isEqualTo(
            BackupState.Done(BackupTask.EXPORT, "All data was written to the file you chose."),
        )
    }

    @Test fun exportReportsProgressWhileRunning() {
        var seen: BackupState? = null
        engine.onRun = { seen = vm.state.value }
        vm.beginExport()
        vm.confirmExportOptions()

        vm.exportTo(file)

        assertThat(seen).isEqualTo(BackupState.Running(BackupTask.EXPORT, progress = 0.5f))
    }

    @Test fun pickerCancelledReturnsToIdle() {
        vm.beginExport()
        vm.confirmExportOptions()

        vm.exportLocationCancelled()

        assertThat(vm.state.value).isEqualTo(BackupState.Idle)
        assertThat(engine.exports).isEmpty()
    }

    @Test fun exportFailureIsReported() {
        engine.exportError = IOException("disk full")
        vm.beginExport()
        vm.confirmExportOptions()

        vm.exportTo(file)

        assertThat(vm.state.value).isEqualTo(BackupState.Failed(BackupTask.EXPORT, "disk full"))
    }

    @Test fun exportFileNameIsDatedFromInjectedClockInTheSystemZone() {
        assertThat(vm.exportFileName())
            .isEqualTo(BackupFileNames.exportName(1_800_000_000_000L, ZoneId.systemDefault()))
    }

    // --- import ---

    @Test fun pickedBackupOpensMergeOptionsByDefault() {
        vm.beginImport(file)

        assertThat(vm.state.value).isEqualTo(BackupState.ImportOptions(file, ImportMode.MERGE))
    }

    @Test fun mergeRunsWithoutSecondConfirmation() {
        vm.beginImport(file)

        vm.startImport()

        assertThat(engine.imports).containsExactly(ImportMode.MERGE)
        assertThat(vm.state.value).isEqualTo(
            BackupState.Done(BackupTask.IMPORT, "Restored 12 podcasts, 340 episode states, 5 queue items"),
        )
    }

    @Test fun replaceNeedsASecondConfirmationBeforeAnythingRuns() {
        vm.beginImport(file)
        vm.setImportMode(ImportMode.REPLACE)

        vm.startImport()

        assertThat(vm.state.value).isEqualTo(BackupState.ConfirmReplace(file))
        assertThat(engine.imports).isEmpty()
    }

    @Test fun backingOutOfReplaceKeepsReplaceSelectedAndRunsNothing() {
        vm.beginImport(file)
        vm.setImportMode(ImportMode.REPLACE)
        vm.startImport()

        vm.backFromReplace()

        assertThat(vm.state.value).isEqualTo(BackupState.ImportOptions(file, ImportMode.REPLACE))
        assertThat(engine.imports).isEmpty()
    }

    @Test fun confirmedReplaceRunsWithReplaceMode() {
        vm.beginImport(file)
        vm.setImportMode(ImportMode.REPLACE)
        vm.startImport()

        vm.confirmReplace()

        assertThat(engine.imports).containsExactly(ImportMode.REPLACE)
        assertThat(vm.state.value).isInstanceOf(BackupState.Done::class.java)
    }

    @Test fun importFailureFromImporterIsReported() {
        engine.importResult = ImportResult.Failed("Not a backup file")
        vm.beginImport(file)

        vm.startImport()

        assertThat(vm.state.value).isEqualTo(BackupState.Failed(BackupTask.IMPORT, "Not a backup file"))
    }

    @Test fun importThrowingIsReportedAsFailure() {
        engine.importError = IOException("Could not open the file")
        vm.beginImport(file)

        vm.startImport()

        assertThat(vm.state.value).isEqualTo(BackupState.Failed(BackupTask.IMPORT, "Could not open the file"))
    }

    // --- guards and dismissal ---

    @Test fun cannotStartAnExportWhileAnotherFlowIsOpen() {
        vm.beginImport(file)

        vm.beginExport()

        assertThat(vm.state.value).isEqualTo(BackupState.ImportOptions(file, ImportMode.MERGE))
    }

    @Test fun runningTaskCannotBeDismissed() {
        var seen: BackupState? = null
        engine.onRun = {
            vm.dismiss()
            seen = vm.state.value
        }
        vm.beginImport(file)

        vm.startImport()

        assertThat(seen).isEqualTo(BackupState.Running(BackupTask.IMPORT, progress = 0.5f))
    }

    @Test fun dismissingResultReturnsToIdle() {
        vm.beginImport(file)
        vm.startImport()

        vm.dismiss()

        assertThat(vm.state.value).isEqualTo(BackupState.Idle)
    }

    @Test fun actionsOutOfOrderAreIgnored() {
        vm.confirmExportOptions()
        vm.exportTo(file)
        vm.startImport()
        vm.confirmReplace()

        assertThat(vm.state.value).isEqualTo(BackupState.Idle)
        assertThat(engine.exports).isEmpty()
        assertThat(engine.imports).isEmpty()
    }

    // --- text helpers ---

    @Test fun restoredSummaryOmitsZeroCountsAndPluralisesCorrectly() {
        val summary = BackupSummary.restored(
            ImportResult.Ok(podcasts = 1, episodeStates = 340, archiveEpisodes = 0, queue = 0, history = 0),
        )

        assertThat(summary).isEqualTo("Restored 1 podcast, 340 episode states, 0 queue items")
    }

    @Test fun restoredSummaryIncludesHistoryAndArchiveWhenPresent() {
        val summary = BackupSummary.restored(
            ImportResult.Ok(podcasts = 2, episodeStates = 3, archiveEpisodes = 900, queue = 1, history = 4),
        )

        assertThat(summary).isEqualTo(
            "Restored 2 podcasts, 3 episode states, 1 queue item, 4 history entries, 900 archive episodes",
        )
    }

    @Test fun exportNameIsDatedInTheGivenZone() {
        // 2027-01-15T23:30Z is the 16th in UTC+2 but still the 15th in UTC.
        val lateEvening = 1_800_055_800_000L
        assertThat(BackupFileNames.exportName(lateEvening, ZoneOffset.UTC)).isEqualTo("mcpodface-backup-2027-01-15.zip")
        assertThat(BackupFileNames.exportName(lateEvening, ZoneOffset.ofHours(2))).isEqualTo("mcpodface-backup-2027-01-16.zip")
    }

    private class FakeBackupEngine : BackupEngine {
        val exports = mutableListOf<Pair<Uri, Boolean>>()
        val imports = mutableListOf<ImportMode>()
        var exportError: Exception? = null
        var importError: Exception? = null
        var importResult: ImportResult = ImportResult.Ok(podcasts = 12, episodeStates = 340, archiveEpisodes = 0, queue = 5, history = 0)

        /** Runs after progress is reported (0.5) and before the engine returns. */
        var onRun: () -> Unit = {}

        override suspend fun export(uri: Uri, includeArchive: Boolean, onProgress: (Float) -> Unit) {
            exports += uri to includeArchive
            onProgress(0.5f)
            onRun()
            exportError?.let { throw it }
        }

        override suspend fun import(uri: Uri, mode: ImportMode, onProgress: (Float) -> Unit): ImportResult {
            imports += mode
            onProgress(0.5f)
            onRun()
            importError?.let { throw it }
            return importResult
        }
    }
}
