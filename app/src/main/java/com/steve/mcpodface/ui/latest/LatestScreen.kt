package com.steve.mcpodface.ui.latest

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.LibraryAdd
import androidx.compose.material.icons.outlined.Podcasts
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.PlaylistRemove
import androidx.compose.material.icons.rounded.Podcasts
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kyant.shapes.Capsule
import com.steve.mcpodface.data.db.EpisodeListItem
import com.steve.mcpodface.data.prefs.LatestFilter
import com.steve.mcpodface.ui.components.CellularPromptDialog
import com.steve.mcpodface.ui.components.LargeTitleScaffold
import com.steve.mcpodface.ui.components.LocalAppSnackbarHostState
import com.steve.mcpodface.ui.components.SettingsButton
import com.steve.mcpodface.ui.components.showReplacing
import com.steve.mcpodface.ui.components.SwipeableEpisodeRow
import com.steve.mcpodface.ui.components.glass.GlassChip
import com.steve.mcpodface.ui.components.glass.GlassPage
import com.steve.mcpodface.ui.components.glass.GlassStyle
import com.steve.mcpodface.ui.components.glass.glass
import com.steve.mcpodface.ui.components.previewEpisodeItems
import com.steve.mcpodface.ui.playlist.AddToPlaylistSheet
import com.steve.mcpodface.ui.theme.AppTheme
import com.steve.mcpodface.ui.theme.Spacing
import com.steve.mcpodface.ui.theme.ThemePreviews
import kotlinx.coroutines.launch

private const val PREVIEW_NOW = 1_792_411_200_000L // 2026-10-19T12:00Z

/** Latest tab (PLAN.md §8.2): recent episodes across subscriptions, with filters and swipe actions. */
@Composable
fun LatestScreen(
    onOpenSettings: () -> Unit,
    onOpenPodcast: (Long) -> Unit,
    onOpenEpisode: (Long) -> Unit,
) {
    val viewModel: LatestViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = LocalAppSnackbarHostState.current
    val context = LocalContext.current

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            // Each message replaces the one on screen, so collecting never waits on a snackbar.
            launch {
                when (message) {
                    is LatestMessage.UpNextChanged ->
                        snackbarHostState.showReplacing(
                            if (message.added) "Added to Up Next" else "Removed from Up Next",
                        )
                    is LatestMessage.PlayedChanged -> {
                        val text = if (message.nowPlayed) "Marked as played" else "Marked as unplayed"
                        val result = snackbarHostState.showReplacing(message = text, actionLabel = "Undo")
                        if (result == SnackbarResult.ActionPerformed) {
                            viewModel.undoPlayedChange(message.episodeId, message.nowPlayed)
                        }
                    }
                }
            }
        }
    }

    LatestContent(
        state = state,
        onOpenSettings = onOpenSettings,
        onOpenPodcast = onOpenPodcast,
        onOpenEpisode = onOpenEpisode,
        onFilterSelected = viewModel::setFilter,
        onRefresh = viewModel::refresh,
        onTogglePlay = viewModel::togglePlay,
        onToggleUpNext = viewModel::toggleUpNext,
        onTogglePlayed = viewModel::togglePlayed,
        onToggleStar = viewModel::toggleStar,
        onShare = { item -> context.startActivity(shareEpisodeIntent(item)) },
        onDownloadAction = viewModel::onDownloadMenuAction,
    )
    if (state.cellularPromptEpisodeId != null) {
        CellularPromptDialog(
            onDownloadNow = { viewModel.confirmCellularDownload(allowCellular = true) },
            onWaitForWifi = { viewModel.confirmCellularDownload(allowCellular = false) },
            onDismiss = viewModel::dismissCellularPrompt,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LatestContent(
    state: LatestUiState,
    onOpenSettings: () -> Unit,
    onOpenPodcast: (Long) -> Unit,
    onOpenEpisode: (Long) -> Unit,
    onFilterSelected: (LatestFilter) -> Unit,
    onRefresh: () -> Unit,
    onTogglePlay: (Long) -> Unit,
    onToggleUpNext: (Long) -> Unit,
    onTogglePlayed: (EpisodeListItem) -> Unit,
    onToggleStar: (EpisodeListItem) -> Unit,
    onShare: (EpisodeListItem) -> Unit,
    onDownloadAction: (EpisodeListItem) -> Unit = {},
) {
    var menuEpisodeId by rememberSaveable { mutableStateOf<Long?>(null) }
    var playlistEpisodeId by rememberSaveable { mutableStateOf<Long?>(null) }
    val menuItem = menuEpisodeId?.let { id -> state.items.firstOrNull { it.episode.id == id } }

    GlassPage {
        LargeTitleScaffold(
            title = "Latest",
            background = Color.Transparent,
            actions = {
                SettingsButton(onOpenSettings)
            },
        ) { padding ->
            val pullState = rememberPullToRefreshState()
            Box(Modifier.fillMaxSize()) {
                PullToRefreshBox(
                    isRefreshing = state.refreshing,
                    onRefresh = onRefresh,
                    modifier = Modifier.fillMaxSize(),
                    state = pullState,
                    indicator = {
                        PullToRefreshDefaults.Indicator(
                            state = pullState,
                            isRefreshing = state.refreshing,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = padding.calculateTopPadding()),
                        )
                    },
                ) {
                    LazyColumn(
                        contentPadding = PaddingValues(
                            top = padding.calculateTopPadding(),
                            bottom = padding.calculateBottomPadding() + Spacing.s,
                        ),
                    ) {
                        item(key = "filters") {
                            FilterChipRow(selected = state.filter, onSelect = onFilterSelected)
                        }
                        if (state.loaded && state.items.isEmpty()) {
                            item(key = "empty") { EmptyState(filter = state.filter) }
                        }
                        itemsIndexed(state.items, key = { _, item -> item.episode.id }) { index, item ->
                            SwipeableEpisodeRow(
                                item = item,
                                now = state.now,
                                isPlaying = state.nowPlaying?.let { it.episodeId == item.episode.id && it.isPlaying } ?: false,
                                inUpNext = item.inQueue,
                                onClick = { onOpenEpisode(item.episode.id) },
                                onPlayClick = { onTogglePlay(item.episode.id) },
                                onLongClick = { menuEpisodeId = item.episode.id },
                                onToggleUpNext = { onToggleUpNext(item.episode.id) },
                                onTogglePlayed = { onTogglePlayed(item) },
                            )
                            if (index < state.items.lastIndex) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(start = Spacing.m + 56.dp + Spacing.m),
                                    color = AppTheme.colors.hairline,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (menuItem != null) {
        EpisodeActionSheet(
            item = menuItem,
            onDismiss = { menuEpisodeId = null },
            onToggleUpNext = { onToggleUpNext(menuItem.episode.id) },
            onAddToPlaylist = { playlistEpisodeId = menuItem.episode.id },
            onToggleStar = { onToggleStar(menuItem) },
            onShare = { onShare(menuItem) },
            onGoToPodcast = { onOpenPodcast(menuItem.episode.podcastId) },
            onDownload = { onDownloadAction(menuItem) },
        )
    }
    playlistEpisodeId?.let { episodeId ->
        AddToPlaylistSheet(episodeId = episodeId, onDismiss = { playlistEpisodeId = null })
    }
}

internal fun LatestFilter.label(): String = when (this) {
    LatestFilter.ALL -> "All"
    LatestFilter.UNPLAYED -> "Unplayed"
    LatestFilter.DOWNLOADED -> "Downloaded"
}

/** Filter chips as glass, scrolling horizontally with the list (first item). */
@Composable
private fun FilterChipRow(selected: LatestFilter, onSelect: (LatestFilter) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = Spacing.m, vertical = Spacing.s),
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        LatestFilter.entries.forEach { filter ->
            GlassChip(
                label = filter.label(),
                selected = filter == selected,
                onClick = { onSelect(filter) },
            )
        }
    }
}

@Composable
private fun EmptyState(filter: LatestFilter) {
    val (icon, title, body) = if (filter == LatestFilter.ALL) {
        Triple(Icons.Rounded.Podcasts, "No episodes yet", "Subscribe to podcasts from the Search tab.")
    } else {
        Triple(Icons.Rounded.FilterList, "Nothing here", "Nothing matches the ${filter.label()} filter.")
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
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(Spacing.xs))
        Text(
            body,
            style = MaterialTheme.typography.bodyLarge,
            color = AppTheme.colors.textSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

/** Long-press menu (PLAN.md §8.2). Every action closes the sheet after it runs. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EpisodeActionSheet(
    item: EpisodeListItem,
    onDismiss: () -> Unit,
    onToggleUpNext: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onToggleStar: () -> Unit,
    onShare: () -> Unit,
    onGoToPodcast: () -> Unit,
    onDownload: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    val starred = item.episode.isStarred
    val downloadAction = downloadMenuAction(item.downloadState)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.padding(bottom = Spacing.l)) {
            Text(
                item.episode.title.ifBlank { "Untitled episode" },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = Spacing.m, vertical = Spacing.s),
            )
            if (item.inQueue) {
                SheetAction(Icons.Rounded.PlaylistRemove, "Remove from Up Next") { onToggleUpNext(); onDismiss() }
            } else {
                SheetAction(Icons.AutoMirrored.Rounded.PlaylistAdd, "Add to Up Next") { onToggleUpNext(); onDismiss() }
            }
            SheetAction(Icons.Outlined.LibraryAdd, "Add to playlist…") { onAddToPlaylist(); onDismiss() }
            SheetAction(
                icon = when (downloadAction) {
                    DownloadMenuAction.DOWNLOAD -> Icons.Outlined.Download
                    DownloadMenuAction.REMOVE -> Icons.Outlined.DeleteOutline
                    DownloadMenuAction.CANCEL -> Icons.Outlined.Cancel
                },
                label = downloadMenuLabel(item.downloadState),
            ) { onDownload(); onDismiss() }
            SheetAction(
                icon = if (starred) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                label = if (starred) "Unstar" else "Star",
            ) { onToggleStar(); onDismiss() }
            SheetAction(Icons.Outlined.Share, "Share") { onShare(); onDismiss() }
            SheetAction(Icons.Outlined.Podcasts, "Go to podcast") { onGoToPodcast(); onDismiss() }
        }
    }
}

@Composable
private fun SheetAction(
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val tint = if (enabled) AppTheme.colors.textPrimary else AppTheme.colors.textSecondary
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = Spacing.m, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(Spacing.m))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = tint)
    }
}

/** ACTION_SEND chooser with "<episode title> — <podcast title>\n<enclosureUrl>" (PLAN.md §8.2). */
internal fun shareEpisodeIntent(item: EpisodeListItem): Intent {
    val text = "${item.episode.title.ifBlank { "Untitled episode" }} — ${item.podcastTitle}\n" +
        item.episode.enclosureUrl
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    return Intent.createChooser(send, null)
}

@ThemePreviews
@Composable
private fun LatestPreview() {
    AppTheme {
        Surface {
            LatestContent(
                state = LatestUiState(items = previewEpisodeItems(), loaded = true, now = PREVIEW_NOW),
                onOpenSettings = {}, onOpenPodcast = {}, onOpenEpisode = {},
                onFilterSelected = {}, onRefresh = {}, onTogglePlay = {},
                onToggleUpNext = {}, onTogglePlayed = {}, onToggleStar = {}, onShare = {},
            )
        }
    }
}

@ThemePreviews
@Composable
private fun LatestEmptyPreview() {
    AppTheme {
        Surface {
            LatestContent(
                state = LatestUiState(loaded = true, now = PREVIEW_NOW),
                onOpenSettings = {}, onOpenPodcast = {}, onOpenEpisode = {},
                onFilterSelected = {}, onRefresh = {}, onTogglePlay = {},
                onToggleUpNext = {}, onTogglePlayed = {}, onToggleStar = {}, onShare = {},
            )
        }
    }
}

@ThemePreviews
@Composable
private fun LatestFilteredEmptyPreview() {
    AppTheme {
        Surface {
            LatestContent(
                state = LatestUiState(filter = LatestFilter.UNPLAYED, loaded = true, now = PREVIEW_NOW),
                onOpenSettings = {}, onOpenPodcast = {}, onOpenEpisode = {},
                onFilterSelected = {}, onRefresh = {}, onTogglePlay = {},
                onToggleUpNext = {}, onTogglePlayed = {}, onToggleStar = {}, onShare = {},
            )
        }
    }
}
