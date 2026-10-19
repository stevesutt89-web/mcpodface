package com.steve.mcpodface.ui.components

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.kyant.shapes.RoundedRectangle
import com.steve.mcpodface.data.db.EpisodeListItem
import com.steve.mcpodface.data.db.PlayState
import com.steve.mcpodface.ui.theme.AppTheme
import com.steve.mcpodface.ui.theme.Spacing
import com.steve.mcpodface.ui.theme.ThemePreviews

/** Swipe left toggles played: an episode that is played becomes unplayed, anything else becomes played. */
fun playedToggleTarget(current: PlayState): Boolean = current != PlayState.PLAYED

/**
 * An [EpisodeRow] with swipe actions (PLAN.md §8.2, §8.11). Swipe right toggles Listen Later (the
 * strip reads "Listen later" or "Remove"). Swipe left toggles played, and is disabled when
 * [onTogglePlayed] is null. The row always springs back: the action runs, and the drag is cancelled
 * by returning false from confirmValueChange.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeableEpisodeRow(
    item: EpisodeListItem,
    now: Long,
    isPlaying: Boolean,
    inListenLater: Boolean,
    onClick: () -> Unit,
    onPlayClick: () -> Unit,
    onToggleListenLater: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    onTogglePlayed: (() -> Unit)? = null,
    showPodcastTitle: Boolean = true,
    snippet: AnnotatedString? = null,
    /** Replaces the publish date in the meta line, e.g. "Played 3h ago" in History. */
    dateLabel: String? = null,
) {
    val haptics = LocalHapticFeedback.current
    val currentToggleListenLater by rememberUpdatedState(onToggleListenLater)
    val currentTogglePlayed by rememberUpdatedState(onTogglePlayed)
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    currentToggleListenLater()
                }
                SwipeToDismissBoxValue.EndToStart -> currentTogglePlayed?.let {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    it()
                }
                SwipeToDismissBoxValue.Settled -> Unit
            }
            false
        },
    )
    val playedAfterSwipe = playedToggleTarget(item.episode.playState)

    SwipeToDismissBox(
        state = state,
        modifier = modifier,
        enableDismissFromStartToEnd = true,
        enableDismissFromEndToStart = onTogglePlayed != null,
        backgroundContent = {
            SwipeActionBackground(
                direction = state.dismissDirection,
                inListenLater = inListenLater,
                playedLabel = if (playedAfterSwipe) "Played" else "Unplayed",
            )
        },
    ) {
        EpisodeRow(
            item = item,
            onClick = onClick,
            onPlayClick = onPlayClick,
            modifier = Modifier.background(MaterialTheme.colorScheme.background),
            isPlaying = isPlaying,
            showPodcastTitle = showPodcastTitle,
            snippet = snippet,
            now = now,
            onLongClick = onLongClick,
            dateLabel = dateLabel,
        )
    }
}

/**
 * The rounded strip revealed behind a row while it is swiped (inset 8dp). Listen Later is
 * accent-tinted with white content; played/unplayed is neutral. Draws nothing when settled.
 */
@Composable
internal fun SwipeActionBackground(
    direction: SwipeToDismissBoxValue,
    inListenLater: Boolean,
    playedLabel: String,
) {
    if (direction == SwipeToDismissBoxValue.Settled) return
    val colors = AppTheme.colors
    val fromStart = direction == SwipeToDismissBoxValue.StartToEnd
    val label = if (!fromStart) playedLabel else if (inListenLater) "Remove" else "Listen later"
    val icon = when {
        !fromStart -> Icons.Rounded.CheckCircle
        inListenLater -> Icons.Rounded.Remove
        else -> Icons.Rounded.Add
    }
    val shape = remember { RoundedRectangle(20.dp) }
    val container = if (fromStart) colors.accent else colors.surfaceGrouped
    val content = if (fromStart) Color.White else colors.textPrimary

    Box(
        Modifier
            .fillMaxSize()
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
                SwipeBackgroundSample(SwipeToDismissBoxValue.StartToEnd, inListenLater = false, playedLabel = "Played")
                SwipeBackgroundSample(SwipeToDismissBoxValue.StartToEnd, inListenLater = true, playedLabel = "Played")
                SwipeBackgroundSample(SwipeToDismissBoxValue.EndToStart, inListenLater = false, playedLabel = "Played")
                SwipeBackgroundSample(SwipeToDismissBoxValue.EndToStart, inListenLater = false, playedLabel = "Unplayed")
            }
        }
    }
}

@Composable
private fun SwipeBackgroundSample(direction: SwipeToDismissBoxValue, inListenLater: Boolean, playedLabel: String) {
    Box(Modifier.fillMaxWidth().height(72.dp)) {
        SwipeActionBackground(direction = direction, inListenLater = inListenLater, playedLabel = playedLabel)
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
                        inListenLater = index == 1,
                        onClick = {},
                        onPlayClick = {},
                        onToggleListenLater = {},
                        onLongClick = {},
                        onTogglePlayed = {},
                    )
                }
            }
        }
    }
}
