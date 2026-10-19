package com.steve.mcpodface.ui.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.steve.mcpodface.data.db.EpisodeListItem
import com.steve.mcpodface.ui.components.BackButton
import com.steve.mcpodface.ui.components.EpisodeRow
import com.steve.mcpodface.ui.components.previewEpisodeItems
import com.steve.mcpodface.ui.theme.AppTheme
import com.steve.mcpodface.ui.theme.Spacing
import com.steve.mcpodface.ui.theme.ThemePreviews

private const val PREVIEW_NOW = 1_792_411_200_000L // 2026-10-19T12:00Z

/** Starred episodes (PLAN.md §8.3). Plain top bar; long press offers "Remove star". */
@Composable
fun StarredScreen(onBack: () -> Unit, onOpenEpisode: (Long) -> Unit) {
    val viewModel: StarredViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()

    StarredContent(
        state = state,
        onBack = onBack,
        onOpenEpisode = onOpenEpisode,
        onTogglePlay = viewModel::togglePlay,
        onRemoveStar = viewModel::removeStar,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StarredContent(
    state: StarredUiState,
    onBack: () -> Unit,
    onOpenEpisode: (Long) -> Unit,
    onTogglePlay: (Long) -> Unit,
    onRemoveStar: (Long) -> Unit,
) {
    var menuEpisodeId by rememberSaveable { mutableStateOf<Long?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text("Starred") },
                navigationIcon = { BackButton(onClick = onBack) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        if (state.loaded && state.items.isEmpty()) {
            EmptyState(Modifier.fillMaxSize().padding(padding))
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(bottom = Spacing.xl),
            ) {
                itemsIndexed(state.items, key = { _, item -> item.episode.id }) { index, item ->
                    val episodeId = item.episode.id
                    Box {
                        EpisodeRow(
                            item = item,
                            onClick = { onOpenEpisode(episodeId) },
                            onPlayClick = { onTogglePlay(episodeId) },
                            isPlaying = state.nowPlaying?.let { it.episodeId == episodeId && it.isPlaying } ?: false,
                            showPodcastTitle = true,
                            now = state.now,
                            onLongClick = { menuEpisodeId = episodeId },
                        )
                        DropdownMenu(
                            expanded = menuEpisodeId == episodeId,
                            onDismissRequest = { menuEpisodeId = null },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Remove star") },
                                onClick = {
                                    onRemoveStar(episodeId)
                                    menuEpisodeId = null
                                },
                            )
                        }
                    }
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

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Box(modifier.padding(horizontal = Spacing.l), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Text("No starred episodes", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Spacer(Modifier.height(Spacing.xs))
            Text(
                "Starred episodes are never auto-deleted.",
                style = MaterialTheme.typography.bodySmall,
                color = AppTheme.colors.textSecondary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

// ---- Previews -------------------------------------------------------------------------------

private fun previewStarred(): List<EpisodeListItem> = previewEpisodeItems().map { item ->
    item.copy(episode = item.episode.copy(isStarred = true))
}

@ThemePreviews
@Composable
private fun StarredListPreview() {
    AppTheme {
        Surface {
            StarredContent(
                state = StarredUiState(items = previewStarred(), loaded = true, now = PREVIEW_NOW),
                onBack = {}, onOpenEpisode = {}, onTogglePlay = {}, onRemoveStar = {},
            )
        }
    }
}

@ThemePreviews
@Composable
private fun StarredEmptyPreview() {
    AppTheme {
        Surface {
            StarredContent(
                state = StarredUiState(loaded = true, now = PREVIEW_NOW),
                onBack = {}, onOpenEpisode = {}, onTogglePlay = {}, onRemoveStar = {},
            )
        }
    }
}
