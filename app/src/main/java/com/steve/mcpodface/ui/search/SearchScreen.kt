package com.steve.mcpodface.ui.search

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kyant.shapes.Capsule
import com.steve.mcpodface.data.catalog.CatalogPodcast
import com.steve.mcpodface.data.prefs.SearchMode
import com.steve.mcpodface.ui.addpodcast.AddPodcastMenu
import com.steve.mcpodface.ui.components.BackButton
import com.steve.mcpodface.ui.components.LargeTitleScaffold
import com.steve.mcpodface.ui.components.glass.GlassPage
import com.steve.mcpodface.ui.components.glass.GlassStyle
import com.steve.mcpodface.ui.components.glass.glass
import com.steve.mcpodface.ui.theme.AppTheme
import com.steve.mcpodface.ui.theme.Spacing
import com.steve.mcpodface.ui.theme.ThemePreviews

/**
 * Search, pushed from the top bar of each tab (PLAN.md §8.9): "Your podcasts" (local episode search)
 * or "Discover". One search field serves both: its text goes to the Discover ViewModel and to the
 * episode search ViewModel.
 */
@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onOpenPodcast: (Long) -> Unit,
    onOpenEpisode: (Long) -> Unit = {},
) {
    val viewModel: SearchViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val episodeViewModel: EpisodeSearchViewModel = hiltViewModel()
    val episodeState by episodeViewModel.uiState.collectAsStateWithLifecycle()
    val episodeActions = rememberEpisodeSearchActions(episodeViewModel, onOpenEpisode)
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
        episodeState = episodeState,
        episodeActions = episodeActions,
        snackbarHostState = snackbarHostState,
        onBack = onBack,
        onOpenPodcast = onOpenPodcast,
        onQueryChange = { text ->
            viewModel.onQueryChange(text)
            episodeActions.onQueryChange(text)
        },
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
    episodeState: EpisodeSearchUiState,
    episodeActions: EpisodeSearchActions,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit = {},
    onOpenPodcast: (Long) -> Unit,
    onQueryChange: (String) -> Unit,
    onModeChange: (SearchMode) -> Unit,
    onCategorySelected: (String?) -> Unit,
    onResultClick: (CatalogPodcast) -> Unit,
    onRetry: () -> Unit,
) {
    val discoverLoading = state.mode == SearchMode.DISCOVER && state.discover == DiscoverState.Loading

    GlassPage {
        LargeTitleScaffold(
            title = "Search",
            background = Color.Transparent,
            navigationIcon = { BackButton(onBack) },
            actions = {
                AddPodcastMenu(onOpenPodcast)
            },
        ) { padding ->
            // The search field and mode switch stay pinned under the large title; the results
            // below scroll under the floating tab bar, which the bottom padding clears.
            val bottomPadding = PaddingValues(bottom = padding.calculateBottomPadding())
            Box(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
                    SearchField(
                        query = state.query,
                        onQueryChange = onQueryChange,
                        loading = discoverLoading,
                        onSubmit = episodeActions.onSubmit,
                    )
                    // Mode is unknown until the default is resolved; show nothing rather than flip.
                    state.mode?.let { mode ->
                        ModeSwitcher(mode = mode, onModeChange = onModeChange)
                        when (mode) {
                            SearchMode.YOUR_PODCASTS -> YourPodcastsContent(
                                state = episodeState,
                                actions = episodeActions,
                                modifier = Modifier.weight(1f),
                                contentPadding = bottomPadding,
                            )
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
                                contentPadding = bottomPadding,
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
}

/** Liquid Glass search capsule: rounded search icon, placeholder, the query and a clear button. */
@Composable
internal fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    loading: Boolean,
    placeholder: String = "Podcasts, episodes",
    onSubmit: () -> Unit = {},
) {
    val focusManager = LocalFocusManager.current
    val colors = AppTheme.colors
    val capsule = remember { Capsule() }
    Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.m)) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .glass(capsule, GlassStyle.Regular)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Rounded.Search,
                contentDescription = null,
                tint = colors.textSecondary,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(Spacing.s))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) {
                    Text(
                        placeholder,
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.textSecondary,
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.textPrimary),
                    cursorBrush = SolidColor(colors.accent),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        onSubmit()
                        focusManager.clearFocus()
                    }),
                )
            }
            if (query.isNotEmpty()) {
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(Capsule())
                        .clickable(role = Role.Button, onClickLabel = "Clear search") { onQueryChange("") },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.Clear,
                        contentDescription = "Clear search",
                        tint = colors.textSecondary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
        // Always reserve the strip so the list does not jump when loading starts or stops.
        if (loading) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.s),
                color = colors.accent,
            )
        } else {
            Spacer(Modifier.height(Spacing.s))
        }
    }
}

@Composable
private fun ModeSwitcher(mode: SearchMode, onModeChange: (SearchMode) -> Unit) {
    val options = listOf(
        SearchMode.YOUR_PODCASTS to "Your podcasts",
        SearchMode.DISCOVER to "Discover",
    )
    GlassSegmentedControl(
        labels = options.map { it.second },
        selectedIndex = options.indexOfFirst { it.first == mode },
        onSelect = { onModeChange(options[it].first) },
        modifier = Modifier.padding(horizontal = Spacing.m, vertical = Spacing.s),
    )
}

/**
 * Two equal segments in a Regular glass capsule. A Clear glass lens tinted with the accent slides
 * behind the selected segment (same spring as the tab bar).
 */
@Composable
private fun GlassSegmentedControl(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val haptics = LocalHapticFeedback.current
    val barShape = remember { Capsule() }
    val lensShape = remember { Capsule() }
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(44.dp)
            .glass(barShape, GlassStyle.Regular)
            .padding(4.dp),
    ) {
        val itemWidth = maxWidth / labels.size.coerceAtLeast(1)
        val lensX by animateDpAsState(
            targetValue = itemWidth * selectedIndex.coerceAtLeast(0),
            animationSpec = spring(dampingRatio = 0.68f, stiffness = 420f),
            label = "segmentLens",
        )
        if (selectedIndex >= 0) {
            Box(
                Modifier
                    .offset(x = lensX)
                    .width(itemWidth)
                    .fillMaxHeight()
                    .glass(lensShape, GlassStyle.Selected),
            )
        }
        Row(Modifier.fillMaxSize()) {
            labels.forEachIndexed { index, label ->
                val selected = index == selectedIndex
                val color by animateColorAsState(
                    if (selected) colors.textPrimary else colors.textSecondary,
                    label = "segmentColor",
                )
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Tab,
                        ) {
                            if (!selected) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            onSelect(index)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        color = color,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 1,
                    )
                }
            }
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
                episodeState = EpisodeSearchUiState(),
                episodeActions = EpisodeSearchActions(),
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

@ThemePreviews
@Composable
private fun SearchFieldWithQueryPreview() {
    AppTheme {
        Surface {
            Column(Modifier.fillMaxWidth().padding(vertical = Spacing.m)) {
                SearchField(query = "radiolab", onQueryChange = {}, loading = true)
                GlassSegmentedControl(
                    labels = listOf("Your podcasts", "Discover"),
                    selectedIndex = 1,
                    onSelect = {},
                    modifier = Modifier.padding(horizontal = Spacing.m, vertical = Spacing.s),
                )
            }
        }
    }
}
