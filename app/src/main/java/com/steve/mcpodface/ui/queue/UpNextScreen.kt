package com.steve.mcpodface.ui.queue

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.steve.mcpodface.data.db.EpisodeListItem
import com.steve.mcpodface.playback.NowPlaying
import com.steve.mcpodface.ui.components.EpisodeRow
import com.steve.mcpodface.ui.components.LargeTitleScaffold
import com.steve.mcpodface.ui.components.previewEpisodeItems
import com.steve.mcpodface.ui.theme.AppTheme
import com.steve.mcpodface.ui.theme.Spacing
import com.steve.mcpodface.ui.theme.ThemePreviews
import sh.calvin.reorderable.ReorderableCollectionItemScope
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

private const val PREVIEW_NOW = 1_792_411_200_000L // 2026-10-19T12:00Z

/** Up Next tab (PLAN.md §8.8): reorderable queue, swipe to remove, clear queue. */
@Composable
fun UpNextScreen() {
    val viewModel: UpNextViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            when (message) {
                is UpNextMessage.Removed -> {
                    val result = snackbarHostState.showSnackbar(
                        message = "Removed from Up Next",
                        actionLabel = "Undo",
                        duration = SnackbarDuration.Short,
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        viewModel.undoRemove(message.episodeId, message.index)
                    }
                }
            }
        }
    }

    UpNextContent(
        state = state,
        snackbarHostState = snackbarHostState,
        onPlayClick = viewModel::togglePlay,
        onRemove = viewModel::remove,
        onDragStarted = viewModel::onDragStarted,
        onDragMove = viewModel::onDragMove,
        onDragStopped = viewModel::onDragStopped,
        onClearQueue = viewModel::clearQueue,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UpNextContent(
    state: UpNextUiState,
    snackbarHostState: SnackbarHostState,
    onPlayClick: (Long) -> Unit,
    onRemove: (Long) -> Unit,
    onDragStarted: (Long) -> Unit,
    onDragMove: (Long, Long) -> Unit,
    onDragStopped: () -> Unit,
    onClearQueue: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var showClearDialog by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        val fromId = from.key as? Long
        val toId = to.key as? Long
        if (fromId != null && toId != null) onDragMove(fromId, toId)
    }
    val playingId = state.nowPlaying?.takeIf { it.isPlaying }?.episodeId

    LargeTitleScaffold(
        title = "Up Next",
        actions = {
            if (state.items.isNotEmpty()) {
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Clear queue") },
                            onClick = {
                                menuOpen = false
                                showClearDialog = true
                            },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(bottom = Spacing.xl),
            ) {
                if (state.items.isNotEmpty()) {
                    item(key = "summary") {
                        Text(
                            text = upNextSummary(state.items),
                            style = MaterialTheme.typography.bodySmall,
                            color = AppTheme.colors.textSecondary,
                            modifier = Modifier.padding(horizontal = Spacing.m, vertical = Spacing.xs),
                        )
                    }
                }
                state.nowPlayingHeader?.let { header ->
                    item(key = "now-playing") {
                        NowPlayingHeader(
                            item = header,
                            isPlaying = state.nowPlaying?.isPlaying == true,
                            now = state.now,
                            onPlayClick = { onPlayClick(header.episode.id) },
                        )
                    }
                }
                if (state.loaded && state.items.isEmpty()) {
                    item(key = "empty") { EmptyState() }
                }
                itemsIndexed(state.items, key = { _, item -> item.episode.id }) { index, item ->
                    ReorderableItem(reorderState, key = item.episode.id) { isDragging ->
                        QueueRow(
                            item = item,
                            isDragging = isDragging,
                            isPlaying = playingId == item.episode.id,
                            now = state.now,
                            onPlayClick = { onPlayClick(item.episode.id) },
                            onRemove = { onRemove(item.episode.id) },
                            onDragStarted = { onDragStarted(item.episode.id) },
                            onDragStopped = onDragStopped,
                            showDivider = index < state.items.lastIndex,
                        )
                    }
                }
            }
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Clear Up Next?") },
            text = { Text("Every episode will be removed from the queue.") },
            confirmButton = {
                TextButton(onClick = {
                    showClearDialog = false
                    onClearQueue()
                }) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("Cancel") }
            },
        )
    }
}

/** One queue row: swipe either way to remove, drag the handle at the end to reorder. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReorderableCollectionItemScope.QueueRow(
    item: EpisodeListItem,
    isDragging: Boolean,
    isPlaying: Boolean,
    now: Long,
    onPlayClick: () -> Unit,
    onRemove: () -> Unit,
    onDragStarted: () -> Unit,
    onDragStopped: () -> Unit,
    showDivider: Boolean,
) {
    val colors = AppTheme.colors
    val haptics = LocalHapticFeedback.current
    val currentRemove by rememberUpdatedState(onRemove)
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            val dismissed = value != SwipeToDismissBoxValue.Settled
            if (dismissed) currentRemove()
            dismissed
        },
    )

    Column {
        SwipeToDismissBox(
            state = dismissState,
            backgroundContent = { RemoveBackground(dismissState.dismissDirection) },
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (isDragging) colors.surfaceGrouped else MaterialTheme.colorScheme.background),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EpisodeRow(
                    item = item,
                    onClick = {},
                    onPlayClick = onPlayClick,
                    modifier = Modifier.weight(1f),
                    isPlaying = isPlaying,
                    now = now,
                )
                Icon(
                    imageVector = Icons.Outlined.DragHandle,
                    contentDescription = "Drag to reorder",
                    tint = colors.textSecondary,
                    modifier = Modifier
                        .padding(end = Spacing.m, start = Spacing.xs)
                        .size(24.dp)
                        .draggableHandle(
                            onDragStarted = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                onDragStarted()
                            },
                            onDragStopped = onDragStopped,
                        ),
                )
            }
        }
        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(start = Spacing.m + 56.dp + Spacing.m),
                color = colors.hairline,
            )
        }
    }
}

/** Neutral strip revealed while a row is swiped. */
@Composable
private fun RemoveBackground(direction: SwipeToDismissBoxValue) {
    if (direction == SwipeToDismissBoxValue.Settled) return
    val fromStart = direction == SwipeToDismissBoxValue.StartToEnd
    Box(
        Modifier
            .fillMaxSize()
            .background(AppTheme.colors.surfaceGrouped)
            .padding(horizontal = Spacing.l),
        contentAlignment = if (fromStart) Alignment.CenterStart else Alignment.CenterEnd,
    ) {
        Icon(
            Icons.Outlined.Delete,
            contentDescription = "Remove",
            tint = AppTheme.colors.textPrimary,
            modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
private fun NowPlayingHeader(item: EpisodeListItem, isPlaying: Boolean, now: Long, onPlayClick: () -> Unit) {
    Column {
        Text(
            text = "Now playing",
            style = MaterialTheme.typography.labelMedium,
            color = AppTheme.colors.textSecondary,
            modifier = Modifier.padding(start = Spacing.m, top = Spacing.s),
        )
        EpisodeRow(
            item = item,
            onClick = {},
            onPlayClick = onPlayClick,
            isPlaying = isPlaying,
            now = now,
        )
        HorizontalDivider(color = AppTheme.colors.hairline)
    }
}

@Composable
private fun EmptyState() {
    val colors = AppTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.l, vertical = Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.AutoMirrored.Outlined.QueueMusic,
            contentDescription = null,
            tint = colors.textSecondary,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(Spacing.m))
        Text("Nothing in Up Next", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(Spacing.xs))
        Text(
            "Swipe right on an episode in Latest to add it.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

// ---- Previews -------------------------------------------------------------------------------

@ThemePreviews
@Composable
private fun UpNextListPreview() {
    val items = previewEpisodeItems()
    AppTheme {
        Surface {
            UpNextContent(
                state = UpNextUiState(
                    items = items.take(4),
                    loaded = true,
                    nowPlaying = NowPlaying(episodeId = items[2].episode.id, isPlaying = true, positionMs = 0L, durationMs = 0L),
                    nowPlayingHeader = items[2],
                    now = PREVIEW_NOW,
                ),
                snackbarHostState = remember { SnackbarHostState() },
                onPlayClick = {}, onRemove = {}, onDragStarted = {}, onDragMove = { _, _ -> },
                onDragStopped = {}, onClearQueue = {},
            )
        }
    }
}

@ThemePreviews
@Composable
private fun UpNextEmptyPreview() {
    AppTheme {
        Surface {
            UpNextContent(
                state = UpNextUiState(loaded = true, now = PREVIEW_NOW),
                snackbarHostState = remember { SnackbarHostState() },
                onPlayClick = {}, onRemove = {}, onDragStarted = {}, onDragMove = { _, _ -> },
                onDragStopped = {}, onClearQueue = {},
            )
        }
    }
}
