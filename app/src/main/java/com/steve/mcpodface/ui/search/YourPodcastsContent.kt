package com.steve.mcpodface.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kyant.shapes.RoundedRectangle
import com.steve.mcpodface.data.db.EpisodeListItem
import com.steve.mcpodface.data.search.DurationFilter
import com.steve.mcpodface.data.search.SearchFilters
import com.steve.mcpodface.data.search.SearchResult
import com.steve.mcpodface.data.search.SearchSort
import com.steve.mcpodface.data.search.StatusFilter
import com.steve.mcpodface.ui.components.SwipeableEpisodeRow
import com.steve.mcpodface.ui.components.glass.GlassChip
import com.steve.mcpodface.ui.components.glass.GlassIconButton
import com.steve.mcpodface.ui.components.glass.LocalGlassBackdrop
import com.steve.mcpodface.ui.components.previewEpisodeItems
import com.steve.mcpodface.ui.theme.AppTheme
import com.steve.mcpodface.ui.theme.Spacing
import com.steve.mcpodface.ui.theme.ThemePreviews
import java.time.ZoneId

/**
 * Every action the "Your podcasts" UI can take. Built from the ViewModel by
 * [rememberEpisodeSearchActions]; previews use the no-op defaults.
 */
data class EpisodeSearchActions(
    val onQueryChange: (String) -> Unit = {},
    val onSubmit: () -> Unit = {},
    val onFiltersChange: ((SearchFilters) -> SearchFilters) -> Unit = {},
    val onDatePreset: (DatePreset) -> Unit = {},
    val onCustomDateRange: (from: Long, to: Long) -> Unit = { _, _ -> },
    val onSortChange: (SearchSort) -> Unit = {},
    val onScopeRemoved: () -> Unit = {},
    val onRecentSelected: (String) -> Unit = {},
    val onClearRecent: () -> Unit = {},
    val onOpenEpisode: (Long) -> Unit = {},
    val onTogglePlay: (Long) -> Unit = {},
    val onToggleUpNext: (Long) -> Unit = {},
    val onTogglePlayed: (EpisodeListItem) -> Unit = {},
)

@Composable
fun rememberEpisodeSearchActions(
    viewModel: EpisodeSearchViewModel,
    onOpenEpisode: (Long) -> Unit,
): EpisodeSearchActions = remember(viewModel, onOpenEpisode) {
    EpisodeSearchActions(
        onQueryChange = viewModel::onQueryChange,
        onSubmit = viewModel::onSearchSubmitted,
        onFiltersChange = viewModel::onFiltersChange,
        onDatePreset = viewModel::onDatePreset,
        onCustomDateRange = viewModel::onCustomDateRange,
        onSortChange = viewModel::onSortChange,
        onScopeRemoved = viewModel::onScopeRemoved,
        onRecentSelected = viewModel::onRecentSelected,
        onClearRecent = viewModel::onClearRecent,
        onOpenEpisode = { id ->
            viewModel.onResultOpened()
            onOpenEpisode(id)
        },
        onTogglePlay = viewModel::onTogglePlay,
        onToggleUpNext = viewModel::onToggleUpNext,
        onTogglePlayed = viewModel::onTogglePlayed,
    )
}

/** Which option sheet is open. */
private enum class FilterSheet { DATE, MATCH, STATUS, DURATION, SORT }

/**
 * The "Your podcasts" body (PLAN.md §7.3): filter chips, a results count and the highlighted
 * results. With an empty field and no filters it shows recent searches and a syntax hint instead.
 * Stateless apart from which sheet is open. [contentPadding] clears the top bar and the shell chrome.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YourPodcastsContent(
    state: EpisodeSearchUiState,
    actions: EpisodeSearchActions,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
) {
    var sheet by remember { mutableStateOf<FilterSheet?>(null) }
    var showDateRange by remember { mutableStateOf(false) }
    val filters = state.filters

    LazyColumn(modifier = modifier.fillMaxSize(), contentPadding = contentPadding) {
        item(key = "filter-chips") {
            FilterChipRow(
                state = state,
                onOpenSheet = { sheet = it },
                actions = actions,
            )
        }
        val results = state.results
        if (results == null) {
            item(key = "recent-header") {
                RecentHeader(hasRecent = state.recent.isNotEmpty(), onClear = actions.onClearRecent)
            }
            items(state.recent, key = { "recent-${it.query}" }) { recent ->
                Text(
                    recent.query,
                    style = MaterialTheme.typography.bodyLarge,
                    color = AppTheme.colors.textPrimary,
                    maxLines = 1,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button) {
                            actions.onRecentSelected(recent.query)
                        }
                        .padding(horizontal = Spacing.m, vertical = 12.dp),
                )
            }
            item(key = "syntax-hint") { SyntaxHint() }
        } else {
            item(key = "count") {
                Text(
                    episodeCountLabel(results.totalCount, results.isCapped),
                    style = MaterialTheme.typography.bodySmall,
                    color = AppTheme.colors.textSecondary,
                    modifier = Modifier.padding(horizontal = Spacing.m, vertical = Spacing.s),
                )
            }
            if (results.items.isEmpty()) {
                item(key = "empty") { EmptyResults() }
            } else {
                itemsIndexed(results.items, key = { _, r -> r.item.episode.id }) { index, result ->
                    ResultRow(
                        result = result,
                        now = state.now,
                        nowPlaying = state.nowPlaying,
                        actions = actions,
                    )
                    if (index < results.items.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = Spacing.m + 56.dp + 12.dp),
                            color = AppTheme.colors.hairline,
                        )
                    }
                }
            }
        }
    }

    when (sheet) {
        FilterSheet.DATE -> OptionSheet(
            title = "Date",
            options = DatePreset.entries.map { it to dateLabel(it) },
            selected = state.datePreset,
            onSelect = { preset ->
                sheet = null
                if (preset == DatePreset.CUSTOM) showDateRange = true else actions.onDatePreset(preset)
            },
            onDismiss = { sheet = null },
        )
        FilterSheet.MATCH -> OptionSheet(
            title = "Match in",
            options = listOf(false to "Title and show notes", true to "Title only"),
            selected = filters.titleOnly,
            onSelect = { titleOnly ->
                sheet = null
                actions.onFiltersChange { it.copy(titleOnly = titleOnly) }
            },
            onDismiss = { sheet = null },
        )
        FilterSheet.STATUS -> OptionSheet(
            title = "Status",
            options = StatusFilter.entries.map { it to statusLabel(it) },
            selected = filters.status,
            onSelect = { status ->
                sheet = null
                actions.onFiltersChange { it.copy(status = status) }
            },
            onDismiss = { sheet = null },
        )
        FilterSheet.DURATION -> OptionSheet(
            title = "Duration",
            options = DurationFilter.entries.map { it to durationLabel(it) },
            selected = filters.duration,
            onSelect = { duration ->
                sheet = null
                actions.onFiltersChange { it.copy(duration = duration) }
            },
            onDismiss = { sheet = null },
        )
        FilterSheet.SORT -> OptionSheet(
            title = "Sort",
            options = SearchSort.entries.map { it to sortLabel(it) },
            selected = state.sort,
            onSelect = { sort ->
                sheet = null
                actions.onSortChange(sort)
            },
            onDismiss = { sheet = null },
        )
        null -> Unit
    }

    if (showDateRange) {
        DateRangeDialog(
            onDismiss = { showDateRange = false },
            onConfirm = { startUtc, endUtc ->
                showDateRange = false
                val (from, to) = customRangeBounds(startUtc, endUtc, ZoneId.systemDefault())
                actions.onCustomDateRange(from, to)
            },
        )
    }
}

@Composable
private fun FilterChipRow(
    state: EpisodeSearchUiState,
    onOpenSheet: (FilterSheet) -> Unit,
    actions: EpisodeSearchActions,
) {
    val filters = state.filters
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = Spacing.m, vertical = Spacing.s),
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (filters.podcastIds.isNotEmpty()) {
            ScopeChip(
                name = state.scopeName ?: "This podcast",
                onRemove = actions.onScopeRemoved,
            )
        }
        GlassChip(
            label = if (state.datePreset == DatePreset.ANY) "Date" else dateLabel(state.datePreset),
            selected = state.datePreset != DatePreset.ANY,
            onClick = { onOpenSheet(FilterSheet.DATE) },
        )
        GlassChip(
            label = if (filters.titleOnly) "Title only" else "Match in",
            selected = filters.titleOnly,
            onClick = { onOpenSheet(FilterSheet.MATCH) },
        )
        GlassChip(
            label = if (filters.status == StatusFilter.ANY) "Status" else statusLabel(filters.status),
            selected = filters.status != StatusFilter.ANY,
            onClick = { onOpenSheet(FilterSheet.STATUS) },
        )
        GlassChip(
            label = "Downloaded",
            selected = filters.downloadedOnly,
            onClick = { actions.onFiltersChange { it.copy(downloadedOnly = !it.downloadedOnly) } },
        )
        GlassChip(
            label = "Starred",
            selected = filters.starredOnly,
            onClick = { actions.onFiltersChange { it.copy(starredOnly = !it.starredOnly) } },
        )
        GlassChip(
            label = if (filters.duration == DurationFilter.ANY) "Duration" else durationLabel(filters.duration),
            selected = filters.duration != DurationFilter.ANY,
            onClick = { onOpenSheet(FilterSheet.DURATION) },
        )
        GlassChip(
            label = if (state.sort == SearchSort.RELEVANCE) "Sort" else sortLabel(state.sort),
            selected = state.sort != SearchSort.RELEVANCE,
            onClick = { onOpenSheet(FilterSheet.SORT) },
        )
    }
}

/** The podcast scope: a chip with its name, and a glass button beside it to widen the search. */
@Composable
private fun ScopeChip(name: String, onRemove: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        GlassChip(label = name, selected = true, onClick = {})
        GlassIconButton(
            icon = Icons.Rounded.Close,
            contentDescription = "Search all subscriptions",
            onClick = onRemove,
            size = 36.dp,
            iconSize = 16.dp,
        )
    }
}

@Composable
private fun RecentHeader(hasRecent: Boolean, onClear: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = Spacing.m, end = Spacing.s, top = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Recent searches",
            style = MaterialTheme.typography.titleSmall,
            color = AppTheme.colors.textSecondary,
            modifier = Modifier.weight(1f),
        )
        if (hasRecent) {
            TextButton(onClick = onClear) { Text("Clear") }
        }
    }
}

@Composable
private fun SyntaxHint() {
    Text(
        "Tip: \"exact phrase\" to match words together, -word to leave a word out, " +
            "and nasa OR esa to match either.",
        style = MaterialTheme.typography.bodySmall,
        color = AppTheme.colors.textSecondary,
        modifier = Modifier.padding(horizontal = Spacing.m, vertical = Spacing.s),
    )
}

@Composable
private fun EmptyResults() {
    Text(
        "No episodes match",
        style = MaterialTheme.typography.bodyLarge,
        color = AppTheme.colors.textSecondary,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = Spacing.l),
    )
}

@Composable
private fun ResultRow(
    result: SearchResult,
    now: Long,
    nowPlaying: com.steve.mcpodface.playback.NowPlaying?,
    actions: EpisodeSearchActions,
) {
    val episodeId = result.item.episode.id
    SwipeableEpisodeRow(
        item = result.item,
        now = now,
        isPlaying = nowPlaying?.let { it.episodeId == episodeId && it.isPlaying } ?: false,
        inUpNext = result.item.inQueue,
        onClick = { actions.onOpenEpisode(episodeId) },
        onPlayClick = { actions.onTogglePlay(episodeId) },
        onToggleUpNext = { actions.onToggleUpNext(episodeId) },
        onTogglePlayed = { actions.onTogglePlayed(result.item) },
        showPodcastTitle = true,
        snippet = result.snippet?.let { snippetToAnnotatedString(it) },
    )
}

/**
 * A list of options in a glass-free sheet body, with a check on the selected one. The sheet is a
 * separate window, so it does not sample the page backdrop (PLAN.md §11).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> OptionSheet(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = AppTheme.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = remember { RoundedRectangle(32.dp) },
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        CompositionLocalProvider(LocalGlassBackdrop provides null) {
            Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.m).padding(bottom = Spacing.l)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = colors.textPrimary,
                    modifier = Modifier.padding(bottom = Spacing.s),
                )
                options.forEach { (value, label) ->
                    val isSelected = value == selected
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.RadioButton) { onSelect(value) }
                            .padding(vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (isSelected) colors.accent else colors.textPrimary,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.weight(1f),
                        )
                        if (isSelected) {
                            Icon(Icons.Outlined.Check, contentDescription = null, tint = colors.accent, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
    }
}

/** Material's range picker in a dialog. Hands back the selected UTC-midnight millis. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateRangeDialog(onDismiss: () -> Unit, onConfirm: (startUtc: Long, endUtc: Long) -> Unit) {
    val pickerState = rememberDateRangePickerState()
    val start = pickerState.selectedStartDateMillis
    val end = pickerState.selectedEndDateMillis
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { if (start != null && end != null) onConfirm(start, end) },
                enabled = start != null && end != null,
            ) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) {
        CompositionLocalProvider(LocalGlassBackdrop provides null) {
            DateRangePicker(
                state = pickerState,
                title = null,
                headline = null,
                showModeToggle = false,
                modifier = Modifier.height(480.dp),
            )
        }
    }
}

internal fun dateLabel(preset: DatePreset): String = when (preset) {
    DatePreset.ANY -> "Any time"
    DatePreset.PAST_WEEK -> "Past week"
    DatePreset.PAST_MONTH -> "Past month"
    DatePreset.PAST_YEAR -> "Past year"
    DatePreset.CUSTOM -> "Custom range…"
}

internal fun statusLabel(status: StatusFilter): String = when (status) {
    StatusFilter.ANY -> "Any"
    StatusFilter.UNPLAYED -> "Unplayed"
    StatusFilter.IN_PROGRESS -> "In progress"
    StatusFilter.PLAYED -> "Played"
    StatusFilter.NOT_PLAYED -> "Unplayed or in progress"
}

internal fun durationLabel(duration: DurationFilter): String = when (duration) {
    DurationFilter.ANY -> "Any length"
    DurationFilter.UNDER_15 -> "Under 15 min"
    DurationFilter.FROM_15_TO_45 -> "15–45 min"
    DurationFilter.FROM_45_TO_90 -> "45–90 min"
    DurationFilter.OVER_90 -> "Over 90 min"
}

internal fun sortLabel(sort: SearchSort): String = when (sort) {
    SearchSort.RELEVANCE -> "Relevance"
    SearchSort.NEWEST -> "Newest"
    SearchSort.OLDEST -> "Oldest"
}

// ---- Previews -------------------------------------------------------------------------------

private const val PREVIEW_NOW = 1_792_411_200_000L // 2026-10-19T12:00Z

private fun previewResults(): EpisodeSearchUiState {
    val items = previewEpisodeItems()
    return EpisodeSearchUiState(
        query = "bridge",
        results = com.steve.mcpodface.data.search.SearchResults(
            items = items.take(3).mapIndexed { i, item ->
                SearchResult(
                    item = item,
                    snippet = listOf(
                        "...the surveyor kept a <b>notebook</b> of every <b>bridge</b> he measured...",
                        "Tom &amp; Jerry <b>bridge</b> &lt;b&gt; literal",
                        null,
                    )[i],
                )
            },
            totalCount = 128,
        ),
        now = PREVIEW_NOW,
    )
}

@ThemePreviews
@Composable
private fun YourPodcastsResultsPreview() {
    AppTheme {
        Surface {
            YourPodcastsContent(state = previewResults(), actions = EpisodeSearchActions())
        }
    }
}

@ThemePreviews
@Composable
private fun YourPodcastsRecentPreview() {
    AppTheme {
        Surface {
            YourPodcastsContent(
                state = EpisodeSearchUiState(
                    recent = listOf(
                        com.steve.mcpodface.data.db.RecentSearchEntity("climate change", PREVIEW_NOW),
                        com.steve.mcpodface.data.db.RecentSearchEntity("nasa OR esa", PREVIEW_NOW),
                    ),
                    now = PREVIEW_NOW,
                ),
                actions = EpisodeSearchActions(),
            )
        }
    }
}

@ThemePreviews
@Composable
private fun FilterChipRowPreview() {
    AppTheme {
        Surface {
            FilterChipRow(
                state = EpisodeSearchUiState(
                    filters = SearchFilters(
                        podcastIds = setOf(1L),
                        titleOnly = true,
                        downloadedOnly = true,
                        duration = DurationFilter.OVER_90,
                    ),
                    datePreset = DatePreset.PAST_MONTH,
                    sort = SearchSort.NEWEST,
                    scopeName = "Radiolab",
                ),
                onOpenSheet = {},
                actions = EpisodeSearchActions(),
            )
        }
    }
}

@ThemePreviews
@Composable
private fun YourPodcastsScopedEmptyPreview() {
    AppTheme {
        Surface {
            YourPodcastsContent(
                state = EpisodeSearchUiState(
                    query = "zzz",
                    filters = SearchFilters(podcastIds = setOf(1L), downloadedOnly = true),
                    datePreset = DatePreset.PAST_MONTH,
                    scopeName = "Radiolab",
                    results = com.steve.mcpodface.data.search.SearchResults(emptyList(), 0),
                    now = PREVIEW_NOW,
                ),
                actions = EpisodeSearchActions(),
            )
        }
    }
}
