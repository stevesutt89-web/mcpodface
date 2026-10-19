package com.steve.mcpodface.ui.components

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
 * Asked when a manual download would run on mobile data and the podcast doesn't allow that
 * (PLAN.md §6.6). "Download now" downloads on cellular once; "Wait for Wi-Fi" queues it for Wi-Fi.
 */
@Composable
fun CellularPromptDialog(
    onDownloadNow: () -> Unit,
    onWaitForWifi: () -> Unit,
    onDismiss: () -> Unit,
) {
    GlassDialogScope {
        AlertDialog(
            onDismissRequest = onDismiss,
            shape = RoundedRectangle(28.dp),
            title = { Text("Download on mobile data?") },
            text = { Text("You're not on Wi-Fi. Mobile data downloads are off for this podcast.") },
            confirmButton = {
                GlassButton(onClick = onDownloadNow, style = GlassStyle.Prominent) { Text("Download now") }
            },
            dismissButton = {
                GlassButton(onClick = onWaitForWifi) { Text("Wait for Wi-Fi") }
            },
        )
    }
}

@ThemePreviews
@Composable
private fun CellularPromptDialogPreview() {
    AppTheme {
        Surface {
            CellularPromptDialog(onDownloadNow = {}, onWaitForWifi = {}, onDismiss = {})
        }
    }
}
