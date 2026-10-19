package com.steve.mcpodface.ui.playlist

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import com.steve.mcpodface.data.db.EpisodeListItem
import com.steve.mcpodface.data.db.PlaylistEntity
import com.steve.mcpodface.data.db.PlaylistKind
import com.steve.mcpodface.ui.addpodcast.GlassDialogScope
import com.steve.mcpodface.ui.components.BackButton
import com.steve.mcpodface.ui.components.EpisodeRow
import com.steve.mcpodface.ui.components.LargeTitleScaffold
import com.steve.mcpodface.ui.components.glass.GlassButton
import com.steve.mcpodface.ui.components.glass.GlassIconButton
import com.steve.mcpodface.ui.components.glass.GlassPage
import com.steve.mcpodface.ui.components.glass.GlassStyle
import com.steve.mcpodface.ui.components.glass.glass
import com.steve.mcpodface.ui.components.previewEpisodeItems
import com.steve.mcpodface.ui.theme.AppTheme
import com.steve.mcpodface.ui.theme.Spacing
import com.steve.mcpodface.ui.theme.ThemePreviews
import sh.calvin.reorderable.ReorderableCollectionItemScope
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

private const val PREVIEW_NOW = 1_792_411_200_000L // 2026-10-19T12:00Z

/**
 * One playlist (PLAN.md §8.11). Large title, the count, Play all and Shuffle, then the episodes:
 * drag to reorder and swipe left to remove for manual lists; smart lists are read-only. The overflow menu holds rename, edit rules and delete.
 */
@Composable
fun PlaylistScreen(onBack: () -> Unit, onOpenEpisode: (Long) -> Unit, onEditRules: (playlistId: Long) -> Unit) {
    val viewModel: PlaylistViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val currentOnBack by rememberUpdatedState(onBack)

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                PlaylistEvent.Deleted -> currentOnBack()
            }
        }
    }

    PlaylistContent(
        state = state,
        onBack = onBack,
        onOpenEpisode = onOpenEpisode,
        onEditRules = { state.playlist?.id?.let(onEditRules) },
        onTogglePlay = viewModel::togglePlay,
        onPlayAll = viewModel::playAll,
        onShuffle = viewModel::shuffle,
        onConfirmReplace = viewModel::confirmReplace,
        onDismissReplace = viewModel::dismissReplace,
        onRemove = viewModel::remove,
        onDragStarted = viewModel::onDragStarted,
        onDragMove = viewModel::onDragMove,
        onDragStopped = viewModel::onDragStopped,
        onRename = viewModel::rename,
        onDelete = viewModel::delete,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaylistContent(
    state: PlaylistUiState,
    onBack: () -> Unit,
    onOpenEpisode: (Long) -> Unit,
    onEditRules: () -> Unit,
    onTogglePlay: (Long) -> Unit,
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit,
    onConfirmReplace: () -> Unit,
    onDismissReplace: () -> Unit,
    onRemove: (Long) -> Unit,
    onDragStarted: (Long) -> Unit = {},
    onDragMove: (Long, Long) -> Unit = { _, _ -> },
    onDragStopped: () -> Unit = {},
    onRename: (String) -> Unit = {},
    onDelete: () -> Unit = {},
) {
    var menuOpen by remember { mutableStateOf(false) }
    var renameOpen by rememberSaveable { mutableStateOf(false) }
    var deleteOpen by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        val fromId = from.key as? Long
        val toId = to.key as? Long
        if (fromId != null && toId != null) onDragMove(fromId, toId)
    }
    val playlist = state.playlist
    val playingId = state.nowPlaying?.takeIf { it.isPlaying }?.episodeId
    val hasMenu = playlist != null

    GlassPage {
        LargeTitleScaffold(
            title = playlist?.name.orEmpty(),
            background = Color.Transparent,
            navigationIcon = { BackButton(onClick = onBack) },
            actions = {
                if (hasMenu) {
                    Box {
                        GlassIconButton(
                            icon = Icons.Rounded.MoreVert,
                            contentDescription = "More",
                            onClick = { menuOpen = true },
                            size = 40.dp,
                            iconSize = 20.dp,
                            modifier = Modifier.padding(end = Spacing.s),
                        )
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Rename") },
                                leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    renameOpen = true
                                },
                            )
                            if (state.isSmart) {
                                DropdownMenuItem(
                                    text = { Text("Edit rules") },
                                    leadingIcon = { Icon(Icons.Outlined.Tune, contentDescription = null) },
                                    onClick = {
                                        menuOpen = false
                                        onEditRules()
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Delete") },
                                leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    deleteOpen = true
                                },
                            )
                        }
                    }
                }
            },
        ) { padding ->
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = padding.calculateTopPadding(),
                    bottom = padding.calculateBottomPadding() + Spacing.s,
                ),
            ) {
                if (playlist != null) {
                    item(key = "header") {
                        PlaylistHeader(
                            count = state.items.size,
                            isSmart = state.isSmart,
                            hasEpisodes = state.items.isNotEmpty(),
                            onPlayAll = onPlayAll,
                            onShuffle = onShuffle,
                        )
                    }
                }
                if (state.loaded && playlist != null && state.items.isEmpty()) {
                    item(key = "empty") { EmptyState(playlist.kind) }
                }
                itemsIndexed(state.items, key = { _, item -> item.episode.id }) { index, item ->
                    val showDivider = index < state.items.lastIndex
                    if (state.isEditable) {
                        ReorderableItem(reorderState, key = item.episode.id) { isDragging ->
                            SwipeRemovableRow(
                                item = item,
                                isDragging = isDragging,
                                isPlaying = playingId == item.episode.id,
                                now = state.now,
                                onOpen = { onOpenEpisode(item.episode.id) },
                                onPlayClick = { onTogglePlay(item.episode.id) },
                                onRemove = { onRemove(item.episode.id) },
                                onDragStarted = { onDragStarted(item.episode.id) },
                                onDragStopped = onDragStopped,
                                showDivider = showDivider,
                            )
                        }
                    } else {
                        ReadOnlyRow(
                            item = item,
                            isPlaying = playingId == item.episode.id,
                            now = state.now,
                            onOpen = { onOpenEpisode(item.episode.id) },
                            onPlayClick = { onTogglePlay(item.episode.id) },
                            showDivider = showDivider,
                        )
                    }
                }
            }
        }
    }

    if (renameOpen && playlist != null) {
        PlaylistNameDialog(
            title = "Rename playlist",
            initial = playlist.name,
            confirmLabel = "Save",
            onDismiss = { renameOpen = false },
            onConfirm = { name ->
                renameOpen = false
                onRename(name)
            },
        )
    }
    if (deleteOpen && playlist != null) {
        DeletePlaylistDialog(
            name = playlist.name,
            onDismiss = { deleteOpen = false },
            onConfirm = {
                deleteOpen = false
                onDelete()
            },
        )
    }
    if (state.pendingReplace != null) {
        AlertDialog(
            onDismissRequest = onDismissReplace,
            title = { Text("Replace Up Next with this playlist?") },
            text = { Text("The episodes in Up Next will be removed from the queue.") },
            confirmButton = {
                TextButton(onClick = onConfirmReplace) { Text("Replace") }
            },
            dismissButton = {
                TextButton(onClick = onDismissReplace) { Text("Cancel") }
            },
        )
    }
}

/** Count line plus Play all and Shuffle. Both buttons are off for an empty playlist. */
@Composable
private fun PlaylistHeader(
    count: Int,
    isSmart: Boolean,
    hasEpisodes: Boolean,
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit,
) {
    Column(Modifier.padding(horizontal = Spacing.m, vertical = Spacing.xs)) {
        Text(
            text = playlistCountLabel(count, isSmart),
            style = MaterialTheme.typography.bodySmall,
            color = AppTheme.colors.textSecondary,
        )
        Spacer(Modifier.height(Spacing.s))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            GlassButton(
                onClick = onPlayAll,
                enabled = hasEpisodes,
                style = GlassStyle.Prominent,
            ) {
                Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                Text("Play all")
            }
            GlassButton(onClick = onShuffle, enabled = hasEpisodes) {
                Icon(Icons.Outlined.Shuffle, contentDescription = null, modifier = Modifier.size(20.dp))
                Text("Shuffle")
            }
        }
    }
}

/** A manual playlist row: swipe left removes it, the handle at the end drags it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReorderableCollectionItemScope.SwipeRemovableRow(
    item: EpisodeListItem,
    isDragging: Boolean,
    isPlaying: Boolean,
    now: Long,
    onOpen: () -> Unit,
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
            val dismissed = value == SwipeToDismissBoxValue.EndToStart
            if (dismissed) currentRemove()
            dismissed
        },
    )
    val liftShape = remember { RoundedRectangle(20.dp) }

    Column {
        SwipeToDismissBox(
            state = dismissState,
            enableDismissFromStartToEnd = false,
            enableDismissFromEndToStart = true,
            backgroundContent = { RemoveBackground(dismissState.dismissDirection) },
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        val scale = if (isDragging) 1.03f else 1f
                        scaleX = scale
                        scaleY = scale
                        shadowElevation = if (isDragging) 12.dp.toPx() else 0f
                        shape = liftShape
                        clip = false
                    }
                    .background(
                        if (isDragging) colors.surfaceGrouped else MaterialTheme.colorScheme.background,
                        if (isDragging) liftShape else RectangleShape,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EpisodeRow(
                    item = item,
                    onClick = onOpen,
                    onPlayClick = onPlayClick,
                    modifier = Modifier.weight(1f),
                    isPlaying = isPlaying,
                    now = now,
                )
                Icon(
                    imageVector = Icons.Rounded.DragHandle,
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

/** A smart playlist row: no swipe and no handle. */
@Composable
private fun ReadOnlyRow(
    item: EpisodeListItem,
    isPlaying: Boolean,
    now: Long,
    onOpen: () -> Unit,
    onPlayClick: () -> Unit,
    showDivider: Boolean,
) {
    Column {
        EpisodeRow(
            item = item,
            onClick = onOpen,
            onPlayClick = onPlayClick,
            isPlaying = isPlaying,
            now = now,
        )
        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(start = Spacing.m + 56.dp + Spacing.m),
                color = AppTheme.colors.hairline,
            )
        }
    }
}

/** Neutral rounded strip revealed while a row is swiped left; the bin icon uses the error colour. */
@Composable
private fun RemoveBackground(direction: SwipeToDismissBoxValue) {
    if (direction == SwipeToDismissBoxValue.Settled) return
    val shape = remember { RoundedRectangle(20.dp) }
    Box(
        Modifier
            .fillMaxSize()
            .padding(8.dp)
            .background(AppTheme.colors.surfaceGrouped, shape)
            .padding(horizontal = Spacing.l),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Icon(
            Icons.Outlined.Delete,
            contentDescription = "Remove",
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
private fun EmptyState(kind: PlaylistKind) {
    val (icon, message) = when (kind) {
        // LISTEN_LATER is retired and no longer opened from the Library; it shares the manual copy.
        PlaylistKind.MANUAL, PlaylistKind.LISTEN_LATER -> Icons.AutoMirrored.Outlined.PlaylistPlay to "Add episodes from an episode's menu"
        PlaylistKind.SMART -> Icons.Outlined.AutoAwesome to "No episodes match these rules"
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.l, vertical = Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(88.dp)
                .glass(remember { Capsule() }, GlassStyle.Regular),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = AppTheme.colors.accent, modifier = Modifier.size(56.dp))
        }
        Spacer(Modifier.height(Spacing.m))
        Text(
            message,
            style = MaterialTheme.typography.bodyLarge,
            color = AppTheme.colors.textSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

/** Name for a rename or a new playlist. Save stays disabled until the name has text. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaylistNameDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initial) }
    val trimmed = name.trim()
    GlassDialogScope {
        AlertDialog(
            onDismissRequest = onDismiss,
            shape = RoundedRectangle(28.dp),
            title = { Text(title) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("Name") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (trimmed.isNotEmpty()) onConfirm(trimmed) }),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                GlassButton(
                    onClick = { onConfirm(trimmed) },
                    enabled = trimmed.isNotEmpty(),
                    style = GlassStyle.Prominent,
                ) { Text(confirmLabel) }
            },
            dismissButton = {
                GlassButton(onClick = onDismiss) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun DeletePlaylistDialog(name: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete $name?") },
        text = { Text("The playlist will be removed. Its episodes stay in your library.") },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Delete", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/** "3 episodes", "1 episode", or "3 episodes · Smart" for a smart playlist. */
internal fun playlistCountLabel(count: Int, isSmart: Boolean): String {
    val noun = if (count == 1) "episode" else "episodes"
    return if (isSmart) "$count $noun · Smart" else "$count $noun"
}

// ---- Previews -------------------------------------------------------------------------------

private fun previewPlaylist(kind: PlaylistKind, name: String) =
    PlaylistEntity(id = 1, name = name, kind = kind, rulesJson = null, createdAt = 0L)

@ThemePreviews
@Composable
private fun PlaylistManualPreview() {
    AppTheme {
        Surface {
            PlaylistContent(
                state = PlaylistUiState(
                    playlist = previewPlaylist(PlaylistKind.MANUAL, "Road trip"),
                    loaded = true,
                    items = previewEpisodeItems(),
                    now = PREVIEW_NOW,
                ),
                onBack = {}, onOpenEpisode = {}, onEditRules = {}, onTogglePlay = {},
                onPlayAll = {}, onShuffle = {}, onConfirmReplace = {}, onDismissReplace = {}, onRemove = {},
            )
        }
    }
}

@ThemePreviews
@Composable
private fun PlaylistSmartPreview() {
    AppTheme {
        Surface {
            PlaylistContent(
                state = PlaylistUiState(
                    playlist = previewPlaylist(PlaylistKind.SMART, "Unplayed tech"),
                    loaded = true,
                    items = previewEpisodeItems().take(3),
                    now = PREVIEW_NOW,
                ),
                onBack = {}, onOpenEpisode = {}, onEditRules = {}, onTogglePlay = {},
                onPlayAll = {}, onShuffle = {}, onConfirmReplace = {}, onDismissReplace = {}, onRemove = {},
            )
        }
    }
}

@ThemePreviews
@Composable
private fun PlaylistSmartEmptyPreview() {
    AppTheme {
        Surface {
            PlaylistContent(
                state = PlaylistUiState(
                    playlist = previewPlaylist(PlaylistKind.SMART, "Unplayed tech"),
                    loaded = true,
                    now = PREVIEW_NOW,
                ),
                onBack = {}, onOpenEpisode = {}, onEditRules = {}, onTogglePlay = {},
                onPlayAll = {}, onShuffle = {}, onConfirmReplace = {}, onDismissReplace = {}, onRemove = {},
            )
        }
    }
}
