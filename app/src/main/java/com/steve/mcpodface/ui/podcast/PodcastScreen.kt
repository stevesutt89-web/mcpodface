package com.steve.mcpodface.ui.podcast

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kyant.shapes.RoundedRectangle
import com.steve.mcpodface.data.db.SortOrder
import com.steve.mcpodface.ui.components.BackButton
import com.steve.mcpodface.ui.components.EpisodeRow
import com.steve.mcpodface.ui.components.GlassTopBarScaffold
import com.steve.mcpodface.ui.components.glass.ArtworkAmbience
import com.steve.mcpodface.ui.components.glass.GlassButton
import com.steve.mcpodface.ui.components.glass.GlassChip
import com.steve.mcpodface.ui.components.glass.GlassIconButton
import com.steve.mcpodface.ui.components.glass.GlassPage
import com.steve.mcpodface.ui.components.glass.GlassStyle
import com.steve.mcpodface.ui.components.glass.rememberArtworkAccent
import com.steve.mcpodface.ui.components.previewEpisodeItems
import com.steve.mcpodface.ui.theme.AppTheme
import com.steve.mcpodface.ui.theme.Spacing
import com.steve.mcpodface.ui.theme.ThemePreviews

@Composable
fun PodcastScreen(podcastId: Long, onBack: () -> Unit, onOpenEpisode: (Long) -> Unit) {
    // The ViewModel reads podcastId from the route; the key keeps one instance per podcast on the back stack.
    val viewModel: PodcastViewModel = hiltViewModel(key = "podcast-$podcastId")
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    PodcastContent(
        state = state,
        onBack = onBack,
        onOpenEpisode = onOpenEpisode,
        onPlayClick = viewModel::togglePlayPause,
        onSubscribe = viewModel::subscribe,
        onUnsubscribe = viewModel::unsubscribe,
        onMarkAllPlayed = viewModel::markAllPlayed,
        onToggleSort = viewModel::toggleSortOrder,
        onHidePlayedChange = viewModel::onHidePlayedChange,
        onRefresh = viewModel::refresh,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PodcastContent(
    state: PodcastUiState,
    onBack: () -> Unit,
    onOpenEpisode: (Long) -> Unit,
    onPlayClick: (Long) -> Unit,
    onSubscribe: () -> Unit,
    onUnsubscribe: (deleteDownloads: Boolean) -> Unit,
    onMarkAllPlayed: () -> Unit,
    onToggleSort: () -> Unit,
    onHidePlayedChange: (Boolean) -> Unit,
    onRefresh: () -> Unit,
) {
    val podcast = state.podcast
    // Artwork accent for the Subscribe button, sort toggle and Hide played chip (PLAN.md §11).
    val artworkAccent by rememberArtworkAccent(podcast?.artworkUrl)
    val accent: Color = artworkAccent ?: AppTheme.colors.accent
    var menuOpen by remember { mutableStateOf(false) }
    var showUnsubscribe by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    // The top bar shows the podcast title only once the header has scrolled away.
    val headerScrolledAway by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }

    GlassPage(background = { ArtworkAmbience(podcast?.artworkUrl, fadeTo = 0.55f) }) {
        GlassTopBarScaffold(
            title = if (headerScrolledAway) podcast?.title.orEmpty() else "",
            background = Color.Transparent,
            navigationIcon = { BackButton(onClick = onBack) },
            actions = {
                // TODO(6.3): open search scoped to this podcast (PLAN.md §7). Disabled until then.
                GlassIconButton(
                    icon = Icons.Rounded.Search,
                    contentDescription = "Search this podcast",
                    onClick = {},
                    enabled = false,
                    size = 40.dp,
                    iconSize = 20.dp,
                )
                if (podcast != null) {
                    Box {
                        GlassIconButton(
                            icon = Icons.Rounded.MoreVert,
                            contentDescription = "More",
                            onClick = { menuOpen = true },
                            size = 40.dp,
                            iconSize = 20.dp,
                        )
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false },
                            shape = RoundedRectangle(20.dp),
                        ) {
                            DropdownMenuItem(
                                text = { Text("Mark all played") },
                                onClick = {
                                    menuOpen = false
                                    onMarkAllPlayed()
                                },
                            )
                            if (state.isSubscribed) {
                                DropdownMenuItem(
                                    text = { Text("Unsubscribe") },
                                    onClick = {
                                        menuOpen = false
                                        showUnsubscribe = true
                                    },
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.width(8.dp))
            },
        ) { padding ->
            when {
                state.isLoading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                podcast == null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Text(
                        text = "Podcast not found",
                        style = MaterialTheme.typography.bodyLarge,
                        color = AppTheme.colors.textSecondary,
                    )
                }
                else -> PullToRefreshBox(
                    isRefreshing = state.isRefreshing,
                    onRefresh = onRefresh,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        // Content scrolls under the glass top bar and the floating chrome.
                        contentPadding = padding,
                    ) {
                        item(key = "header") {
                            PodcastHeader(
                                podcast = podcast,
                                onSubscribe = onSubscribe,
                                onSubscribedClick = {},
                                accent = accent,
                            )
                        }
                        item(key = "controls") {
                            EpisodeControls(
                                count = state.episodeCount,
                                sortOrder = state.sortOrder,
                                hidePlayed = state.hidePlayed,
                                accent = accent,
                                onToggleSort = onToggleSort,
                                onHidePlayedChange = onHidePlayedChange,
                            )
                        }
                        if (state.episodes.isEmpty()) {
                            item(key = "empty") {
                                Text(
                                    text = if (state.hidePlayed) "Every episode is played" else "No episodes yet",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = AppTheme.colors.textSecondary,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = Spacing.m, vertical = Spacing.l),
                                )
                            }
                        }
                        items(state.episodes, key = { it.episode.id }) { item ->
                            // Episode rows stay solid so they read clearly over the artwork ambience.
                            EpisodeRow(
                                item = item,
                                onClick = { onOpenEpisode(item.episode.id) },
                                onPlayClick = { onPlayClick(item.episode.id) },
                                modifier = Modifier.background(MaterialTheme.colorScheme.background),
                                isPlaying = state.isPlaying && state.nowPlayingEpisodeId == item.episode.id,
                                showPodcastTitle = false,
                                now = state.now,
                            )
                        }
                    }
                }
            }
        }
    }

    if (showUnsubscribe && podcast != null) {
        AlertDialog(
            onDismissRequest = { showUnsubscribe = false },
            shape = RoundedRectangle(28.dp),
            title = { Text("Unsubscribe from ${podcast.title}?") },
            text = { Text("Episodes and listening history are kept, so re-subscribing restores them.") },
            confirmButton = {
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(Spacing.s),
                ) {
                    GlassButton(onClick = {
                        showUnsubscribe = false
                        onUnsubscribe(false)
                    }) { Text("Unsubscribe") }
                    GlassButton(onClick = {
                        showUnsubscribe = false
                        onUnsubscribe(true)
                    }) { Text("Unsubscribe and delete downloads") }
                }
            },
            dismissButton = {
                GlassButton(onClick = { showUnsubscribe = false }) { Text("Cancel") }
            },
        )
    }
}

/** Episode count, sort toggle and Hide played filter, between the header and the list (PLAN.md §8.4). */
@Composable
private fun EpisodeControls(
    count: Int,
    sortOrder: SortOrder,
    hidePlayed: Boolean,
    accent: Color,
    onToggleSort: () -> Unit,
    onHidePlayedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.m, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = episodeCountLabel(count),
            style = MaterialTheme.typography.bodySmall,
            color = AppTheme.colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        GlassButton(
            onClick = onToggleSort,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.Sort,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = if (sortOrder == SortOrder.NEWEST_FIRST) "Newest first" else "Oldest first",
                color = accent,
            )
        }
        Spacer(Modifier.width(Spacing.xs))
        GlassChip(
            label = "Hide played",
            selected = hidePlayed,
            onClick = { onHidePlayedChange(!hidePlayed) },
            accent = accent,
        )
    }
}

private fun episodeCountLabel(count: Int): String = if (count == 1) "1 episode" else "$count episodes"

// ---- Previews -------------------------------------------------------------------------------

private const val PREVIEW_NOW = 1_792_411_200_000L // 2026-10-19T12:00Z

@ThemePreviews
@Composable
private fun PodcastContentSubscribedPreview() {
    AppTheme {
        Surface {
            PodcastContent(
                state = PodcastUiState(
                    loaded = true,
                    podcast = previewPodcast(subscribed = true),
                    episodeCount = 7,
                    episodes = previewEpisodeItems(),
                    nowPlayingEpisodeId = 2L,
                    isPlaying = true,
                    now = PREVIEW_NOW,
                ),
                onBack = {},
                onOpenEpisode = {},
                onPlayClick = {},
                onSubscribe = {},
                onUnsubscribe = {},
                onMarkAllPlayed = {},
                onToggleSort = {},
                onHidePlayedChange = {},
                onRefresh = {},
            )
        }
    }
}

@ThemePreviews
@Composable
private fun PodcastContentNotSubscribedPreview() {
    AppTheme {
        Surface {
            PodcastContent(
                state = PodcastUiState(
                    loaded = true,
                    podcast = previewPodcast(subscribed = false),
                    sortOrder = SortOrder.OLDEST_FIRST,
                    hidePlayed = true,
                    episodeCount = 7,
                    episodes = previewEpisodeItems().filter { it.episode.id != 3L },
                    now = PREVIEW_NOW,
                ),
                onBack = {},
                onOpenEpisode = {},
                onPlayClick = {},
                onSubscribe = {},
                onUnsubscribe = {},
                onMarkAllPlayed = {},
                onToggleSort = {},
                onHidePlayedChange = {},
                onRefresh = {},
            )
        }
    }
}
