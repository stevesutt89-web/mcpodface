package com.steve.mcpodface.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FastForward
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Forward30
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import com.steve.mcpodface.ui.components.Artwork
import com.steve.mcpodface.ui.components.glass.GlassPage
import com.steve.mcpodface.ui.components.glass.GlassStyle
import com.steve.mcpodface.ui.components.glass.LocalChromeBackdrop
import com.steve.mcpodface.ui.components.glass.glass
import com.steve.mcpodface.ui.theme.AppTheme
import com.steve.mcpodface.ui.theme.ThemePreviews

/** Height of the floating mini player (PLAN.md §8.1). */
val MINI_PLAYER_HEIGHT = 60.dp

/**
 * Floating Liquid Glass capsule above the tab bar showing the loaded episode (PLAN.md §8.1, §11).
 * Tapping it opens the full player. Samples [LocalChromeBackdrop] like the tab bar.
 */
@Composable
fun MiniPlayer(
    state: PlayerUiState,
    onOpen: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSkipForward: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val haptics = LocalHapticFeedback.current
    val progress = if (state.durationMs > 0) {
        (state.positionMs.toFloat() / state.durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val shape = remember { Capsule() }

    Box(
        modifier
            .fillMaxWidth()
            .height(MINI_PLAYER_HEIGHT)
            .glass(shape, GlassStyle.Regular, backdrop = LocalChromeBackdrop.current, shadow = true)
            .clip(shape)
            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onOpen),
    ) {
        // Progress as a soft accent fill along the bottom edge of the capsule.
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth(progress)
                .height(2.dp)
                .background(colors.accent),
        )
        Row(
            modifier = Modifier.fillMaxWidth().fillMaxHeight().padding(start = 8.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Artwork(
                url = state.artworkUrl,
                contentDescription = null,
                size = 44.dp,
                modifier = Modifier.clip(remember { RoundedRectangle(12.dp) }),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    text = state.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = state.podcastTitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box(
                Modifier
                    .size(44.dp)
                    .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button) {
                        haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                        onTogglePlayPause()
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (state.isBuffering) {
                    CircularProgressIndicator(Modifier.size(22.dp), color = colors.accent, strokeWidth = 2.dp)
                } else {
                    Icon(
                        if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (state.isPlaying) "Pause" else "Play",
                        tint = colors.textPrimary,
                        modifier = Modifier.size(30.dp),
                    )
                }
            }
            Box(
                Modifier
                    .size(44.dp)
                    .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onSkipForward),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    skipForwardIcon(state.skipForwardSec),
                    contentDescription = "Skip forward ${state.skipForwardSec} seconds",
                    tint = colors.textPrimary,
                    modifier = Modifier.size(26.dp),
                )
            }
        }
    }
}

internal fun skipForwardIcon(seconds: Int): ImageVector = when (seconds) {
    10 -> Icons.Rounded.Forward10
    30 -> Icons.Rounded.Forward30
    else -> Icons.Outlined.FastForward
}

@ThemePreviews
@Composable
private fun MiniPlayerPlayingPreview() {
    AppTheme {
        GlassPage {
            MiniPlayer(
                state = previewPlayerState(isPlaying = true),
                onOpen = {},
                onTogglePlayPause = {},
                onSkipForward = {},
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

@ThemePreviews
@Composable
private fun MiniPlayerBufferingPreview() {
    AppTheme {
        GlassPage {
            MiniPlayer(
                state = previewPlayerState(isPlaying = false, isBuffering = true),
                onOpen = {},
                onTogglePlayPause = {},
                onSkipForward = {},
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

/** Sample state shared by the player previews. */
internal fun previewPlayerState(
    isPlaying: Boolean,
    isBuffering: Boolean = false,
) = PlayerUiState(
    episodeId = 1L,
    title = "Why the hedgehog never sleeps",
    podcastTitle = "Sample Show",
    podcastId = 1L,
    artworkUrl = null,
    descriptionHtml = "<p>Notes with a timestamp at 12:34 and a second at 1:02:03.</p>",
    isPlaying = isPlaying,
    isBuffering = isBuffering,
    positionMs = 754_000L,
    durationMs = 3_723_000L,
    speed = 1.0f,
    skipBackSec = 10,
    skipForwardSec = 30,
)
