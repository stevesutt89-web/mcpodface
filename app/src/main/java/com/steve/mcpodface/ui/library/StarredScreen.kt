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
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.steve.mcpodface.data.db.EpisodeEntity
import com.steve.mcpodface.data.db.EpisodeListItem
import com.steve.mcpodface.ui.components.BackButton
import com.steve.mcpodface.ui.components.GlassTopBarScaffold
import com.steve.mcpodface.ui.components.SwipeableEpisodeRow
import com.steve.mcpodface.ui.components.glass.GlassPage
import com.steve.mcpodface.ui.components.glass.GlassStyle
import com.steve.mcpodface.ui.components.glass.glass
import com.steve.mcpodface.ui.components.previewEpisodeItems
import com.steve.mcpodface.ui.theme.AppTheme
import com.steve.mcpodface.ui.theme.Spacing
import com.steve.mcpodface.ui.theme.ThemePreviews

private const val PREVIEW_NOW = 1_792_411_200_000L // 2026-10-19T12:00Z

/** Starred episodes (PLAN.md §8.3). Glass top bar; long press offers "Remove star". */
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
        onToggleUpNext = viewModel::toggleUpNext,
        onTogglePlayed = viewModel::togglePlayed,
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
    onToggleUpNext: (Long) -> Unit = {},
    onTogglePlayed: (EpisodeEntity) -> Unit = {},
) {
    var menuEpisodeId by rememberSaveable { mutableStateOf<Long?>(null) }

    GlassPage {
        GlassTopBarScaffold(
            title = "Starred",
            background = Color.Transparent,
            navigationIcon = { BackButton(onClick = onBack) },
        ) { padding ->
            if (state.loaded && state.items.isEmpty()) {
                EmptyState(Modifier.fillMaxSize().padding(padding))
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        top = padding.calculateTopPadding(),
                        bottom = padding.calculateBottomPadding() + Spacing.s,
                    ),
                ) {
                    itemsIndexed(state.items, key = { _, item -> item.episode.id }) { index, item ->
                        val episodeId = item.episode.id
                        Box {
                            SwipeableEpisodeRow(
                                item = item,
                                onClick = { onOpenEpisode(episodeId) },
                                onPlayClick = { onTogglePlay(episodeId) },
                                isPlaying = state.nowPlaying?.let { it.episodeId == episodeId && it.isPlaying } ?: false,
                                inUpNext = item.inQueue,
                                onToggleUpNext = { onToggleUpNext(episodeId) },
                                onTogglePlayed = { onTogglePlayed(item.episode) },
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
}

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
                    Icons.Rounded.StarBorder,
                    contentDescription = null,
                    tint = AppTheme.colors.accent,
                    modifier = Modifier.size(56.dp),
                )
            }
            Spacer(Modifier.height(Spacing.m))
            Text(
                "No starred episodes",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(Spacing.xs))
            Text(
                "Starred episodes are never auto-deleted.",
                style = MaterialTheme.typography.bodyLarge,
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
