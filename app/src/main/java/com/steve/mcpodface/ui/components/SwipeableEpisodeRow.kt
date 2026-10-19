package com.steve.mcpodface.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.PlaylistRemove
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.kyant.shapes.RoundedRectangle
import com.steve.mcpodface.data.db.EpisodeListItem
import com.steve.mcpodface.data.db.PlayState
import com.steve.mcpodface.ui.theme.AppTheme
import com.steve.mcpodface.ui.theme.Spacing
import com.steve.mcpodface.ui.theme.ThemePreviews
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

/** Swipe left toggles played: an episode that is played becomes unplayed, anything else becomes played. */
fun playedToggleTarget(current: PlayState): Boolean = current != PlayState.PLAYED

/** Fraction of the row width a swipe must pass to trigger its action. */
private const val SWIPE_THRESHOLD = 0.3f

/** A fling faster than this (px/s) in the swipe's direction also triggers it. */
private const val FLING_VELOCITY = 1_500f

/** How long the row stays off screen before it slides back in from the other side. */
private const val RETURN_DELAY_MS = 500L

private enum class SwipePhase { IDLE, FLYING, RETURNING }

/**
 * An [EpisodeRow] with swipe actions (PLAN.md §8.2, §8.11). Swipe right toggles Up Next (the
 * strip reads "Up Next" or "Remove from Up Next"). Swipe left toggles played, and is disabled when
 * [onTogglePlayed] is null. Past the threshold the row flies off that side, the action runs, and
 * about [RETURN_DELAY_MS] later the row slides back in from the other side. Short of it, the row
 * springs back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeableEpisodeRow(
    item: EpisodeListItem,
    now: Long,
    isPlaying: Boolean,
    inUpNext: Boolean,
    onClick: () -> Unit,
    onPlayClick: () -> Unit,
    onToggleUpNext: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    onTogglePlayed: (() -> Unit)? = null,
    showPodcastTitle: Boolean = true,
    snippet: AnnotatedString? = null,
    /** Replaces the publish date in the meta line, e.g. "Played 3h ago" in History. */
    dateLabel: String? = null,
    preferPodcastArtwork: Boolean = true,
) {
    val haptics = LocalHapticFeedback.current
    val currentToggleUpNext by rememberUpdatedState(onToggleUpNext)
    val currentTogglePlayed by rememberUpdatedState(onTogglePlayed)
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    var widthPx by remember { mutableFloatStateOf(0f) }
    var phase by remember { mutableStateOf(SwipePhase.IDLE) }
    val canSwipeLeft = onTogglePlayed != null
    val playedAfterSwipe = playedToggleTarget(item.episode.playState)
    // Labels frozen when a swipe commits, so the strip doesn't flip while the action lands.
    var frozenInUpNext by remember { mutableStateOf<Boolean?>(null) }
    var frozenPlayedLabel by remember { mutableStateOf<String?>(null) }

    val dragState = rememberDraggableState { delta ->
        if (phase == SwipePhase.IDLE) {
            val min = if (canSwipeLeft) -widthPx else 0f
            scope.launch { offset.snapTo((offset.value + delta).coerceIn(min, widthPx)) }
        }
    }

    fun settle(velocity: Float) {
        if (phase != SwipePhase.IDLE) return
        scope.launch {
            val value = offset.value
            val threshold = widthPx * SWIPE_THRESHOLD
            val fling = abs(velocity) > FLING_VELOCITY && sign(velocity) == sign(value)
            val toEnd = value > 0f && (value > threshold || fling)
            val toStart = value < 0f && canSwipeLeft && (-value > threshold || fling)
            if (!toEnd && !toStart) {
                offset.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy), velocity)
                return@launch
            }
            phase = SwipePhase.FLYING
            frozenInUpNext = inUpNext
            frozenPlayedLabel = if (playedAfterSwipe) "Played" else "Unplayed"
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            val out = if (toEnd) widthPx else -widthPx
            offset.animateTo(out, tween(durationMillis = 180, easing = FastOutLinearInEasing), velocity)
            if (toEnd) currentToggleUpNext() else currentTogglePlayed?.invoke()
            delay(RETURN_DELAY_MS)
            phase = SwipePhase.RETURNING
            offset.snapTo(-out)
            offset.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow))
            phase = SwipePhase.IDLE
            frozenInUpNext = null
            frozenPlayedLabel = null
        }
    }

    // Which strip is showing; derived so the background only recomposes when it changes.
    val direction by remember {
        derivedStateOf {
            when {
                phase == SwipePhase.RETURNING || offset.value == 0f -> SwipeToDismissBoxValue.Settled
                offset.value > 0f -> SwipeToDismissBoxValue.StartToEnd
                else -> SwipeToDismissBoxValue.EndToStart
            }
        }
    }

    Box(
        modifier
            .fillMaxWidth()
            .clipToBounds()
            .onSizeChanged { widthPx = it.width.toFloat() }
            .draggable(
                state = dragState,
                orientation = Orientation.Horizontal,
                enabled = phase == SwipePhase.IDLE,
                onDragStopped = { velocity -> settle(velocity) },
            ),
    ) {
        SwipeActionBackground(
            direction = direction,
            inUpNext = frozenInUpNext ?: inUpNext,
            playedLabel = frozenPlayedLabel ?: if (playedAfterSwipe) "Played" else "Unplayed",
            modifier = Modifier.matchParentSize(),
        )
        EpisodeRow(
            item = item,
            onClick = onClick,
            onPlayClick = onPlayClick,
            modifier = Modifier
                .graphicsLayer { translationX = offset.value }
                .background(MaterialTheme.colorScheme.background),
            isPlaying = isPlaying,
            showPodcastTitle = showPodcastTitle,
            snippet = snippet,
            now = now,
            onLongClick = onLongClick,
            dateLabel = dateLabel,
            preferPodcastArtwork = preferPodcastArtwork,
            customActions = buildList {
                // TalkBack users can't swipe, so the swipe actions are also custom actions.
                add(
                    CustomAccessibilityAction(
                        if (inUpNext) "Remove from Up Next" else "Add to Up Next",
                    ) {
                        onToggleUpNext()
                        true
                    },
                )
                if (onTogglePlayed != null) {
                    add(
                        CustomAccessibilityAction(if (playedAfterSwipe) "Mark played" else "Mark unplayed") {
                            onTogglePlayed()
                            true
                        },
                    )
                }
            },
        )
    }
}

/**
 * The rounded strip revealed behind a row while it is swiped (inset 8dp). Up Next is
 * accent-tinted with white content; played/unplayed is neutral. Draws nothing when settled.
 */
@Composable
internal fun SwipeActionBackground(
    direction: SwipeToDismissBoxValue,
    inUpNext: Boolean,
    playedLabel: String,
    modifier: Modifier = Modifier.fillMaxSize(),
) {
    if (direction == SwipeToDismissBoxValue.Settled) return
    val colors = AppTheme.colors
    val fromStart = direction == SwipeToDismissBoxValue.StartToEnd
    val label = if (!fromStart) playedLabel else if (inUpNext) "Remove from Up Next" else "Up Next"
    val icon = when {
        !fromStart -> Icons.Rounded.CheckCircle
        inUpNext -> Icons.Rounded.PlaylistRemove
        else -> Icons.AutoMirrored.Rounded.PlaylistAdd
    }
    val shape = remember { RoundedRectangle(20.dp) }
    val container = if (fromStart) colors.accent else colors.surfaceGrouped
    val content = if (fromStart) Color.White else colors.textPrimary

    Box(
        modifier
            .padding(8.dp)
            .background(container, shape)
            .padding(horizontal = Spacing.l),
        contentAlignment = if (fromStart) Alignment.CenterStart else Alignment.CenterEnd,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!fromStart) {
                Text(label, style = MaterialTheme.typography.labelLarge, color = content)
                Spacer(Modifier.width(Spacing.s))
            }
            Icon(icon, contentDescription = label, tint = content, modifier = Modifier.size(24.dp))
            if (fromStart) {
                Spacer(Modifier.width(Spacing.s))
                Text(label, style = MaterialTheme.typography.labelLarge, color = content)
            }
        }
    }
}

@ThemePreviews
@Composable
private fun SwipeBackgroundsPreview() {
    AppTheme {
        Surface {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                SwipeBackgroundSample(SwipeToDismissBoxValue.StartToEnd, inUpNext = false, playedLabel = "Played")
                SwipeBackgroundSample(SwipeToDismissBoxValue.StartToEnd, inUpNext = true, playedLabel = "Played")
                SwipeBackgroundSample(SwipeToDismissBoxValue.EndToStart, inUpNext = false, playedLabel = "Played")
                SwipeBackgroundSample(SwipeToDismissBoxValue.EndToStart, inUpNext = false, playedLabel = "Unplayed")
            }
        }
    }
}

@Composable
private fun SwipeBackgroundSample(direction: SwipeToDismissBoxValue, inUpNext: Boolean, playedLabel: String) {
    Box(Modifier.fillMaxWidth().height(72.dp)) {
        SwipeActionBackground(direction = direction, inUpNext = inUpNext, playedLabel = playedLabel)
    }
}

@ThemePreviews
@Composable
private fun SwipeableEpisodeRowPreview() {
    AppTheme {
        Surface {
            Column {
                previewEpisodeItems().take(2).forEachIndexed { index, item ->
                    SwipeableEpisodeRow(
                        item = item,
                        now = 1_792_411_200_000L,
                        isPlaying = false,
                        inUpNext = index == 1,
                        onClick = {},
                        onPlayClick = {},
                        onToggleUpNext = {},
                        onLongClick = {},
                        onTogglePlayed = {},
                    )
                }
            }
        }
    }
}
