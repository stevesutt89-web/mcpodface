package com.steve.mcpodface.ui.search

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.steve.mcpodface.ui.components.BackButton
import com.steve.mcpodface.ui.components.GlassTopBarScaffold
import com.steve.mcpodface.ui.components.glass.GlassPage
import com.steve.mcpodface.ui.theme.AppTheme
import com.steve.mcpodface.ui.theme.ThemePreviews

/**
 * Episode search scoped to one podcast (PLAN.md §7.1), opened from "Search this podcast". The
 * podcast id comes from the route; the scope chip can widen the search to all subscriptions.
 */
@Composable
fun EpisodeSearchScreen(onBack: () -> Unit, onOpenEpisode: (Long) -> Unit) {
    val viewModel: EpisodeSearchViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val actions = rememberEpisodeSearchActions(viewModel, onOpenEpisode)
    EpisodeSearchContent(state = state, actions = actions, onBack = onBack)
}

@Composable
private fun EpisodeSearchContent(
    state: EpisodeSearchUiState,
    actions: EpisodeSearchActions,
    onBack: () -> Unit,
) {
    GlassPage {
        GlassTopBarScaffold(
            title = "Search",
            background = Color.Transparent,
            navigationIcon = { BackButton(onClick = onBack) },
        ) { padding ->
            // The field stays under the top bar; the results scroll and clear the tab bar via contentPadding.
            Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
                SearchField(
                    query = state.query,
                    onQueryChange = actions.onQueryChange,
                    loading = false,
                    placeholder = state.scopeName?.let { "Search $it" } ?: "Podcasts, episodes",
                    onSubmit = actions.onSubmit,
                )
                YourPodcastsContent(
                    state = state,
                    actions = actions,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = padding.calculateBottomPadding()),
                )
            }
        }
    }
}

@ThemePreviews
@Composable
private fun EpisodeSearchScreenPreview() {
    AppTheme {
        Surface {
            EpisodeSearchContent(
                state = EpisodeSearchUiState(
                    filters = com.steve.mcpodface.data.search.SearchFilters(podcastIds = setOf(1L)),
                    scopeName = "Radiolab",
                ),
                actions = EpisodeSearchActions(),
                onBack = {},
            )
        }
    }
}
