package com.steve.mcpodface.ui.latest

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
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.CheckCircle
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
import androidx.compose.ui.unit.dp
import com.kyant.shapes.RoundedRectangle
import com.steve.mcpodface.data.db.EpisodeListItem
import com.steve.mcpodface.ui.components.EpisodeRow
import com.steve.mcpodface.ui.components.previewEpisodeItems
import com.steve.mcpodface.ui.theme.AppTheme
import com.steve.mcpodface.ui.theme.Spacing
import com.steve.mcpodface.ui.theme.ThemePreviews

/**
 * An [EpisodeRow] with two swipe actions (PLAN.md §8.2). Swipe right adds to Up Next; swipe left
 * toggles played. The row always springs back: the action is performed, and the drag is cancelled
 * by returning false from confirmValueChange.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeableEpisodeRow(
    item: EpisodeListItem,
    now: Long,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onPlayClick: () -> Unit,
    onLongClick: () -> Unit,
    onAddToUpNext: () -> Unit,
    onTogglePlayed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentAddToUpNext by rememberUpdatedState(onAddToUpNext)
    val currentTogglePlayed by rememberUpdatedState(onTogglePlayed)
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> currentAddToUpNext()
                SwipeToDismissBoxValue.EndToStart -> currentTogglePlayed()
                SwipeToDismissBoxValue.Settled -> Unit
            }
            false
        },
    )
    val playedAfterSwipe = playedToggleTarget(item.episode.playState)

    SwipeToDismissBox(
        state = state,
        modifier = modifier,
        backgroundContent = {
            SwipeActionBackground(
                direction = state.dismissDirection,
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
            now = now,
            onLongClick = onLongClick,
        )
    }
}

/**
 * The rounded strip revealed behind a row while it is swiped (inset 8dp). Up Next is accent-tinted
 * with white content; played/unplayed is neutral. Draws nothing when settled.
 */
@Composable
internal fun SwipeActionBackground(direction: SwipeToDismissBoxValue, playedLabel: String) {
    if (direction == SwipeToDismissBoxValue.Settled) return
    val colors = AppTheme.colors
    val fromStart = direction == SwipeToDismissBoxValue.StartToEnd
    val icon = if (fromStart) Icons.AutoMirrored.Rounded.PlaylistAdd else Icons.Rounded.CheckCircle
    val label = if (fromStart) "Up Next" else playedLabel
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
                SwipeBackgroundSample(SwipeToDismissBoxValue.StartToEnd, playedLabel = "Played")
                SwipeBackgroundSample(SwipeToDismissBoxValue.EndToStart, playedLabel = "Played")
                SwipeBackgroundSample(SwipeToDismissBoxValue.EndToStart, playedLabel = "Unplayed")
            }
        }
    }
}

@Composable
private fun SwipeBackgroundSample(direction: SwipeToDismissBoxValue, playedLabel: String) {
    Box(Modifier.fillMaxWidth().height(72.dp)) {
        SwipeActionBackground(direction = direction, playedLabel = playedLabel)
    }
}

@ThemePreviews
@Composable
private fun SwipeableEpisodeRowPreview() {
    AppTheme {
        Surface {
            Column {
                previewEpisodeItems().take(2).forEach { item ->
                    SwipeableEpisodeRow(
                        item = item,
                        now = 1_792_411_200_000L,
                        isPlaying = false,
                        onClick = {},
                        onPlayClick = {},
                        onLongClick = {},
                        onAddToUpNext = {},
                        onTogglePlayed = {},
                    )
                }
            }
        }
    }
}
