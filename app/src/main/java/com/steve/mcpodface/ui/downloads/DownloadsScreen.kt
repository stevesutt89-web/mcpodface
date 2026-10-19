package com.steve.mcpodface.ui.downloads

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp


import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import com.steve.mcpodface.data.db.DownloadEntity
import com.steve.mcpodface.data.db.DownloadListItem
import com.steve.mcpodface.data.db.DownloadSource
import com.steve.mcpodface.data.db.DownloadState
import com.steve.mcpodface.data.db.FeedType
import com.steve.mcpodface.data.db.PlayState
import com.steve.mcpodface.data.db.PodcastDownloadSize
import com.steve.mcpodface.ui.components.Artwork
import com.steve.mcpodface.ui.components.LargeTitleScaffold
import com.steve.mcpodface.ui.components.BackButton
import com.steve.mcpodface.ui.components.formatBytes
import com.steve.mcpodface.ui.components.glass.GlassButton
import com.steve.mcpodface.ui.components.glass.GlassChip
import com.steve.mcpodface.ui.components.glass.GlassIconButton
import com.steve.mcpodface.ui.components.glass.GlassPage
import com.steve.mcpodface.ui.components.glass.GlassStyle
import com.steve.mcpodface.ui.components.glass.glass
import com.steve.mcpodface.ui.components.glass.glassCard
import com.steve.mcpodface.ui.theme.AppTheme
import com.steve.mcpodface.ui.theme.Spacing
import com.steve.mcpodface.ui.theme.ThemePreviews

private const val PREVIEW_NOW = 1_792_411_200_000L // 2026-10-19T12:00Z

/**
 * Downloads (PLAN.md §8.7), opened from the Playlists screen. All changes go through
 * DownloadManager via the view model.
 */
@Composable
fun DownloadsScreen(onBack: () -> Unit = {}) {
    val viewModel: DownloadsViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    DownloadsContent(
        state = state,
        onBack = onBack,
        actions = DownloadsActions(
            onCancel = viewModel::cancel,
            onRetry = viewModel::retry,
            onDelete = viewModel::delete,
            onDeleteEpisodes = viewModel::deleteEpisodes,
            onSort = viewModel::setSort,
            onUnplayedOnly = viewModel::setUnplayedOnly,
            onStartSelection = viewModel::startSelection,
            onToggleSelected = viewModel::toggleSelected,
            onClearSelection = viewModel::clearSelection,
        ),
    )
}

private data class DownloadsActions(
    val onCancel: (Long) -> Unit = {},
    val onRetry: (Long) -> Unit = {},
    val onDelete: (Long) -> Unit = {},
    val onDeleteEpisodes: (Collection<Long>) -> Unit = {},
    val onSort: (DownloadSort) -> Unit = {},
    val onUnplayedOnly: (Boolean) -> Unit = {},
    val onStartSelection: (Long) -> Unit = {},
    val onToggleSelected: (Long) -> Unit = {},
    val onClearSelection: () -> Unit = {},
)

/** A destructive action waiting for the user's confirmation. */
private data class PendingDelete(val title: String, val message: String, val episodeIds: List<Long>)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DownloadsContent(
    state: DownloadsUiState,
    onBack: () -> Unit,
    actions: DownloadsActions,
) {
    val colors = AppTheme.colors
    var menuOpen by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<PendingDelete?>(null) }
    var headerExpanded by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val selecting = state.selected.isNotEmpty()
    val playedRows = state.allDownloaded.filter { it.item.playState == PlayState.PLAYED }
    val isEmpty = state.loaded && state.downloading.isEmpty() && state.queued.isEmpty() &&
        state.failed.isEmpty() && state.allDownloaded.isEmpty()

    GlassPage {
        LargeTitleScaffold(
            title = if (selecting) "${state.selected.size} selected" else "Downloads",
            background = Color.Transparent,
            navigationIcon = if (selecting) {
                {
                    GlassIconButton(
                        icon = Icons.Rounded.Close,
                        contentDescription = "Clear selection",
                        onClick = actions.onClearSelection,
                        size = 40.dp,
                        iconSize = 20.dp,
                        modifier = Modifier.padding(start = Spacing.s),
                    )
                }
            } else {
                { BackButton(onBack) }
            },
            actions = {
                if (selecting) {
                    GlassIconButton(
                        icon = Icons.Rounded.Delete,
                        contentDescription = "Delete selected",
                        onClick = { actions.onDeleteEpisodes(state.selected) },
                        size = 40.dp,
                        iconSize = 20.dp,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(end = Spacing.s),
                    )
                } else {
                    if (state.allDownloaded.isNotEmpty()) {
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
                                    text = { Text("Delete all played") },
                                    enabled = playedRows.isNotEmpty(),
                                    onClick = {
                                        menuOpen = false
                                        pending = pendingDelete("Delete all played?", playedRows)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Delete all downloads") },
                                    onClick = {
                                        menuOpen = false
                                        pending = pendingDelete("Delete all downloads?", state.allDownloaded)
                                    },
                                )
                            }
                        }
                    }
                }
            },
        ) { padding ->
            if (isEmpty) {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(
                        top = padding.calculateTopPadding(),
                        bottom = padding.calculateBottomPadding() + Spacing.s,
                    ),
                ) {
                    item(key = "empty") { EmptyState() }
                }
                return@LargeTitleScaffold
            }
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(
                    top = padding.calculateTopPadding(),
                    bottom = padding.calculateBottomPadding() + Spacing.s,
                ),
            ) {
                if (state.allDownloaded.isNotEmpty()) {
                    item(key = "storage") {
                        StorageHeader(
                            state = state,
                            expanded = headerExpanded,
                            onToggle = { headerExpanded = !headerExpanded },
                            onDeletePodcast = { size ->
                                val rows = state.allDownloaded.filter { it.item.podcastId == size.podcastId }
                                pending = pendingDelete("Delete ${size.podcastTitle} downloads?", rows)
                            },
                        )
                    }
                }
                if (state.downloading.isNotEmpty()) {
                    item(key = "section-downloading") { SectionHeader("Downloading") }
                    items(state.downloading, key = { "downloading-${it.download.episodeId}" }) { item ->
                        TransferRow(
                            item = item,
                            footer = { DownloadProgress(item) },
                            trailing = {
                                GlassIconButton(
                                    icon = Icons.Rounded.Cancel,
                                    contentDescription = "Cancel download",
                                    onClick = { actions.onCancel(item.download.episodeId) },
                                    size = 36.dp,
                                    iconSize = 20.dp,
                                )
                            },
                        )
                    }
                }
                if (state.queued.isNotEmpty()) {
                    item(key = "section-queued") { SectionHeader("Queued") }
                    items(state.queued, key = { "queued-${it.download.episodeId}" }) { item ->
                        val waiting = item.download.state == DownloadState.PAUSED_NETWORK
                        TransferRow(
                            item = item,
                            footer = {
                                Text(
                                    text = if (waiting) "Waiting for Wi-Fi" else "Queued",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.textSecondary,
                                )
                            },
                            trailing = {
                                GlassIconButton(
                                    icon = Icons.Rounded.Cancel,
                                    contentDescription = "Cancel download",
                                    onClick = { actions.onCancel(item.download.episodeId) },
                                    size = 36.dp,
                                    iconSize = 20.dp,
                                )
                            },
                        )
                    }
                }
                if (state.failed.isNotEmpty()) {
                    item(key = "section-failed") { SectionHeader("Failed") }
                    items(state.failed, key = { "failed-${it.download.episodeId}" }) { item ->
                        TransferRow(
                            item = item,
                            footer = {
                                Text(
                                    text = item.download.error?.takeIf { it.isNotBlank() } ?: "Download failed",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                                Row(
                                    modifier = Modifier.padding(top = Spacing.s),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    GlassButton(
                                        onClick = { actions.onRetry(item.download.episodeId) },
                                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                                    ) {
                                        Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Text("Retry")
                                    }
                                    Spacer(Modifier.width(Spacing.s))
                                    GlassIconButton(
                                        icon = Icons.Rounded.Delete,
                                        contentDescription = "Remove",
                                        onClick = { actions.onDelete(item.download.episodeId) },
                                        size = 36.dp,
                                        iconSize = 18.dp,
                                    )
                                }
                            },
                            trailing = null,
                        )
                    }
                }
                if (state.allDownloaded.isNotEmpty()) {
                    item(key = "section-downloaded") { SectionHeader("Downloaded") }
                    item(key = "downloaded-controls") {
                        DownloadedControls(
                            sort = state.sort,
                            unplayedOnly = state.unplayedOnly,
                            onSort = actions.onSort,
                            onUnplayedOnly = actions.onUnplayedOnly,
                        )
                    }
                    if (state.downloaded.isEmpty()) {
                        item(key = "downloaded-none") {
                            Text(
                                text = "No unplayed downloads",
                                style = MaterialTheme.typography.bodyLarge,
                                color = colors.textSecondary,
                                modifier = Modifier.padding(horizontal = Spacing.m, vertical = Spacing.s),
                            )
                        }
                    }
                    itemsIndexed(state.downloaded, key = { _, row -> "done-${row.item.download.episodeId}" }) { index, row ->
                        val id = row.item.download.episodeId
                        DownloadedRowItem(
                            row = row,
                            selecting = selecting,
                            selected = id in state.selected,
                            onOpenOrToggle = { actions.onToggleSelected(id) },
                            onStartSelection = { actions.onStartSelection(id) },
                            onSwipeDelete = { actions.onDelete(id) },
                            showDivider = index < state.downloaded.lastIndex,
                        )
                    }
                }
            }
        }
    }

    pending?.let { request ->
        AlertDialog(
            onDismissRequest = { pending = null },
            shape = remember { RoundedRectangle(28.dp) },
            title = { Text(request.title) },
            text = { Text(request.message) },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    actions.onDeleteEpisodes(request.episodeIds)
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pending = null }) { Text("Cancel") }
            },
        )
    }
}

private fun pendingDelete(title: String, rows: List<DownloadedRow>): PendingDelete {
    val noun = if (rows.size == 1) "episode" else "episodes"
    val size = formatBytes(rows.sumOf { it.sizeBytes })
    return PendingDelete(
        title = title,
        message = "${rows.size} $noun · $size will be removed from this phone. " +
            "Removed episodes won't download again automatically.",
        episodeIds = rows.map { it.item.download.episodeId },
    )
}

// ---- Storage header --------------------------------------------------------------------------

@Composable
private fun StorageHeader(
    state: DownloadsUiState,
    expanded: Boolean,
    onToggle: () -> Unit,
    onDeletePodcast: (PodcastDownloadSize) -> Unit,
) {
    val colors = AppTheme.colors
    val shape = remember { RoundedRectangle(24.dp) }
    val episodes = if (state.episodeCount == 1) "1 episode" else "${state.episodeCount} episodes"
    val secondary = buildList {
        add(episodes)
        state.freeBytes?.let { add("${formatBytes(it)} free") }
    }.joinToString(" · ")

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.m, vertical = Spacing.s)
            .glassCard(shape)
            .clickable(onClick = onToggle)
            .padding(Spacing.m),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Storage",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.textSecondary,
                )
                Text(
                    text = formatBytes(state.totalBytes),
                    style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
                    color = colors.textPrimary,
                )
                Text(
                    text = secondary,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                )
            }
            Icon(
                imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = if (expanded) "Hide per-podcast sizes" else "Show per-podcast sizes",
                tint = colors.textSecondary,
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(Modifier.padding(top = Spacing.s)) {
                state.podcastSizes.forEach { size ->
                    PodcastSizeRow(size = size, onDelete = { onDeletePodcast(size) })
                }
            }
        }
    }
}

@Composable
private fun PodcastSizeRow(size: PodcastDownloadSize, onDelete: () -> Unit) {
    val colors = AppTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = size.podcastTitle.ifBlank { "Untitled podcast" },
                style = MaterialTheme.typography.bodyLarge,
                color = colors.textPrimary,
                maxLines = 1,
            )
            Text(
                text = "${size.episodeCount} ${if (size.episodeCount == 1) "episode" else "episodes"} · ${formatBytes(size.totalBytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
            )
        }
        TextButton(onClick = onDelete) {
            Text("Delete", color = MaterialTheme.colorScheme.error)
        }
    }
}

// ---- Rows ------------------------------------------------------------------------------------

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = AppTheme.colors.textSecondary,
        modifier = Modifier.padding(start = Spacing.m, top = Spacing.l, bottom = Spacing.xs).semantics { heading() },
    )
}

/** Shared layout for the Downloading, Queued and Failed rows: artwork, text, then a footer or trailing control. */
@Composable
private fun TransferRow(
    item: DownloadListItem,
    footer: @Composable () -> Unit,
    trailing: (@Composable () -> Unit)?,
) {
    val colors = AppTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.m, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(url = item.artworkUrl, contentDescription = null, size = 56.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = item.podcastTitle,
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
                maxLines = 1,
            )
            Text(
                text = item.episodeTitle.ifBlank { "Untitled episode" },
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = colors.textPrimary,
                maxLines = 2,
            )
            Spacer(Modifier.height(Spacing.xs))
            footer()
        }
        if (trailing != null) {
            Spacer(Modifier.width(Spacing.s))
            trailing()
        }
    }
}

@Composable
private fun DownloadProgress(item: DownloadListItem) {
    val colors = AppTheme.colors
    val done = item.download.bytesDownloaded
    val total = item.download.totalBytes?.takeIf { it > 0 }
    if (total != null) {
        val fraction = (done.toFloat() / total).coerceIn(0f, 1f)
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxWidth(),
            color = colors.accent,
            trackColor = colors.textPrimary.copy(alpha = 0.12f),
        )
    } else {
        LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth(),
            color = colors.accent,
            trackColor = colors.textPrimary.copy(alpha = 0.12f),
        )
    }
    Text(
        text = when {
            total != null -> "${formatBytes(done)} of ${formatBytes(total)}"
            done > 0 -> "${formatBytes(done)} so far"
            else -> "Starting…"
        },
        style = MaterialTheme.typography.bodySmall,
        color = colors.textSecondary,
    )
}

@Composable
private fun DownloadedControls(
    sort: DownloadSort,
    unplayedOnly: Boolean,
    onSort: (DownloadSort) -> Unit,
    onUnplayedOnly: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = Spacing.m, vertical = Spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        GlassChip(
            label = "Date downloaded",
            selected = sort == DownloadSort.DATE_DOWNLOADED,
            onClick = { onSort(DownloadSort.DATE_DOWNLOADED) },
        )
        GlassChip(
            label = "Published",
            selected = sort == DownloadSort.PUBLISHED,
            onClick = { onSort(DownloadSort.PUBLISHED) },
        )
        GlassChip(
            label = "Podcast",
            selected = sort == DownloadSort.PODCAST,
            onClick = { onSort(DownloadSort.PODCAST) },
        )
        GlassChip(
            label = "Unplayed only",
            selected = unplayedOnly,
            onClick = { onUnplayedOnly(!unplayedOnly) },
        )
    }
}

/**
 * One Downloaded row. Swipe to delete (not while selecting). Long-press starts multi-select; while
 * selecting, a tap toggles the checkbox.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DownloadedRowItem(
    row: DownloadedRow,
    selecting: Boolean,
    selected: Boolean,
    onOpenOrToggle: () -> Unit,
    onStartSelection: () -> Unit,
    onSwipeDelete: () -> Unit,
    showDivider: Boolean,
) {
    val colors = AppTheme.colors
    val currentDelete by rememberUpdatedState(onSwipeDelete)
    val currentSelecting by rememberUpdatedState(selecting)
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            val dismissed = value != SwipeToDismissBoxValue.Settled && !currentSelecting
            if (dismissed) currentDelete()
            dismissed
        },
    )
    val item = row.item

    Column {
        SwipeToDismissBox(
            state = dismissState,
            enableDismissFromStartToEnd = !selecting,
            enableDismissFromEndToStart = !selecting,
            backgroundContent = { DeleteBackground(dismissState.dismissDirection) },
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
                    .combinedClickable(
                        onClick = { if (selecting) onOpenOrToggle() },
                        onLongClick = { if (!selecting) onStartSelection() },
                    )
                    .padding(horizontal = Spacing.m, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (selecting) {
                    Checkbox(checked = selected, onCheckedChange = null)
                    Spacer(Modifier.width(Spacing.xs))
                }
                Artwork(url = item.artworkUrl, contentDescription = null, size = 56.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "${item.podcastTitle} · ${formatBytes(row.sizeBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                        maxLines = 1,
                    )
                    Text(
                        text = item.episodeTitle.ifBlank { "Untitled episode" },
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = if (item.playState == PlayState.PLAYED) colors.textSecondary else colors.textPrimary,
                        maxLines = 2,
                    )
                    row.deletionLabel?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textSecondary,
                        )
                    }
                }
            }
        }
        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(start = Spacing.m + 56.dp + 12.dp),
                color = colors.hairline,
            )
        }
    }
}

/** Neutral rounded strip revealed while a row is swiped; the bin icon uses the error colour. */
@Composable
private fun DeleteBackground(direction: SwipeToDismissBoxValue) {
    if (direction == SwipeToDismissBoxValue.Settled) return
    val fromStart = direction == SwipeToDismissBoxValue.StartToEnd
    val shape = remember { RoundedRectangle(20.dp) }
    Box(
        Modifier
            .fillMaxSize()
            .padding(8.dp)
            .background(AppTheme.colors.surfaceGrouped, shape)
            .padding(horizontal = Spacing.l),
        contentAlignment = if (fromStart) Alignment.CenterStart else Alignment.CenterEnd,
    ) {
        Icon(
            Icons.Rounded.Delete,
            contentDescription = "Delete download",
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(24.dp),
        )
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
        Box(
            Modifier
                .size(88.dp)
                .glass(remember { Capsule() }, GlassStyle.Regular),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.DownloadForOffline,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(56.dp),
            )
        }
        Spacer(Modifier.height(Spacing.m))
        Text(
            "No downloads",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Spacing.xs))
        Text(
            "Episodes download automatically for podcasts you follow, or tap download on any episode.",
            style = MaterialTheme.typography.bodyLarge,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

// ---- Previews -------------------------------------------------------------------------------

private fun previewItem(
    id: Long,
    title: String,
    podcast: String,
    state: DownloadState,
    playState: PlayState = PlayState.UNPLAYED,
    playedAgoHours: Long? = null,
    starred: Boolean = false,
    pubDaysAgo: Long = 3,
    totalMb: Long? = 48,
    done: Long = 0,
    error: String? = null,
): DownloadListItem = DownloadListItem(
    download = DownloadEntity(
        episodeId = id,
        state = state,
        source = DownloadSource.AUTO,
        filePath = "/preview/$id.mp3",
        bytesDownloaded = if (totalMb != null) totalMb * 1024 * 1024 else done,
        totalBytes = totalMb?.let { it * 1024 * 1024 },
        downloadedAt = PREVIEW_NOW - 2 * 3_600_000L,
        error = error,
    ),
    episodeTitle = title,
    podcastId = podcast.hashCode().toLong(),
    podcastTitle = podcast,
    feedType = FeedType.EPISODIC,
    artworkUrl = null,
    pubDate = PREVIEW_NOW - pubDaysAgo * 86_400_000L,
    durationSec = 2400,
    playState = playState,
    playedAt = playedAgoHours?.let { PREVIEW_NOW - it * 3_600_000L },
    isStarred = starred,
)

private fun previewRow(item: DownloadListItem, label: String?) =
    DownloadedRow(item, item.download.totalBytes ?: 0L, label)

@ThemePreviews
@Composable
private fun DownloadsPopulatedPreview() {
    val downloading = previewItem(1, "Downloading this one", "The Daily", DownloadState.DOWNLOADING, done = 12L * 1024 * 1024, totalMb = 48)
    val queued = previewItem(2, "Queued next", "Hard Fork", DownloadState.QUEUED, totalMb = null)
    val waiting = previewItem(3, "Waiting for Wi-Fi", "Serial", DownloadState.PAUSED_NETWORK, totalMb = null)
    val failed = previewItem(4, "Failed episode", "Radiolab", DownloadState.FAILED, totalMb = null, error = "HTTP 503")
    val played = previewItem(5, "Played and ready to go", "Radiolab", DownloadState.COMPLETED, PlayState.PLAYED, playedAgoHours = 30)
    val fresh = previewItem(6, "Fresh unplayed episode", "Hard Fork", DownloadState.COMPLETED, pubDaysAgo = 1, totalMb = 62)
    val starred = previewItem(7, "Starred keeper", "The Daily", DownloadState.COMPLETED, starred = true, totalMb = 120)
    val older = previewItem(8, "Older back catalogue", "Serial", DownloadState.COMPLETED, pubDaysAgo = 40, totalMb = 210)
    val downloaded = listOf(
        previewRow(played, formatDeletionLabel(DeletionEta.Scheduled(PREVIEW_NOW + 18 * 3_600_000L, DeletionEta.Reason.PLAYED), PREVIEW_NOW)),
        previewRow(fresh, null),
        previewRow(starred, formatDeletionLabel(DeletionEta.Kept(), PREVIEW_NOW)),
        previewRow(older, formatDeletionLabel(DeletionEta.Scheduled(PREVIEW_NOW + 3 * 86_400_000L, DeletionEta.Reason.TOO_OLD, 30), PREVIEW_NOW)),
    )
    val state = DownloadsUiState(
        loaded = true,
        downloading = listOf(downloading),
        queued = listOf(queued, waiting),
        failed = listOf(failed),
        downloaded = arrangeDownloaded(downloaded, DownloadSort.DATE_DOWNLOADED, unplayedOnly = false),
        allDownloaded = downloaded,
        episodeCount = downloaded.size,
        totalBytes = downloaded.sumOf { it.sizeBytes },
        freeBytes = 23L * 1024 * 1024 * 1024,
        podcastSizes = listOf(
            PodcastDownloadSize(1, "Serial", 2, 330L * 1024 * 1024),
            PodcastDownloadSize(2, "Radiolab", 1, 48L * 1024 * 1024),
        ),
        now = PREVIEW_NOW,
    )
    AppTheme {
        Surface {
            DownloadsContent(state = state, onBack = {}, actions = DownloadsActions())
        }
    }
}

@ThemePreviews
@Composable
private fun DownloadsEmptyPreview() {
    AppTheme {
        Surface {
            DownloadsContent(
                state = DownloadsUiState(loaded = true, now = PREVIEW_NOW),
                onBack = {},
                actions = DownloadsActions(),
            )
        }
    }
}
