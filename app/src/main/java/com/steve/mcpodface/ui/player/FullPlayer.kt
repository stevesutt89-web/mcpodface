package com.steve.mcpodface.ui.player

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.Replay10
import androidx.compose.material.icons.outlined.Replay30
import androidx.compose.material.icons.outlined.Speaker
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.steve.mcpodface.ui.components.Artwork
import com.steve.mcpodface.ui.episode.ShowNotes
import com.steve.mcpodface.ui.theme.AppTheme
import com.steve.mcpodface.ui.theme.Spacing
import com.steve.mcpodface.ui.theme.ThemePreviews

/** Full player bottom sheet (PLAN.md §8.6). Fills the screen; opened from the mini player. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullPlayer(
    state: PlayerUiState,
    viewModel: PlayerViewModel,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        FullPlayerContent(
            state = state,
            onSeek = viewModel::seekTo,
            onTogglePlayPause = viewModel::togglePlayPause,
            onSkipBack = viewModel::skipBack,
            onSkipForward = viewModel::skipForward,
            onSpeedChange = viewModel::setSpeed,
            onOpenOutputSwitcher = { openOutputSwitcher(context) },
        )
    }
}

/** Sheet content without the ModalBottomSheet wrapper, so it can be previewed directly. */
@Composable
fun FullPlayerContent(
    state: PlayerUiState,
    onSeek: (positionMs: Long) -> Unit,
    onTogglePlayPause: () -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
    onSpeedChange: (speed: Float, rememberForPodcast: Boolean) -> Unit,
    onOpenOutputSwitcher: () -> Unit,
) {
    val colors = AppTheme.colors
    var showSpeedSheet by rememberSaveable { mutableStateOf(false) }

    // While the user drags the scrubber, show the drag position; the seek happens on release.
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val playedFraction = if (state.durationMs > 0) {
        (state.positionMs.toFloat() / state.durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val shownFraction = dragFraction ?: playedFraction
    val shownPositionMs = (shownFraction * state.durationMs).toLong()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.m)
            .padding(bottom = Spacing.xl),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            Artwork(
                url = state.artworkUrl,
                contentDescription = null,
                size = maxWidth,
            )
        }
        Spacer(Modifier.height(Spacing.l))
        Text(
            text = state.title,
            style = MaterialTheme.typography.titleLarge,
            color = colors.textPrimary,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = state.podcastTitle,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        Spacer(Modifier.height(Spacing.l))
        Slider(
            value = shownFraction,
            onValueChange = { dragFraction = it },
            onValueChangeFinished = {
                dragFraction?.let { onSeek((it * state.durationMs).toLong()) }
                dragFraction = null
            },
            colors = SliderDefaults.colors(
                thumbColor = colors.accent,
                activeTrackColor = colors.accent,
                inactiveTrackColor = colors.hairline,
            ),
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = formatClock(shownPositionMs),
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
            )
            Text(
                text = if (state.durationMs > 0) {
                    formatRemaining(state.durationMs, shownPositionMs, state.speed)
                } else {
                    "--:--"
                },
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
            )
        }

        Spacer(Modifier.height(Spacing.m))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            IconButton(onClick = onSkipBack, modifier = Modifier.size(56.dp)) {
                Icon(
                    imageVector = skipBackIcon(state.skipBackSec),
                    contentDescription = "Skip back ${state.skipBackSec} seconds",
                    tint = colors.textPrimary,
                    modifier = Modifier.size(32.dp),
                )
            }
            Spacer(Modifier.width(Spacing.l))
            PlayPauseButton(
                isPlaying = state.isPlaying,
                isBuffering = state.isBuffering,
                onClick = onTogglePlayPause,
            )
            Spacer(Modifier.width(Spacing.l))
            IconButton(onClick = onSkipForward, modifier = Modifier.size(56.dp)) {
                Icon(
                    imageVector = skipForwardIcon(state.skipForwardSec),
                    contentDescription = "Skip forward ${state.skipForwardSec} seconds",
                    tint = colors.textPrimary,
                    modifier = Modifier.size(32.dp),
                )
            }
        }

        Spacer(Modifier.height(Spacing.m))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AssistChip(
                onClick = { showSpeedSheet = true },
                label = { Text(formatSpeed(state.speed), style = MaterialTheme.typography.labelLarge) },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = colors.surfaceGrouped,
                    labelColor = colors.textPrimary,
                ),
                border = null,
            )
            Spacer(Modifier.weight(1f))
            IconButton(onClick = {}, enabled = false) {
                Icon(
                    imageVector = Icons.Outlined.Bedtime,
                    contentDescription = "Sleep timer (coming soon)",
                )
            }
            IconButton(onClick = onOpenOutputSwitcher) {
                Icon(
                    imageVector = Icons.Outlined.Speaker,
                    contentDescription = "Output device",
                    tint = colors.textPrimary,
                )
            }
        }

        Spacer(Modifier.height(Spacing.l))
        Text(
            text = "Show notes",
            style = MaterialTheme.typography.titleMedium,
            color = colors.textPrimary,
        )
        Spacer(Modifier.height(Spacing.s))
        ShowNotes(html = state.descriptionHtml, onTimestampClick = onSeek)
    }

    if (showSpeedSheet) {
        SpeedSheet(
            speed = state.speed,
            onSpeedChange = onSpeedChange,
            onDismiss = { showSpeedSheet = false },
        )
    }
}

@Composable
private fun PlayPauseButton(
    isPlaying: Boolean,
    isBuffering: Boolean,
    onClick: () -> Unit,
) {
    val colors = AppTheme.colors
    val haptic = LocalHapticFeedback.current
    PrimaryCircleButton(
        onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onClick()
        },
    ) {
        if (isBuffering) {
            CircularProgressIndicator(
                modifier = Modifier.size(28.dp),
                color = colors.onButtonPrimary,
                strokeWidth = 2.dp,
            )
        } else {
            Icon(
                imageVector = if (isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play",
                tint = colors.onButtonPrimary,
                modifier = Modifier.size(32.dp),
            )
        }
    }
}

/** 64dp filled circle in the neutral primary-button colour (PLAN.md §11). */
@Composable
private fun PrimaryCircleButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    val colors = AppTheme.colors
    Box(
        modifier = Modifier
            .size(64.dp)
            .clip(CircleShape)
            .background(colors.buttonPrimary)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/** Back icon matching the configured interval; falls back to a generic replay icon. */
private fun skipBackIcon(seconds: Int): ImageVector = when (seconds) {
    10 -> Icons.Outlined.Replay10
    30 -> Icons.Outlined.Replay30
    else -> Icons.Outlined.Replay
}

/** Opens the system media output switcher for this app (PLAN.md §8.6). Silently does nothing if absent. */
private fun openOutputSwitcher(context: Context) {
    val intent = Intent("com.android.settings.panel.action.MEDIA_OUTPUT")
        .putExtra("com.android.settings.panel.extra.PACKAGE_NAME", context.packageName)
    runCatching { context.startActivity(intent) }
}

@ThemePreviews
@Composable
private fun FullPlayerPlayingPreview() {
    AppTheme {
        Surface {
            FullPlayerContent(
                state = previewPlayerState(isPlaying = true),
                onSeek = {},
                onTogglePlayPause = {},
                onSkipBack = {},
                onSkipForward = {},
                onSpeedChange = { _, _ -> },
                onOpenOutputSwitcher = {},
            )
        }
    }
}

@ThemePreviews
@Composable
private fun FullPlayerBufferingPreview() {
    AppTheme {
        Surface {
            FullPlayerContent(
                state = previewPlayerState(isPlaying = false, isBuffering = true),
                onSeek = {},
                onTogglePlayPause = {},
                onSkipBack = {},
                onSkipForward = {},
                onSpeedChange = { _, _ -> },
                onOpenOutputSwitcher = {},
            )
        }
    }
}
