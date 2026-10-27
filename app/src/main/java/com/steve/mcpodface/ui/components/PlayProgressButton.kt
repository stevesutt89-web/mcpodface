package com.steve.mcpodface.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.steve.mcpodface.ui.components.glass.GlassPressSpring
import com.steve.mcpodface.ui.theme.AppTheme
import com.steve.mcpodface.ui.theme.Spacing
import com.steve.mcpodface.ui.theme.ThemePreviews

/**
 * 36dp hollow accent ring with a play/pause icon. The ring is the time left: it starts full and
 * wipes away clockwise from 12 o'clock like a clock hand as [progress] (0..1, null = not started)
 * grows, leaving a faint track; at 1 (played) only the track is left. The button springs down to
 * 90% while pressed and gives a haptic tick on click.
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
    // Animated so the wipe glides when the saved position moves on, rather than jumping.
    val played by animateFloatAsState(progress?.coerceIn(0f, 1f) ?: 0f, label = "playWipe")
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.9f else 1f, GlassPressSpring, label = "playPress")

    // The touch target is at least 48dp; the visible circle stays 36dp and is centred in it.
    Box(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .clickable(interactionSource = interaction, indication = null, role = Role.Button) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(36.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .clip(CircleShape),
            contentAlignment = Alignment.Center,
        ) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 2.5.dp.toPx()
            val inset = stroke / 2f
            val arcSize = Size(size.width - stroke, size.height - stroke)
            if (played > 0f) {
                drawArc(
                    color = colors.textPrimary.copy(alpha = 0.14f),
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = stroke),
                )
            }
            // Time left: from where the hand has reached, clockwise back round to 12 o'clock.
            val left = 1f - played
            if (left > 0f) {
                drawArc(
                    color = colors.accent,
                    startAngle = -90f + 360f * played,
                    sweepAngle = 360f * left,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    // Butt caps while whole, so the full ring has no seam; round once it is wiping.
                    style = Stroke(width = stroke, cap = if (played > 0f) StrokeCap.Round else StrokeCap.Butt),
                )
            }
        }
        Icon(
            imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
            contentDescription = if (isPlaying) "Pause" else "Play",
            tint = colors.accent,
            modifier = Modifier.size(18.dp),
        )
        }
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
                PlayProgressButton(progress = 1f, isPlaying = false, onClick = {})
            }
        }
    }
}
