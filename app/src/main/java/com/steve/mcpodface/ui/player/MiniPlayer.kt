package com.steve.mcpodface.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FastForward
import androidx.compose.material.icons.outlined.Forward10
import androidx.compose.material.icons.outlined.Forward30
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.steve.mcpodface.ui.components.Artwork
import com.steve.mcpodface.ui.theme.AppTheme
import com.steve.mcpodface.ui.theme.Spacing
import com.steve.mcpodface.ui.theme.ThemePreviews

/** Height of the mini player bar, excluding the navigation bar (PLAN.md §8.1). */
val MINI_PLAYER_HEIGHT = 64.dp

/**
 * Compact bar above the tab bar showing the loaded episode (PLAN.md §8.1). Tapping the text or
 * artwork opens the full player. Only shown while something is loaded.
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
    val progress = if (state.durationMs > 0) {
        (state.positionMs.toFloat() / state.durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

    // Only the bar itself casts a faint shadow; tonal elevation stays off (PLAN.md §11).
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shadowElevation = 2.dp,
        modifier = modifier.fillMaxWidth().height(MINI_PLAYER_HEIGHT),
    ) {
        Column(Modifier.fillMaxSize()) {
            HorizontalDivider(color = colors.hairline)
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color = colors.accent,
                trackColor = colors.hairline,
            )
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f).padding(start = Spacing.m, end = Spacing.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clickable(role = Role.Button, onClick = onOpen),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Artwork(url = state.artworkUrl, contentDescription = null, size = 44.dp)
                    Spacer(Modifier.width(Spacing.m))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = state.title,
                            style = MaterialTheme.typography.titleSmall,
                            color = colors.textPrimary,
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
                }
                IconButton(onClick = onTogglePlayPause) {
                    if (state.isBuffering) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            color = colors.accent,
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(
                            imageVector = if (state.isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                            contentDescription = if (state.isPlaying) "Pause" else "Play",
                            tint = colors.accent,
                        )
                    }
                }
                IconButton(onClick = onSkipForward) {
                    Icon(
                        imageVector = skipForwardIcon(state.skipForwardSec),
                        contentDescription = "Skip forward ${state.skipForwardSec} seconds",
                        tint = colors.textPrimary,
                    )
                }
            }
        }
    }
}

/** Forward icon matching the configured skip interval; falls back to a generic icon. */
internal fun skipForwardIcon(seconds: Int): ImageVector = when (seconds) {
    10 -> Icons.Outlined.Forward10
    30 -> Icons.Outlined.Forward30
    else -> Icons.Outlined.FastForward
}

@ThemePreviews
@Composable
private fun MiniPlayerPlayingPreview() {
    AppTheme {
        Surface {
            MiniPlayer(
                state = previewPlayerState(isPlaying = true),
                onOpen = {},
                onTogglePlayPause = {},
                onSkipForward = {},
            )
        }
    }
}

@ThemePreviews
@Composable
private fun MiniPlayerPausedPreview() {
    AppTheme {
        Surface {
            MiniPlayer(
                state = previewPlayerState(isPlaying = false),
                onOpen = {},
                onTogglePlayPause = {},
                onSkipForward = {},
            )
        }
    }
}

@ThemePreviews
@Composable
private fun MiniPlayerBufferingPreview() {
    AppTheme {
        Surface {
            MiniPlayer(
                state = previewPlayerState(isPlaying = false, isBuffering = true),
                onOpen = {},
                onTogglePlayPause = {},
                onSkipForward = {},
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
