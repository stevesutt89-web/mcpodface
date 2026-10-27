package com.steve.mcpodface.ui.library

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Podcasts
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import com.kyant.shapes.RoundedRectangle
import com.steve.mcpodface.data.db.LibraryPodcast
import com.steve.mcpodface.data.db.PodcastEntity
import com.steve.mcpodface.data.prefs.LibraryLayout
import com.steve.mcpodface.data.prefs.LibrarySort
import com.steve.mcpodface.ui.components.LargeTitleScaffold
import com.steve.mcpodface.ui.components.SearchButton
import com.steve.mcpodface.ui.components.SettingsButton
import com.steve.mcpodface.ui.components.glass.GlassIconButton
import com.steve.mcpodface.ui.components.glass.GlassPage
import com.steve.mcpodface.ui.components.glass.GlassPressSpring
import com.steve.mcpodface.ui.podcastsettings.PodcastSettingsSheet
import com.steve.mcpodface.ui.theme.AppTheme
import com.steve.mcpodface.ui.theme.Spacing
import com.steve.mcpodface.ui.theme.ThemePreviews

private val GRID_GAP = 12.dp
private val TILE_SHAPE_RADIUS = 16.dp
private val LIST_ARTWORK_RADIUS = 12.dp
private val fullRow: LazyGridItemSpanScope.() -> GridItemSpan = { GridItemSpan(maxLineSpan) }

/**
 * Library tab (PLAN.md §8.3): the subscriptions as a 3-column artwork grid or a list, with sort and
 * layout controls. Starred, History, Stats and playlists live on the Playlists tab (PLAN.md §8.11).
 */
@Composable
fun LibraryScreen(
    onOpenSettings: () -> Unit,
    onOpenPodcast: (Long) -> Unit,
    onSearch: () -> Unit,
) {
    val viewModel: LibraryViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()

    LibraryContent(
        state = state,
        onOpenSettings = onOpenSettings,
        onOpenPodcast = onOpenPodcast,
        onSearch = onSearch,
        onSortSelected = viewModel::setSort,
        onLayoutSelected = viewModel::setLayout,
        onRefresh = viewModel::refresh,
        onUnsubscribe = viewModel::unsubscribe,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryContent(
    state: LibraryUiState,
    onOpenSettings: () -> Unit,
    onOpenPodcast: (Long) -> Unit,
    onSearch: () -> Unit,
    onSortSelected: (LibrarySort) -> Unit,
    onLayoutSelected: (LibraryLayout) -> Unit,
    onRefresh: () -> Unit,
    onUnsubscribe: (podcastId: Long, deleteDownloads: Boolean) -> Unit,
) {
    var sortMenuOpen by rememberSaveable { mutableStateOf(false) }
    var actionPodcastId by rememberSaveable { mutableStateOf<Long?>(null) }
    var settingsPodcastId by rememberSaveable { mutableStateOf<Long?>(null) }
    var unsubscribeId by rememberSaveable { mutableStateOf<Long?>(null) }
    val isGrid = state.layout == LibraryLayout.GRID
    val actionPodcast = actionPodcastId?.let { id -> state.podcasts.firstOrNull { it.podcast.id == id } }
    val unsubscribePodcast = unsubscribeId?.let { id -> state.podcasts.firstOrNull { it.podcast.id == id } }

    GlassPage {
        LargeTitleScaffold(
            title = "Library",
            background = Color.Transparent,
            actions = {
                SearchButton(onSearch)
                Box(Modifier.padding(end = Spacing.xs)) {
                    GlassIconButton(
                        icon = Icons.AutoMirrored.Outlined.Sort,
                        contentDescription = "Sort",
                        onClick = { sortMenuOpen = true },
                        size = 40.dp,
                        iconSize = 20.dp,
                    )
                    DropdownMenu(
                        expanded = sortMenuOpen,
                        onDismissRequest = { sortMenuOpen = false },
                        shape = RoundedRectangle(20.dp),
                    ) {
                        LibrarySort.entries.forEach { sort ->
                            DropdownMenuItem(
                                text = { Text(sort.label()) },
                                leadingIcon = { RadioButton(selected = sort == state.sort, onClick = null) },
                                onClick = {
                                    onSortSelected(sort)
                                    sortMenuOpen = false
                                },
                            )
                        }
                    }
                }
                GlassIconButton(
                    icon = if (isGrid) Icons.AutoMirrored.Outlined.ViewList else Icons.Outlined.GridView,
                    contentDescription = if (isGrid) "Show as list" else "Show as grid",
                    onClick = { onLayoutSelected(if (isGrid) LibraryLayout.LIST else LibraryLayout.GRID) },
                    size = 40.dp,
                    iconSize = 20.dp,
                    modifier = Modifier.padding(end = Spacing.xs),
                )
                SettingsButton(onOpenSettings)
            },
        ) { padding ->
            val layoutDirection = LocalLayoutDirection.current
            val pullState = rememberPullToRefreshState()
            PullToRefreshBox(
                isRefreshing = state.refreshing,
                onRefresh = onRefresh,
                modifier = Modifier.fillMaxSize(),
                state = pullState,
                indicator = {
                    // The bar sits over the content, so drop the indicator below it.
                    PullToRefreshDefaults.Indicator(
                        state = pullState,
                        isRefreshing = state.refreshing,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = padding.calculateTopPadding()),
                    )
                },
            ) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(if (isGrid) 3 else 1),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = padding.calculateStartPadding(layoutDirection) + Spacing.m,
                        top = padding.calculateTopPadding() + Spacing.s,
                        end = padding.calculateEndPadding(layoutDirection) + Spacing.m,
                        bottom = padding.calculateBottomPadding(),
                    ),
                    horizontalArrangement = Arrangement.spacedBy(if (isGrid) GRID_GAP else 0.dp),
                    verticalArrangement = Arrangement.spacedBy(if (isGrid) GRID_GAP else Spacing.xs),
                ) {
                    if (state.loaded && state.podcasts.isEmpty()) {
                        item(key = "empty", span = fullRow) { EmptyState() }
                    }
                    items(state.podcasts, key = { it.podcast.id }) { library ->
                        val onClick = { onOpenPodcast(library.podcast.id) }
                        val onLongClick = { actionPodcastId = library.podcast.id }
                        if (isGrid) {
                            PodcastGridCell(library, onClick = onClick, onLongClick = onLongClick)
                        } else {
                            PodcastListRow(library, onClick = onClick, onLongClick = onLongClick)
                        }
                    }
                }
            }
        }
    }

    if (actionPodcast != null) {
        PodcastActionSheet(
            title = actionPodcast.podcast.title,
            onDismiss = { actionPodcastId = null },
            onSettings = {
                settingsPodcastId = actionPodcast.podcast.id
                actionPodcastId = null
            },
            onUnsubscribe = {
                unsubscribeId = actionPodcast.podcast.id
                actionPodcastId = null
            },
        )
    }
    settingsPodcastId?.let { id ->
        PodcastSettingsSheet(podcastId = id, onDismiss = { settingsPodcastId = null })
    }
    if (unsubscribePodcast != null) {
        UnsubscribeDialog(
            title = unsubscribePodcast.podcast.title,
            onDismiss = { unsubscribeId = null },
            onConfirm = { deleteDownloads ->
                onUnsubscribe(unsubscribePodcast.podcast.id, deleteDownloads)
                unsubscribeId = null
            },
        )
    }
}

private fun LibrarySort.label(): String = when (this) {
    LibrarySort.RECENTLY_UPDATED -> "Recently updated"
    LibrarySort.ALPHABETICAL -> "A–Z"
    LibrarySort.RECENTLY_ADDED -> "Recently added"
}

@Composable
private fun PodcastGridCell(item: LibraryPodcast, onClick: () -> Unit, onLongClick: () -> Unit) {
    val shape = remember { RoundedRectangle(TILE_SHAPE_RADIUS) }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, GlassPressSpring, label = "tilePress")
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                // Soft lift: the shadow follows the same continuous shape as the artwork.
                this.shape = shape
                shadowElevation = 6.dp.toPx()
                clip = false
            }
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick,
            ),
    ) {
        // Square artwork at the cell width; the title is carried by contentDescription only.
        ShapedArtwork(
            url = item.podcast.artworkUrl,
            contentDescription = item.podcast.title,
            shape = shape,
            modifier = Modifier.size(maxWidth),
        )
    }
}

@Composable
private fun PodcastListRow(item: LibraryPodcast, onClick: () -> Unit, onLongClick: () -> Unit) {
    val colors = AppTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ShapedArtwork(
            url = item.podcast.artworkUrl,
            contentDescription = null,
            shape = remember { RoundedRectangle(LIST_ARTWORK_RADIUS) },
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.width(Spacing.m))
        Column(Modifier.weight(1f)) {
            Text(
                item.podcast.title,
                style = MaterialTheme.typography.titleMedium,
                color = colors.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            item.podcast.author?.takeIf { it.isNotBlank() }?.let { author ->
                Text(
                    author,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Podcast artwork clipped to [shape]. The shared [com.steve.mcpodface.ui.components.Artwork] only
 * knows its fixed corner, so tiles that need continuous corners draw their own frame. Loads at the
 * fixed Library size ([libraryArtworkRequest]), which [LibraryArtworkWarmer] decodes into memory
 * at app start, so subscribed covers show at once. The placeholder icon sits behind the image
 * rather than in a subcomposition, which keeps grid scrolling cheap.
 */
@Composable
internal fun ShapedArtwork(
    url: String?,
    contentDescription: String?,
    shape: Shape,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.clip(shape).background(AppTheme.colors.surfaceGrouped),
        contentAlignment = Alignment.Center,
    ) {
        ArtworkPlaceholderIcon()
        if (!url.isNullOrBlank()) {
            val context = LocalPlatformContext.current
            AsyncImage(
                model = remember(url) { libraryArtworkRequest(context, url) },
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

@Composable
private fun ArtworkPlaceholderIcon() {
    Icon(
        imageVector = Icons.Outlined.Podcasts,
        contentDescription = null,
        tint = AppTheme.colors.textSecondary.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxSize(0.45f),
    )
}

@Composable
private fun EmptyState() {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.l, vertical = Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("No podcasts yet", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.size(Spacing.xs))
        Text(
            "Find shows in the Search tab.",
            style = MaterialTheme.typography.bodySmall,
            color = AppTheme.colors.textSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

/** Long-press menu for a podcast: per-podcast settings and unsubscribe. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PodcastActionSheet(
    title: String,
    onDismiss: () -> Unit,
    onSettings: () -> Unit,
    onUnsubscribe: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.padding(bottom = Spacing.l)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = Spacing.m, vertical = Spacing.s),
            )
            SheetAction(Icons.Outlined.Settings, "Podcast settings", onClick = onSettings)
            SheetAction(Icons.Outlined.RemoveCircleOutline, "Unsubscribe", onClick = onUnsubscribe)
        }
    }
}

@Composable
private fun UnsubscribeDialog(
    title: String,
    onDismiss: () -> Unit,
    onConfirm: (deleteDownloads: Boolean) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Unsubscribe from $title?") },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = { onConfirm(false) }) { Text("Unsubscribe") }
                TextButton(onClick = { onConfirm(true) }) { Text("Unsubscribe and delete downloads") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
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

// ---- Previews -------------------------------------------------------------------------------

private fun previewLibrary(): List<LibraryPodcast> = listOf(
    previewPodcast(1, "Radiolab", unplayed = 3, lastNew = 1_792_411_200_000L),
    previewPodcast(2, "The Daily", unplayed = 120, lastNew = 1_792_300_000_000L),
    previewPodcast(3, "Hard Fork", unplayed = 0, lastNew = 1_792_000_000_000L),
    previewPodcast(4, "Serial", unplayed = 12, lastNew = null),
    previewPodcast(5, "Planet Money", unplayed = 1, lastNew = 1_791_000_000_000L),
    previewPodcast(6, "99% Invisible", unplayed = 0, lastNew = null),
)

private fun previewPodcast(id: Long, title: String, unplayed: Int, lastNew: Long?): LibraryPodcast =
    LibraryPodcast(
        podcast = PodcastEntity(
            id = id,
            feedUrl = "https://example.invalid/$id",
            title = title,
            author = "Host name",
            isSubscribed = true,
            subscribedAt = 0L,
            lastNewEpisodeAt = lastNew,
        ),
        unplayedCount = unplayed,
    )

@ThemePreviews
@Composable
private fun LibraryGridPreview() {
    AppTheme {
        Surface {
            LibraryContent(
                state = LibraryUiState(podcasts = previewLibrary(), loaded = true),
                onOpenSettings = {}, onOpenPodcast = {}, onSearch = {},
                onSortSelected = {}, onLayoutSelected = {}, onRefresh = {}, onUnsubscribe = { _, _ -> },
            )
        }
    }
}

@ThemePreviews
@Composable
private fun LibraryListPreview() {
    AppTheme {
        Surface {
            LibraryContent(
                state = LibraryUiState(
                    podcasts = previewLibrary(),
                    layout = LibraryLayout.LIST,
                    loaded = true,
                ),
                onOpenSettings = {}, onOpenPodcast = {}, onSearch = {},
                onSortSelected = {}, onLayoutSelected = {}, onRefresh = {}, onUnsubscribe = { _, _ -> },
            )
        }
    }
}

@ThemePreviews
@Composable
private fun LibraryEmptyPreview() {
    AppTheme {
        Surface {
            LibraryContent(
                state = LibraryUiState(loaded = true),
                onOpenSettings = {}, onOpenPodcast = {}, onSearch = {},
                onSortSelected = {}, onLayoutSelected = {}, onRefresh = {}, onUnsubscribe = { _, _ -> },
            )
        }
    }
}
