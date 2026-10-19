package com.steve.mcpodface.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.kyant.shapes.RoundedRectangle
import com.steve.mcpodface.data.backup.ImportMode
import com.steve.mcpodface.ui.addpodcast.GlassDialogScope
import com.steve.mcpodface.ui.components.glass.GlassButton
import com.steve.mcpodface.ui.components.glass.GlassStyle
import com.steve.mcpodface.ui.theme.AppTheme
import com.steve.mcpodface.ui.theme.Spacing
import com.steve.mcpodface.ui.theme.ThemePreviews

/**
 * Renders whichever backup dialog the [state] calls for. [Idle], [BackupState.ChoosingExportLocation]
 * show nothing: the system file picker is up, or nothing is happening.
 */
@Composable
fun BackupDialogs(
    state: BackupState,
    onIncludeArchive: (Boolean) -> Unit,
    onExportOptionsConfirm: () -> Unit,
    onImportModeChange: (ImportMode) -> Unit,
    onImportConfirm: () -> Unit,
    onReplaceConfirm: () -> Unit,
    onReplaceBack: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (state) {
        BackupState.Idle, is BackupState.ChoosingExportLocation -> Unit
        is BackupState.ExportOptions -> ExportOptionsDialog(
            includeArchive = state.includeArchive,
            onIncludeArchiveChange = onIncludeArchive,
            onExport = onExportOptionsConfirm,
            onCancel = onDismiss,
        )
        is BackupState.ImportOptions -> ImportOptionsDialog(
            mode = state.mode,
            onModeChange = onImportModeChange,
            onImport = onImportConfirm,
            onCancel = onDismiss,
        )
        is BackupState.ConfirmReplace -> ReplaceConfirmDialog(
            onConfirm = onReplaceConfirm,
            onCancel = onReplaceBack,
        )
        is BackupState.Running -> BackupProgressDialog(task = state.task, progress = state.progress)
        is BackupState.Done -> BackupResultDialog(
            task = state.task,
            message = state.message,
            failed = false,
            onDismiss = onDismiss,
        )
        is BackupState.Failed -> BackupResultDialog(
            task = state.task,
            message = state.message,
            failed = true,
            onDismiss = onDismiss,
        )
    }
}

@Composable
fun ExportOptionsDialog(
    includeArchive: Boolean,
    onIncludeArchiveChange: (Boolean) -> Unit,
    onExport: () -> Unit,
    onCancel: () -> Unit,
) {
    GlassDialogScope {
        AlertDialog(
            onDismissRequest = onCancel,
            shape = RoundedRectangle(28.dp),
            title = { Text("Export all data") },
            text = {
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(
                                value = includeArchive,
                                role = Role.Checkbox,
                                onValueChange = onIncludeArchiveChange,
                            ),
                    ) {
                        Checkbox(
                            checked = includeArchive,
                            onCheckedChange = null,
                            colors = CheckboxDefaults.colors(checkedColor = AppTheme.colors.accent),
                        )
                        Text("Include episode archive", style = MaterialTheme.typography.bodyLarge)
                    }
                    Text(
                        "Saves re-downloading old episodes on the new phone. Typically tens of MB.",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppTheme.colors.textSecondary,
                        modifier = Modifier.padding(start = Spacing.s, top = Spacing.xs),
                    )
                }
            },
            confirmButton = {
                GlassButton(onClick = onExport, style = GlassStyle.Prominent) { Text("Export") }
            },
            dismissButton = {
                GlassButton(onClick = onCancel) { Text("Cancel") }
            },
        )
    }
}

@Composable
fun ImportOptionsDialog(
    mode: ImportMode,
    onModeChange: (ImportMode) -> Unit,
    onImport: () -> Unit,
    onCancel: () -> Unit,
) {
    GlassDialogScope {
        AlertDialog(
            onDismissRequest = onCancel,
            shape = RoundedRectangle(28.dp),
            title = { Text("Import all data") },
            text = {
                Column {
                    ImportModeRow(
                        title = "Merge",
                        subtitle = "Keep what's on this phone and add the backup",
                        selected = mode == ImportMode.MERGE,
                        onSelect = { onModeChange(ImportMode.MERGE) },
                    )
                    ImportModeRow(
                        title = "Replace",
                        subtitle = "Remove everything on this phone first",
                        selected = mode == ImportMode.REPLACE,
                        onSelect = { onModeChange(ImportMode.REPLACE) },
                    )
                }
            },
            confirmButton = {
                GlassButton(onClick = onImport, style = GlassStyle.Prominent) { Text("Import") }
            },
            dismissButton = {
                GlassButton(onClick = onCancel) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun ImportModeRow(title: String, subtitle: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            colors = RadioButtonDefaults.colors(selectedColor = AppTheme.colors.accent),
        )
        Column(modifier = Modifier.padding(start = Spacing.xs)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = AppTheme.colors.textSecondary)
        }
    }
}

/** The second, destructive step of a Replace import. Nothing is deleted until "Replace everything". */
@Composable
fun ReplaceConfirmDialog(onConfirm: () -> Unit, onCancel: () -> Unit) {
    GlassDialogScope {
        AlertDialog(
            onDismissRequest = onCancel,
            shape = RoundedRectangle(28.dp),
            title = { Text("Replace everything on this phone?") },
            text = {
                Text(
                    "Podcasts, episodes, listening history and the queue on this phone will be deleted, " +
                        "then replaced with the backup. This can't be undone.",
                    style = MaterialTheme.typography.bodyLarge,
                )
            },
            confirmButton = {
                GlassButton(
                    onClick = onConfirm,
                    style = GlassStyle.Prominent,
                    accent = MaterialTheme.colorScheme.error,
                ) { Text("Replace everything") }
            },
            dismissButton = {
                GlassButton(onClick = onCancel) { Text("Back") }
            },
        )
    }
}

/**
 * Shown while a backup runs. It can't be dismissed by tapping outside or Back, and the work goes on
 * in the ViewModel either way.
 */
@Composable
fun BackupProgressDialog(task: BackupTask, progress: Float) {
    GlassDialogScope {
        AlertDialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            shape = RoundedRectangle(28.dp),
            title = { Text(if (task == BackupTask.EXPORT) "Exporting backup" else "Importing backup") },
            text = {
                Column {
                    LinearProgressIndicator(
                        progress = { progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "Please keep McPodface open until this finishes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppTheme.colors.textSecondary,
                        modifier = Modifier.padding(top = Spacing.s),
                    )
                }
            },
            confirmButton = {},
        )
    }
}

@Composable
fun BackupResultDialog(task: BackupTask, message: String, failed: Boolean, onDismiss: () -> Unit) {
    GlassDialogScope {
        AlertDialog(
            onDismissRequest = onDismiss,
            shape = RoundedRectangle(28.dp),
            title = {
                Text(
                    when {
                        failed && task == BackupTask.EXPORT -> "Export failed"
                        failed -> "Import failed"
                        task == BackupTask.EXPORT -> "Backup saved"
                        else -> "Backup restored"
                    },
                )
            },
            text = {
                Text(
                    message,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
            },
            confirmButton = {
                GlassButton(onClick = onDismiss, style = GlassStyle.Prominent) { Text("Done") }
            },
        )
    }
}

@ThemePreviews
@Composable
private fun ExportOptionsDialogPreview() {
    AppTheme {
        Surface {
            ExportOptionsDialog(includeArchive = true, onIncludeArchiveChange = {}, onExport = {}, onCancel = {})
        }
    }
}

@ThemePreviews
@Composable
private fun ImportOptionsDialogPreview() {
    AppTheme {
        Surface {
            ImportOptionsDialog(mode = ImportMode.MERGE, onModeChange = {}, onImport = {}, onCancel = {})
        }
    }
}

@ThemePreviews
@Composable
private fun ReplaceConfirmDialogPreview() {
    AppTheme {
        Surface {
            ReplaceConfirmDialog(onConfirm = {}, onCancel = {})
        }
    }
}

@ThemePreviews
@Composable
private fun BackupProgressDialogPreview() {
    AppTheme {
        Surface {
            BackupProgressDialog(task = BackupTask.EXPORT, progress = 0.4f)
        }
    }
}

@ThemePreviews
@Composable
private fun BackupResultDialogPreview() {
    AppTheme {
        Surface {
            BackupResultDialog(
                task = BackupTask.IMPORT,
                message = "Restored 12 podcasts, 340 episode states, 5 queue items",
                failed = false,
                onDismiss = {},
            )
        }
    }
}
