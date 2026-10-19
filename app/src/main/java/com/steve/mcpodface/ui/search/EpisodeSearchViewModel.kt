package com.steve.mcpodface.ui.search

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.steve.mcpodface.core.Clock
import com.steve.mcpodface.data.db.PodcastDao
import com.steve.mcpodface.data.db.RecentSearchEntity
import com.steve.mcpodface.data.search.SearchFilters
import com.steve.mcpodface.data.search.SearchRepository
import com.steve.mcpodface.data.search.SearchResults
import com.steve.mcpodface.data.search.SearchSort
import com.steve.mcpodface.playback.NowPlaying
import com.steve.mcpodface.playback.PlayerController
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The Date chip's choices (PLAN.md §7.3). The window is fixed when chosen, not re-computed. */
enum class DatePreset { ANY, PAST_WEEK, PAST_MONTH, PAST_YEAR, CUSTOM }

/** Everything the user has typed or picked, before the 200ms debounce on the text. */
internal data class SearchForm(
    val query: String = "",
    val filters: SearchFilters = SearchFilters(),
    val sort: SearchSort = SearchSort.RELEVANCE,
    val datePreset: DatePreset = DatePreset.ANY,
)

data class EpisodeSearchUiState(
    val query: String = "",
    val filters: SearchFilters = SearchFilters(),
    val sort: SearchSort = SearchSort.RELEVANCE,
    val datePreset: DatePreset = DatePreset.ANY,
    /** Name of the single scoped podcast, if any. */
    val scopeName: String? = null,
    /** null when there is nothing to search for: the screen shows recent searches and the hint. */
    val results: SearchResults? = null,
    val recent: List<RecentSearchEntity> = emptyList(),
    val nowPlaying: NowPlaying? = null,
    /** Wall-clock time for the relative dates in the rows. */
    val now: Long = 0L,
)

/**
 * State for "Your podcasts" search (PLAN.md §7, §8.9). Used by the Search tab (all subscriptions)
 * and by the podcast-scoped route, which passes its podcast id as the `podcastId` nav argument.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class EpisodeSearchViewModel @Inject constructor(
    private val search: SearchRepository,
    private val podcasts: PodcastDao,
    private val player: PlayerController,
    private val clock: Clock,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val form = MutableStateFlow(
        SearchForm(filters = SearchFilters(podcastIds = setOfNotNull(savedStateHandle.get<Long>(ARG_PODCAST_ID)))),
    )

    private val filters: Flow<SearchFilters> = form.map { it.filters }.distinctUntilChanged()
    private val sort: Flow<SearchSort> = form.map { it.sort }.distinctUntilChanged()

    /** The text waits 200ms after the last keystroke before it searches. Blank text clears at once. */
    private val debouncedQuery: Flow<String> = form.map { it.query }
        .distinctUntilChanged()
        .debounce { if (it.isBlank()) 0L else DEBOUNCE_MS }

    private val results: Flow<SearchResults?> = combine(debouncedQuery, filters, sort) { query, f, s ->
        Triple(query, f, s)
    }.flatMapLatest { (query, f, s) ->
        if (query.isBlank() && f.isDefault) {
            flowOf(null)
        } else {
            search.search(query, f, s)
        }
    }

    /** Scope name for the chip. Null when not scoped, or when the podcast has gone. */
    private val scopeName: Flow<String?> = filters
        .map { it.podcastIds.singleOrNull() }
        .distinctUntilChanged()
        .flatMapLatest { id ->
            if (id == null) flowOf(null) else podcasts.observeById(id).map { it?.title }
        }

    val uiState: StateFlow<EpisodeSearchUiState> = combine(
        form,
        scopeName,
        results,
        search.recentSearches(),
        player.nowPlaying,
    ) { f, scope, res, recent, playing ->
        EpisodeSearchUiState(
            query = f.query,
            filters = f.filters,
            sort = f.sort,
            datePreset = f.datePreset,
            scopeName = scope,
            results = res,
            recent = recent,
            nowPlaying = playing,
            now = clock.now(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EpisodeSearchUiState())

    fun onQueryChange(text: String) {
        form.update { it.copy(query = text) }
    }

    fun onFiltersChange(transform: (SearchFilters) -> SearchFilters) {
        form.update { it.copy(filters = transform(it.filters)) }
    }

    fun onSortChange(sort: SearchSort) {
        form.update { it.copy(sort = sort) }
    }

    /** A preset other than [DatePreset.CUSTOM]: the window is fixed from now. */
    fun onDatePreset(preset: DatePreset) {
        val now = clock.now()
        val from = when (preset) {
            DatePreset.PAST_WEEK -> now - 7 * DAY_MS
            DatePreset.PAST_MONTH -> now - 30 * DAY_MS
            DatePreset.PAST_YEAR -> now - 365 * DAY_MS
            DatePreset.ANY, DatePreset.CUSTOM -> null
        }
        form.update {
            it.copy(datePreset = preset, filters = it.filters.copy(publishedFrom = from, publishedTo = null))
        }
    }

    /** A custom window, as [publishedFrom, publishedTo) epoch millis (see [customRangeBounds]). */
    fun onCustomDateRange(from: Long, to: Long) {
        form.update {
            it.copy(datePreset = DatePreset.CUSTOM, filters = it.filters.copy(publishedFrom = from, publishedTo = to))
        }
    }

    /** Widens a podcast-scoped search back to all subscriptions. */
    fun onScopeRemoved() {
        form.update { it.copy(filters = it.filters.copy(podcastIds = emptySet())) }
    }

    /** The IME search action. */
    fun onSearchSubmitted() = recordCurrent()

    /** Called when a result is opened, so the search is remembered. */
    fun onResultOpened() = recordCurrent()

    fun onRecentSelected(query: String) {
        form.update { it.copy(query = query) }
    }

    fun onClearRecent() {
        viewModelScope.launch { search.clearRecentSearches() }
    }

    fun onTogglePlay(episodeId: Long) {
        player.togglePlayPause(episodeId)
    }

    private fun recordCurrent() {
        val current = form.value
        if (current.query.isBlank()) return
        viewModelScope.launch {
            search.recordSearch(current.query, current.filters.podcastIds.singleOrNull())
        }
    }

    companion object {
        /** The nav argument name of [com.steve.mcpodface.ui.navigation.EpisodeSearchRoute]. */
        const val ARG_PODCAST_ID = "podcastId"
        const val DEBOUNCE_MS = 200L
        private const val DAY_MS = 86_400_000L
    }
}
