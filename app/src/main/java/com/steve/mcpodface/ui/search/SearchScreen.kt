package com.steve.mcpodface.ui.search

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.steve.mcpodface.data.catalog.CatalogPodcast
import com.steve.mcpodface.data.prefs.SearchMode
import com.steve.mcpodface.ui.components.LargeTitleScaffold
import com.steve.mcpodface.ui.addpodcast.AddPodcastMenu
import com.steve.mcpodface.ui.theme.AppTheme
import com.steve.mcpodface.ui.theme.Spacing
import com.steve.mcpodface.ui.theme.ThemePreviews

/** Search tab (PLAN.md §8.9): "Your podcasts" (placeholder until 6.3) or "Discover". */
@Composable
fun SearchScreen(onOpenPodcast: (Long) -> Unit) {
    val viewModel: SearchViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val currentOnOpenPodcast by rememberUpdatedState(onOpenPodcast)

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is SearchEvent.OpenPodcast -> currentOnOpenPodcast(event.podcastId)
                is SearchEvent.ShowMessage -> snackbarHostState.showSnackbar(event.message)
            }
        }
    }

    SearchContent(
        state = state,
        snackbarHostState = snackbarHostState,
        onOpenPodcast = onOpenPodcast,
        onQueryChange = viewModel::onQueryChange,
        onModeChange = viewModel::onModeChange,
        onCategorySelected = viewModel::onCategorySelected,
        onResultClick = viewModel::onResultClick,
        onRetry = viewModel::retry,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchContent(
    state: SearchUiState,
    snackbarHostState: SnackbarHostState,
    onOpenPodcast: (Long) -> Unit,
    onQueryChange: (String) -> Unit,
    onModeChange: (SearchMode) -> Unit,
    onCategorySelected: (String?) -> Unit,
    onResultClick: (CatalogPodcast) -> Unit,
    onRetry: () -> Unit,
) {
    val discoverLoading = state.mode == SearchMode.DISCOVER && state.discover == DiscoverState.Loading

    LargeTitleScaffold(
        title = "Search",
        actions = { AddPodcastMenu(onOpenPodcast) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.fillMaxSize()) {
                SearchField(
                    query = state.query,
                    onQueryChange = onQueryChange,
                    loading = discoverLoading,
                )
                // Mode is unknown until the default is resolved; show nothing rather than flip.
                state.mode?.let { mode ->
                    ModeSwitcher(mode = mode, onModeChange = onModeChange)
                    when (mode) {
                        SearchMode.YOUR_PODCASTS -> YourPodcastsPlaceholder(Modifier.weight(1f))
                        SearchMode.DISCOVER -> DiscoverContent(
                            query = state.query,
                            state = state.discover,
                            categories = state.categories,
                            selectedCategory = state.selectedCategory,
                            subscribedFeedUrls = state.subscribedFeedUrls,
                            openingFeedUrl = state.openingFeedUrl,
                            onCategorySelected = onCategorySelected,
                            onResultClick = onResultClick,
                            onRetry = onRetry,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter).padding(Spacing.m),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    loading: Boolean,
) {
    val focusManager = LocalFocusManager.current
    Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.m)) {
        TextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Podcasts, authors, topics") },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Outlined.Clear, contentDescription = "Clear search")
                    }
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
            shape = MaterialTheme.shapes.extraLarge,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = AppTheme.colors.surfaceGrouped,
                unfocusedContainerColor = AppTheme.colors.surfaceGrouped,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
            ),
        )
        // Always reserve the strip so the list does not jump when loading starts or stops.
        if (loading) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.xs),
                color = AppTheme.colors.accent,
            )
        } else {
            Spacer(Modifier.height(Spacing.s))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeSwitcher(mode: SearchMode, onModeChange: (SearchMode) -> Unit) {
    val options = listOf(
        SearchMode.YOUR_PODCASTS to "Your podcasts",
        SearchMode.DISCOVER to "Discover",
    )
    SingleChoiceSegmentedButtonRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.m, vertical = Spacing.s),
    ) {
        options.forEachIndexed { index, (value, label) ->
            SegmentedButton(
                selected = mode == value,
                onClick = { onModeChange(value) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                label = { Text(label) },
            )
        }
    }
}

@ThemePreviews
@Composable
private fun SearchContentPreview() {
    AppTheme {
        Surface {
            SearchContent(
                state = SearchUiState(
                    mode = SearchMode.DISCOVER,
                    query = "",
                    discover = DiscoverState.Loading,
                ),
                snackbarHostState = SnackbarHostState(),
                onOpenPodcast = {},
                onQueryChange = {},
                onModeChange = {},
                onCategorySelected = {},
                onResultClick = {},
                onRetry = {},
            )
        }
    }
}
