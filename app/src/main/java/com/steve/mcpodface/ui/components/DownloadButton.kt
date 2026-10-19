package com.steve.mcpodface.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import com.steve.mcpodface.data.db.DownloadEntity
import com.steve.mcpodface.data.db.DownloadSource
import com.steve.mcpodface.data.db.DownloadState
import com.steve.mcpodface.ui.addpodcast.GlassDialogScope
import com.steve.mcpodface.ui.components.glass.GlassIconButton
import com.steve.mcpodface.ui.components.glass.GlassStyle
import com.steve.mcpodface.ui.components.glass.glass
import com.steve.mcpodface.ui.components.glass.rememberGlassPress
import com.steve.mcpodface.ui.theme.AppTheme
import com.steve.mcpodface.ui.theme.ThemePreviews

/**
 * Download control for an episode, one glass circle that shows the state (PLAN.md §6.6, §8.2, §8.5):
 * none = download; queued or waiting for Wi-Fi = ring with a stop square (tap cancels); downloading =
 * progress ring with a stop square (tap cancels); completed = done tick (tap asks, then removes);
 * failed = error (tap retries).
 */
@Composable
fun DownloadButton(
    download: DownloadEntity?,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmRemove by remember { mutableStateOf(false) }
    val state = download?.state
    when (state) {
        null -> GlassIconButton(
            icon = Icons.Rounded.Download,
            contentDescription = "Download",
            onClick = onDownload,
            modifier = modifier,
        )
        DownloadState.QUEUED -> ActiveDownloadButton(
            fraction = null,
            description = "Queued — tap to cancel",
            onCancel = onCancel,
            modifier = modifier,
        )
        DownloadState.PAUSED_NETWORK -> ActiveDownloadButton(
            fraction = null,
            description = "Waiting for Wi-Fi — tap to cancel",
            onCancel = onCancel,
            modifier = modifier,
        )
        DownloadState.DOWNLOADING -> ActiveDownloadButton(
            fraction = download?.let { downloadProgressFraction(it) },
            description = "Downloading — tap to cancel",
            onCancel = onCancel,
            modifier = modifier,
        )
        DownloadState.COMPLETED -> GlassIconButton(
            icon = Icons.Rounded.DownloadDone,
            contentDescription = "Downloaded — tap to remove",
            onClick = { confirmRemove = true },
            tint = AppTheme.colors.accent,
            modifier = modifier,
        )
        DownloadState.FAILED -> GlassIconButton(
            icon = Icons.Rounded.ErrorOutline,
            contentDescription = "Download failed — tap to retry",
            onClick = onRetry,
            tint = MaterialTheme.colorScheme.error,
            modifier = modifier,
        )
    }

    if (confirmRemove) {
        RemoveDownloadDialog(
            onConfirm = {
                confirmRemove = false
                onDelete()
            },
            onDismiss = { confirmRemove = false },
        )
    }
}

/**
 * Progress for a DOWNLOADING row, 0..1, or null when the size is unknown (the ring then spins).
 * Clamped, so a stale byte count can't overflow the ring.
 */
internal fun downloadProgressFraction(download: DownloadEntity): Float? {
    val total = download.totalBytes?.takeIf { it > 0L } ?: return null
    return (download.bytesDownloaded.toFloat() / total).coerceIn(0f, 1f)
}

/** A stop square inside a spinning or filling ring; tapping it cancels the download. */
@Composable
private fun ActiveDownloadButton(
    fraction: Float?,
    description: String,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val (press, pressModifier) = rememberGlassPress(interaction)
    val shape = remember { Capsule() }
    Box(
        modifier = modifier
            .size(44.dp)
            .then(pressModifier)
            .glass(shape, GlassStyle.Regular, pressed = press)
            .clickable(interaction, indication = null, role = Role.Button) {
                haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                onCancel()
            }
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        if (fraction != null) {
            CircularProgressIndicator(
                progress = { fraction },
                modifier = Modifier.size(32.dp),
                color = colors.accent,
                trackColor = colors.hairline,
                strokeWidth = 2.5.dp,
            )
        } else {
            CircularProgressIndicator(
                modifier = Modifier.size(32.dp),
                color = colors.accent,
                trackColor = colors.hairline,
                strokeWidth = 2.5.dp,
            )
        }
        Box(
            Modifier
                .size(12.dp)
                .background(colors.textPrimary, RoundedRectangle(3.dp)),
        )
    }
}

@Composable
private fun RemoveDownloadDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    GlassDialogScope {
        AlertDialog(
            onDismissRequest = onDismiss,
            shape = RoundedRectangle(28.dp),
            title = { Text("Remove download?") },
            text = { Text("The file is deleted from this phone, and auto-download won't fetch it again.") },
            confirmButton = {
                TextButton(onClick = onConfirm) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text("Cancel") }
            },
        )
    }
}

// ---- Previews -------------------------------------------------------------------------------

private fun previewDownload(state: DownloadState, bytes: Long = 0L, total: Long? = 40_000_000L) = DownloadEntity(
    episodeId = 1,
    state = state,
    source = DownloadSource.MANUAL,
    bytesDownloaded = bytes,
    totalBytes = total,
)

@ThemePreviews
@Composable
private fun DownloadButtonNonePreview() {
    AppTheme {
        Surface { DownloadButton(null, onDownload = {}, onCancel = {}, onDelete = {}, onRetry = {}) }
    }
}

@ThemePreviews
@Composable
private fun DownloadButtonQueuedPreview() {
    AppTheme {
        Surface {
            DownloadButton(previewDownload(DownloadState.QUEUED), {}, {}, {}, {})
        }
    }
}

@ThemePreviews
@Composable
private fun DownloadButtonWaitingForWifiPreview() {
    AppTheme {
        Surface {
            DownloadButton(previewDownload(DownloadState.PAUSED_NETWORK), {}, {}, {}, {})
        }
    }
}

@ThemePreviews
@Composable
private fun DownloadButtonDownloadingPreview() {
    AppTheme {
        Surface {
            DownloadButton(previewDownload(DownloadState.DOWNLOADING, bytes = 26_000_000L), {}, {}, {}, {})
        }
    }
}

@ThemePreviews
@Composable
private fun DownloadButtonDownloadingUnknownSizePreview() {
    AppTheme {
        Surface {
            DownloadButton(previewDownload(DownloadState.DOWNLOADING, bytes = 3_000_000L, total = null), {}, {}, {}, {})
        }
    }
}

@ThemePreviews
@Composable
private fun DownloadButtonCompletedPreview() {
    AppTheme {
        Surface {
            DownloadButton(previewDownload(DownloadState.COMPLETED, bytes = 40_000_000L), {}, {}, {}, {})
        }
    }
}

@ThemePreviews
@Composable
private fun DownloadButtonFailedPreview() {
    AppTheme {
        Surface {
            DownloadButton(previewDownload(DownloadState.FAILED, bytes = 1_000_000L), {}, {}, {}, {})
        }
    }
}
