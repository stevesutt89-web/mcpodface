package com.steve.mcpodface.ui.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import com.steve.mcpodface.data.db.EpisodeListItem
import com.steve.mcpodface.data.db.HistoryItem
import com.steve.mcpodface.ui.components.BackButton
import com.steve.mcpodface.ui.components.EpisodeRow
import com.steve.mcpodface.ui.components.formatRelativeDate
import com.steve.mcpodface.ui.components.GlassTopBarScaffold
import com.steve.mcpodface.ui.components.glass.GlassIconButton
import com.steve.mcpodface.ui.components.glass.GlassPage
import com.steve.mcpodface.ui.components.glass.GlassStyle
import com.steve.mcpodface.ui.components.glass.LocalGlassBackdrop
import com.steve.mcpodface.ui.components.glass.glass
import com.steve.mcpodface.ui.components.previewEpisodeItems
import com.steve.mcpodface.ui.theme.AppTheme
import com.steve.mcpodface.ui.theme.Spacing
import com.steve.mcpodface.ui.theme.ThemePreviews

private const val PREVIEW_NOW = 1_792_411_200_000L // 2026-10-19T12:00Z
private const val HOUR_MS = 3_600_000L

/** Listening history (PLAN.md §8.3): episodes grouped by when they were last played. */
@Composable
fun HistoryScreen(onBack: () -> Unit, onOpenEpisode: (Long) -> Unit) {
    val viewModel: HistoryViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()

    HistoryContent(
        state = state,
        onBack = onBack,
        onOpenEpisode = onOpenEpisode,
        onTogglePlay = viewModel::togglePlay,
        onClearHistory = viewModel::clearHistory,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryContent(
    state: HistoryUiState,
    onBack: () -> Unit,
    onOpenEpisode: (Long) -> Unit,
    onTogglePlay: (Long) -> Unit,
    onClearHistory: () -> Unit,
) {
    var showClearDialog by rememberSaveable { mutableStateOf(false) }
    val hasHistory = state.sections.isNotEmpty()

    GlassPage {
        GlassTopBarScaffold(
            title = "History",
            background = Color.Transparent,
            navigationIcon = { BackButton(onClick = onBack) },
            actions = {
                HistoryOverflowMenu(
                    enabled = hasHistory,
                    onClearHistory = { showClearDialog = true },
                )
            },
        ) { padding ->
            if (state.loaded && !hasHistory) {
                EmptyState(Modifier.fillMaxSize().padding(padding))
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        top = padding.calculateTopPadding(),
                        bottom = padding.calculateBottomPadding() + Spacing.s,
                    ),
                ) {
                    state.sections.forEach { section ->
                        item(key = "header-${section.title}") {
                            Text(
                                text = section.title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = AppTheme.colors.textSecondary,
                                modifier = Modifier.padding(
                                    start = Spacing.m,
                                    end = Spacing.m,
                                    top = Spacing.m,
                                    bottom = Spacing.xs,
                                ),
                            )
                        }
                        itemsIndexed(section.items, key = { _, item -> item.episode.id }) { index, item ->
                            val episodeId = item.episode.id
                            EpisodeRow(
                                item = item.toRowItem(),
                                onClick = { onOpenEpisode(episodeId) },
                                onPlayClick = { onTogglePlay(episodeId) },
                                isPlaying = state.nowPlaying?.let { it.episodeId == episodeId && it.isPlaying } ?: false,
                                showPodcastTitle = true,
                                now = state.now,
                                dateLabel = "Played " + formatRelativeDate(item.lastListenedAt, state.now).replaceFirstChar { it.lowercase() },
                            )
                            if (index < section.items.lastIndex) {
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

    if (showClearDialog) {
        CompositionLocalProvider(LocalGlassBackdrop provides null) {
            AlertDialog(
                onDismissRequest = { showClearDialog = false },
                title = { Text("Clear history?") },
                text = {
                    Text(
                        "This removes your listening history and time-listened stats. " +
                            "Episodes and their play progress are not changed.",
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        showClearDialog = false
                        onClearHistory()
                    }) { Text("Clear") }
                },
                dismissButton = {
                    TextButton(onClick = { showClearDialog = false }) { Text("Cancel") }
                },
            )
        }
    }
}

@Composable
private fun HistoryOverflowMenu(enabled: Boolean, onClearHistory: () -> Unit) {
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    Box {
        GlassIconButton(
            icon = Icons.Rounded.MoreVert,
            contentDescription = "More",
            onClick = { menuOpen = true },
            modifier = Modifier.padding(end = Spacing.s),
            size = 40.dp,
            iconSize = 20.dp,
        )
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            shape = RoundedRectangle(20.dp),
        ) {
            DropdownMenuItem(
                text = { Text("Clear history") },
                enabled = enabled,
                onClick = {
                    menuOpen = false
                    onClearHistory()
                },
            )
        }
    }
}

/** Maps a history row onto the shared [EpisodeRow]. */
private fun HistoryItem.toRowItem(): EpisodeListItem = EpisodeListItem(
    episode = episode,
    podcastTitle = podcastTitle,
    podcastArtworkUrl = podcastArtworkUrl,
    downloadState = downloadState,
    downloadBytes = downloadBytes,
    inQueue = inQueue,
)

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Box(modifier.padding(horizontal = Spacing.l), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Box(
                Modifier
                    .size(88.dp)
                    .glass(remember { Capsule() }, GlassStyle.Regular),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.History,
                    contentDescription = null,
                    tint = AppTheme.colors.accent,
                    modifier = Modifier.size(56.dp),
                )
            }
            Spacer(Modifier.height(Spacing.m))
            Text(
                "Nothing played yet",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(Spacing.xs))
            Text(
                "Episodes you listen to show up here.",
                style = MaterialTheme.typography.bodyLarge,
                color = AppTheme.colors.textSecondary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

// ---- Previews -------------------------------------------------------------------------------

/** Sample history spread over the four sections: a few hours, a day, several days, and weeks. */
private fun previewHistory(): List<HistoryItem> = previewEpisodeItems().mapIndexed { index, item ->
    HistoryItem(
        episode = item.episode,
        podcastTitle = item.podcastTitle,
        podcastArtworkUrl = item.podcastArtworkUrl,
        downloadState = item.downloadState,
        downloadBytes = item.downloadBytes,
        inQueue = item.inQueue,
        lastListenedAt = PREVIEW_NOW - (index + 1) * 9 * HOUR_MS,
        totalListenedMs = 1_800_000L,
    )
}

@ThemePreviews
@Composable
private fun HistoryListPreview() {
    AppTheme {
        Surface {
            HistoryContent(
                state = HistoryUiState(
                    sections = groupHistory(previewHistory(), PREVIEW_NOW),
                    loaded = true,
                    now = PREVIEW_NOW,
                ),
                onBack = {}, onOpenEpisode = {}, onTogglePlay = {}, onClearHistory = {},
            )
        }
    }
}

@ThemePreviews
@Composable
private fun HistoryEmptyPreview() {
    AppTheme {
        Surface {
            HistoryContent(
                state = HistoryUiState(loaded = true, now = PREVIEW_NOW),
                onBack = {}, onOpenEpisode = {}, onTogglePlay = {}, onClearHistory = {},
            )
        }
    }
}
