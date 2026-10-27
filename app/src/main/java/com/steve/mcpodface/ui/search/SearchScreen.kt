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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.text.style.TextOverflow
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import com.steve.mcpodface.ui.components.SettingsButton
import com.steve.mcpodface.ui.components.LargeTitleScaffold
import com.steve.mcpodface.ui.components.glass.GlassPage
import com.steve.mcpodface.ui.components.glass.GlassStyle
import com.steve.mcpodface.ui.components.glass.glass
import com.steve.mcpodface.ui.components.glass.onGlass
import com.steve.mcpodface.ui.theme.AppTheme
import com.steve.mcpodface.ui.theme.Spacing
import com.steve.mcpodface.ui.theme.ThemePreviews
import com.steve.mcpodface.ui.components.LocalAppSnackbarHostState
import com.steve.mcpodface.ui.components.showReplacing
import kotlinx.coroutines.launch

/**
 * The Discover tab (PLAN.md §8.9): trending shows and catalogue search, for finding new podcasts.
 * Searching the episodes of your own podcasts is [EpisodeSearchScreen], opened from the search
 * button on Latest and Library.
 */
@Composable
fun SearchScreen(
    onOpenSettings: () -> Unit,
    onOpenPodcast: (Long) -> Unit,
) {
    val viewModel: SearchViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = LocalAppSnackbarHostState.current
    val currentOnOpenPodcast by rememberUpdatedState(onOpenPodcast)

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is SearchEvent.OpenPodcast -> currentOnOpenPodcast(event.podcastId)
                is SearchEvent.ShowMessage -> launch { snackbarHostState.showReplacing(event.message) }
            }
        }
    }

    SearchContent(
        state = state,
        onOpenSettings = onOpenSettings,
        onOpenPodcast = onOpenPodcast,
        onQueryChange = viewModel::onQueryChange,
        onCategorySelected = viewModel::onCategorySelected,
        onResultClick = viewModel::onResultClick,
        onRetry = viewModel::retry,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchContent(
    state: SearchUiState,
    onOpenSettings: () -> Unit = {},
    onOpenPodcast: (Long) -> Unit,
    onQueryChange: (String) -> Unit,
    onCategorySelected: (String?) -> Unit,
    onResultClick: (CatalogPodcast) -> Unit,
    onRetry: () -> Unit,
) {
    val discoverLoading = state.discover == DiscoverState.Loading

    GlassPage {
        LargeTitleScaffold(
            title = "Discover",
            background = Color.Transparent,
            actions = {
                AddPodcastMenu(onOpenPodcast)
                SettingsButton(onOpenSettings)
            },
        ) { padding ->
            // The search field stays pinned under the large title; the results
            // below scroll under the floating tab bar, which the bottom padding clears.
            val bottomPadding = PaddingValues(bottom = padding.calculateBottomPadding())
            Box(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
                    SearchField(
                        query = state.query,
                        onQueryChange = onQueryChange,
                        loading = discoverLoading,
                        placeholder = "Find new podcasts",
                    )
                    DiscoverContent(
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
    /** Opens the keyboard the first time the field is shown (not again on coming back to it). */
    autoFocus: Boolean = false,
) {
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    var focusedOnce by rememberSaveable { mutableStateOf(false) }
    if (autoFocus && !focusedOnce) {
        LaunchedEffect(Unit) {
            focusRequester.requestFocus()
            focusedOnce = true
        }
    }
    val colors = AppTheme.colors
    val capsule = remember { Capsule() }
    Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.m)) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
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
                        style = MaterialTheme.typography.bodyLarge.onGlass(colors.textSecondary),
                        color = colors.textSecondary,
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.textPrimary).onGlass(colors.textPrimary),
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
                onOpenPodcast = {},
                onQueryChange = {},
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
            }
        }
    }
}
