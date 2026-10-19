package com.steve.mcpodface.ui.player

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp
import com.steve.mcpodface.ui.theme.AppTheme

/**
 * iOS-style scrubber: a slim capsule track that swells while you drag it. Reports the fraction
 * while dragging ([onScrub]) and once on release ([onScrubEnd]); tap to jump.
 */
@Composable
fun Scrubber(
    fraction: Float,
    onScrub: (Float) -> Unit,
    onScrubEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val haptics = LocalHapticFeedback.current
    var dragging by remember { mutableStateOf(false) }
    val thickness by animateDpAsState(
        if (dragging) 14.dp else 6.dp,
        spring(dampingRatio = 0.6f, stiffness = 500f),
        label = "scrubber",
    )
    val currentOnScrub by rememberUpdatedState(onScrub)
    val currentOnScrubEnd by rememberUpdatedState(onScrubEnd)

    Canvas(
        modifier
            .fillMaxWidth()
            .height(28.dp)
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                setProgress { target ->
                    currentOnScrub(target.coerceIn(0f, 1f))
                    currentOnScrubEnd()
                    true
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    currentOnScrub((offset.x / size.width).coerceIn(0f, 1f))
                    currentOnScrubEnd()
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        dragging = true
                        haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                        currentOnScrub((offset.x / size.width).coerceIn(0f, 1f))
                    },
                    onDragEnd = {
                        dragging = false
                        haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
                        currentOnScrubEnd()
                    },
                    onDragCancel = {
                        dragging = false
                        currentOnScrubEnd()
                    },
                ) { change, _ ->
                    change.consume()
                    currentOnScrub((change.position.x / size.width).coerceIn(0f, 1f))
                }
            },
    ) {
        val h = thickness.toPx()
        val top = (size.height - h) / 2f
        val radius = CornerRadius(h / 2f, h / 2f)
        drawRoundRect(
            color = colors.textPrimary.copy(alpha = 0.16f),
            topLeft = Offset(0f, top),
            size = Size(size.width, h),
            cornerRadius = radius,
        )
        val filled = (size.width * fraction.coerceIn(0f, 1f)).coerceAtLeast(h)
        drawRoundRect(
            color = colors.textPrimary.copy(alpha = if (dragging) 0.95f else 0.75f),
            topLeft = Offset(0f, top),
            size = Size(filled, h),
            cornerRadius = radius,
        )
    }
}
