package com.steve.mcpodface.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.steve.mcpodface.core.Clock
import com.steve.mcpodface.core.di.IoDispatcher
import com.steve.mcpodface.data.backup.ImportMode
import com.steve.mcpodface.data.backup.ImportResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import javax.inject.Inject

enum class BackupTask { EXPORT, IMPORT }

/**
 * The "Export all data" / "Import all data" flow. Idle → options → (SAF location for export) →
 * Running → Done / Failed. Replace imports add a second confirmation before they run.
 */
sealed interface BackupState {
    data object Idle : BackupState

    /** Export dialog: the "Include episode archive" choice. */
    data class ExportOptions(val includeArchive: Boolean = true) : BackupState

    /** The "create document" picker is open. No dialog is shown. */
    data class ChoosingExportLocation(val includeArchive: Boolean) : BackupState

    /** Import dialog: Merge or Replace for the file at [uri]. */
    data class ImportOptions(val uri: Uri, val mode: ImportMode = ImportMode.MERGE) : BackupState

    /** Second, destructive confirmation for a Replace import. */
    data class ConfirmReplace(val uri: Uri) : BackupState

    data class Running(val task: BackupTask, val progress: Float = 0f) : BackupState

    data class Done(val task: BackupTask, val message: String) : BackupState

    data class Failed(val task: BackupTask, val message: String) : BackupState
}

/**
 * Drives the backup export and import. The work runs in [viewModelScope] (not the composition), so
 * a rotation doesn't cancel it, and the dialogs just render [state].
 */
@HiltViewModel
class BackupViewModel @Inject constructor(
    private val engine: BackupEngine,
    private val clock: Clock,
    @IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {

    private val _state = MutableStateFlow<BackupState>(BackupState.Idle)
    val state: StateFlow<BackupState> = _state.asStateFlow()

    /** The suggested name for the "create document" picker, e.g. `mcpodface-backup-2026-10-19.zip`. */
    fun exportFileName(): String = BackupFileNames.exportName(clock.now(), ZoneId.systemDefault())

    fun beginExport() {
        if (_state.value == BackupState.Idle) _state.value = BackupState.ExportOptions()
    }

    fun setIncludeArchive(include: Boolean) {
        val current = _state.value as? BackupState.ExportOptions ?: return
        _state.value = current.copy(includeArchive = include)
    }

    /** "Export" in the options dialog: the picker opens next, so the screen launches it. */
    fun confirmExportOptions() {
        val current = _state.value as? BackupState.ExportOptions ?: return
        _state.value = BackupState.ChoosingExportLocation(current.includeArchive)
    }

    /** The user backed out of the file picker. */
    fun exportLocationCancelled() {
        if (_state.value is BackupState.ChoosingExportLocation) _state.value = BackupState.Idle
    }

    fun exportTo(uri: Uri) {
        val choice = _state.value as? BackupState.ChoosingExportLocation ?: return
        launchTask(BackupTask.EXPORT) {
            engine.export(uri, choice.includeArchive) { report(it) }
            BackupState.Done(BackupTask.EXPORT, "All data was written to the file you chose.")
        }
    }

    /** A file was picked for "Import all data". */
    fun beginImport(uri: Uri) {
        if (_state.value == BackupState.Idle) _state.value = BackupState.ImportOptions(uri)
    }

    fun setImportMode(mode: ImportMode) {
        val current = _state.value as? BackupState.ImportOptions ?: return
        _state.value = current.copy(mode = mode)
    }

    /** "Import" in the options dialog. Replace asks again before anything is deleted. */
    fun startImport() {
        val current = _state.value as? BackupState.ImportOptions ?: return
        if (current.mode == ImportMode.REPLACE) {
            _state.value = BackupState.ConfirmReplace(current.uri)
        } else {
            runImport(current.uri, ImportMode.MERGE)
        }
    }

    /** The user confirmed the destructive Replace. */
    fun confirmReplace() {
        val current = _state.value as? BackupState.ConfirmReplace ?: return
        runImport(current.uri, ImportMode.REPLACE)
    }

    /** The user backed out of the Replace confirmation; they return to the options. */
    fun backFromReplace() {
        val current = _state.value as? BackupState.ConfirmReplace ?: return
        _state.value = BackupState.ImportOptions(current.uri, ImportMode.REPLACE)
    }

    /** Closes an options dialog, a result or a failure. A running task can't be dismissed. */
    fun dismiss() {
        when (_state.value) {
            is BackupState.Running, BackupState.Idle -> Unit
            else -> _state.value = BackupState.Idle
        }
    }

    private fun runImport(uri: Uri, mode: ImportMode) {
        launchTask(BackupTask.IMPORT) {
            when (val result = engine.import(uri, mode) { report(it) }) {
                is ImportResult.Ok -> BackupState.Done(BackupTask.IMPORT, BackupSummary.restored(result))
                is ImportResult.Failed -> BackupState.Failed(BackupTask.IMPORT, result.message)
            }
        }
    }

    private fun report(progress: Float) {
        _state.update { if (it is BackupState.Running) it.copy(progress = progress) else it }
    }

    /** Puts the task into Running, then runs [block] on IO and records its outcome. */
    private fun launchTask(task: BackupTask, block: suspend () -> BackupState) {
        _state.value = BackupState.Running(task)
        viewModelScope.launch(io) {
            _state.value = try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                BackupState.Failed(task, e.message?.takeIf { it.isNotBlank() } ?: "Something went wrong")
            }
        }
    }
}

/** Pure text helpers for the backup flow, kept apart from Android so they can be unit-tested. */
object BackupSummary {
    /** "Restored 12 podcasts, 340 episode states, 5 queue items". Zero-count parts are left out. */
    fun restored(ok: ImportResult.Ok): String {
        val parts = buildList {
            add(plural(ok.podcasts, "podcast"))
            add(plural(ok.episodeStates, "episode state"))
            add(plural(ok.queue, "queue item"))
            if (ok.history > 0) add(plural(ok.history, "history entry", "history entries"))
            if (ok.archiveEpisodes > 0) add(plural(ok.archiveEpisodes, "archive episode"))
        }
        return "Restored " + parts.joinToString(", ")
    }

    private fun plural(count: Int, singular: String, pluralForm: String = "${singular}s"): String =
        "$count ${if (count == 1) singular else pluralForm}"
}

object BackupFileNames {
    private const val PREFIX = "mcpodface-backup-"
    const val MIME_TYPE = "application/zip"

    /** `mcpodface-backup-YYYY-MM-DD.zip`, dated in [zone] (the phone's zone in the app). */
    fun exportName(epochMillis: Long, zone: ZoneId = ZoneOffset.UTC): String {
        val date = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()
        return "$PREFIX$date.zip"
    }
}
