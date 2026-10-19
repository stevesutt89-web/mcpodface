package com.steve.mcpodface.ui.settings

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.kyant.shapes.RoundedRectangle
import com.steve.mcpodface.ui.addpodcast.GlassDialogScope
import com.steve.mcpodface.ui.components.glass.GlassButton
import com.steve.mcpodface.ui.components.glass.GlassStyle
import com.steve.mcpodface.ui.theme.AppTheme
import com.steve.mcpodface.ui.theme.ThemePreviews

/**
 * Asked before a global setting change that would delete downloads (PLAN.md §6.5). "Remove"
 * applies the change and deletes the files now; "Cancel" leaves the setting as it was.
 */
@Composable
fun CleanupPreviewDialog(
    count: Int,
    bytes: Long,
    onRemove: () -> Unit,
    onCancel: () -> Unit,
) {
    GlassDialogScope {
        AlertDialog(
            onDismissRequest = onCancel,
            shape = RoundedRectangle(28.dp),
            title = { Text("Remove downloaded episodes?") },
            text = { Text(CleanupChange.message(count, bytes)) },
            confirmButton = {
                GlassButton(onClick = onRemove, style = GlassStyle.Prominent) { Text("Remove") }
            },
            dismissButton = {
                GlassButton(onClick = onCancel) { Text("Cancel") }
            },
        )
    }
}

@ThemePreviews
@Composable
private fun CleanupPreviewDialogPreview() {
    AppTheme {
        Surface {
            CleanupPreviewDialog(count = 7, bytes = 412_000_000L, onRemove = {}, onCancel = {})
        }
    }
}
