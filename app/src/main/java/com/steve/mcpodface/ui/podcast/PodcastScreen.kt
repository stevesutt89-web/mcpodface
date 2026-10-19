package com.steve.mcpodface.ui.podcast

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.steve.mcpodface.data.db.SortOrder
import com.steve.mcpodface.ui.components.BackButton
import com.steve.mcpodface.ui.components.EpisodeRow
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
    var menuOpen by remember { mutableStateOf(false) }
    var showUnsubscribe by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = podcast?.title.orEmpty(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = { BackButton(onClick = onBack) },
                actions = {
                    // TODO(6.3): open search scoped to this podcast (PLAN.md §7). Disabled until then.
                    IconButton(onClick = {}, enabled = false) {
                        Icon(Icons.Outlined.Search, contentDescription = "Search this podcast")
                    }
                    if (podcast != null) {
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Outlined.MoreVert, contentDescription = "More")
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
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
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.isLoading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                podcast == null -> Text(
                    text = "Podcast not found",
                    style = MaterialTheme.typography.bodyLarge,
                    color = AppTheme.colors.textSecondary,
                    modifier = Modifier.align(Alignment.Center),
                )
                else -> PullToRefreshBox(
                    isRefreshing = state.isRefreshing,
                    onRefresh = onRefresh,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = Spacing.l),
                    ) {
                        item(key = "header") {
                            PodcastHeader(
                                podcast = podcast,
                                onSubscribe = onSubscribe,
                                onSubscribedClick = {},
                            )
                        }
                        item(key = "controls") {
                            EpisodeControls(
                                count = state.episodeCount,
                                sortOrder = state.sortOrder,
                                hidePlayed = state.hidePlayed,
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
                            EpisodeRow(
                                item = item,
                                onClick = { onOpenEpisode(item.episode.id) },
                                onPlayClick = { onPlayClick(item.episode.id) },
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
            title = { Text("Unsubscribe from ${podcast.title}?") },
            text = { Text("Episodes and listening history are kept, so re-subscribing restores them.") },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = {
                        showUnsubscribe = false
                        onUnsubscribe(false)
                    }) { Text("Unsubscribe") }
                    TextButton(onClick = {
                        showUnsubscribe = false
                        onUnsubscribe(true)
                    }) { Text("Unsubscribe and delete downloads") }
                }
            },
            dismissButton = {
                TextButton(onClick = { showUnsubscribe = false }) { Text("Cancel") }
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
        TextButton(onClick = onToggleSort) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.Sort,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(Spacing.xs))
            Text(if (sortOrder == SortOrder.NEWEST_FIRST) "Newest first" else "Oldest first")
        }
        Spacer(Modifier.width(Spacing.xs))
        FilterChip(
            selected = hidePlayed,
            onClick = { onHidePlayedChange(!hidePlayed) },
            label = { Text("Hide played") },
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
