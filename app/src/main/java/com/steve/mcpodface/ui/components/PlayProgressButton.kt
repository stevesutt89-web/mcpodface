package com.steve.mcpodface.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.steve.mcpodface.ui.theme.AppTheme
import com.steve.mcpodface.ui.theme.Spacing
import com.steve.mcpodface.ui.theme.ThemePreviews

/**
 * 40dp circular play/pause control. A thin accent ring shows [progress] (0..1) over a hairline
 * track when progress is non-null and above zero. Click gives a gentle haptic tick.
 */
@Composable
fun PlayProgressButton(
    progress: Float?,
    isPlaying: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val haptic = LocalHapticFeedback.current
    val ring = progress?.coerceIn(0f, 1f)?.takeIf { it > 0f }

    Box(
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        if (ring != null) {
            CircularProgressIndicator(
                progress = { ring },
                modifier = Modifier.fillMaxSize(),
                color = colors.accent,
                trackColor = colors.hairline,
                strokeWidth = 2.dp,
            )
        }
        Icon(
            imageVector = if (isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
            contentDescription = if (isPlaying) "Pause" else "Play",
            tint = colors.textPrimary,
            modifier = Modifier.size(20.dp),
        )
    }
}

@ThemePreviews
@Composable
private fun PlayProgressButtonPreview() {
    AppTheme {
        Surface {
            Row(
                Modifier.padding(Spacing.m),
                horizontalArrangement = Arrangement.spacedBy(Spacing.m),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlayProgressButton(progress = null, isPlaying = false, onClick = {})
                PlayProgressButton(progress = 0.0f, isPlaying = false, onClick = {})
                PlayProgressButton(progress = 0.4f, isPlaying = false, onClick = {})
                PlayProgressButton(progress = 0.8f, isPlaying = true, onClick = {})
            }
        }
    }
}
