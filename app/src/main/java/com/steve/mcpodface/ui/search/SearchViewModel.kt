package com.steve.mcpodface.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.steve.mcpodface.data.catalog.CatalogCategory
import com.steve.mcpodface.data.catalog.CatalogException
import com.steve.mcpodface.data.catalog.CatalogPodcast
import com.steve.mcpodface.data.catalog.CatalogRepository
import com.steve.mcpodface.data.db.PodcastDao
import com.steve.mcpodface.data.feed.SubscriptionRepository
import com.steve.mcpodface.data.prefs.SearchMode
import com.steve.mcpodface.data.prefs.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Discover-mode content for the current query and category. */
sealed interface DiscoverState {
    data object Loading : DiscoverState
    /** Blank query: the trending list for the selected category. */
    data class Trending(val podcasts: List<CatalogPodcast>) : DiscoverState
    /** Non-blank query: podcasts matching the search term. */
    data class Results(val podcasts: List<CatalogPodcast>) : DiscoverState
    data object Empty : DiscoverState
    data object Error : DiscoverState
}

data class SearchUiState(
    /** null until the default mode has been resolved from settings and subscriptions. */
    val mode: SearchMode? = null,
    val query: String = "",
    /** Category chips (PLAN.md §8.9): first 20 from the catalogue, "All" is added by the UI. */
    val categories: List<CatalogCategory> = emptyList(),
    /** null means "All". */
    val selectedCategory: String? = null,
    val discover: DiscoverState = DiscoverState.Loading,
    val subscribedFeedUrls: Set<String> = emptySet(),
    /** feedUrl of the row whose subscription is being opened, if any. */
    val openingFeedUrl: String? = null,
)

/** One-shot effects for the screen. */
sealed interface SearchEvent {
    data class OpenPodcast(val podcastId: Long) : SearchEvent
    data class ShowMessage(val message: String) : SearchEvent
}

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val catalog: CatalogRepository,
    private val settingsRepository: SettingsRepository,
    private val podcastDao: PodcastDao,
    private val subscriptionRepository: SubscriptionRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _state.asStateFlow()

    private val _events = Channel<SearchEvent>(Channel.BUFFERED)
    val events: Flow<SearchEvent> = _events.receiveAsFlow()

    /** Bumped by [retry] to force a fresh request with the same query. */
    private val attempt = MutableStateFlow(0)

    private data class DiscoverRequest(val query: String, val category: String?, val attempt: Int)

    private val requests: Flow<DiscoverRequest?> = combine(
        _state.map { it.mode }.distinctUntilChanged(),
        _state.map { it.query.trim() }
            .distinctUntilChanged()
            .debounce { if (it.isEmpty()) 0L else DEBOUNCE_MS },
        _state.map { it.selectedCategory }.distinctUntilChanged(),
        attempt,
    ) { mode, query, category, attemptNo ->
        if (mode != SearchMode.DISCOVER) {
            null
        } else {
            // The category only matters for trending; a text search ignores it.
            DiscoverRequest(query, category.takeIf { query.isEmpty() }, attemptNo)
        }
    }.distinctUntilChanged()

    init {
        viewModelScope.launch {
            val persisted = settingsRepository.settings.first().searchMode
            val hasSubscriptions = podcastDao.getSubscribed().isNotEmpty()
            _state.update { it.copy(mode = if (hasSubscriptions) persisted else SearchMode.DISCOVER) }
        }

        podcastDao.observeSubscribed()
            .onEach { rows -> _state.update { it.copy(subscribedFeedUrls = rows.map { r -> r.feedUrl }.toSet()) } }
            .launchIn(viewModelScope)

        viewModelScope.launch {
            val categories = catalog.categories().take(MAX_CATEGORY_CHIPS)
            _state.update { it.copy(categories = categories) }
        }

        requests
            .flatMapLatest { request ->
                if (request == null) {
                    emptyFlow()
                } else {
                    flow {
                        emit(DiscoverState.Loading)
                        emit(load(request))
                    }
                }
            }
            .onEach { discover -> _state.update { it.copy(discover = discover) } }
            .launchIn(viewModelScope)
    }

    fun onQueryChange(query: String) {
        _state.update { it.copy(query = query) }
    }

    fun onModeChange(mode: SearchMode) {
        _state.update { it.copy(mode = mode) }
        viewModelScope.launch {
            settingsRepository.update { it.copy(searchMode = mode) }
        }
    }

    /** null selects "All". */
    fun onCategorySelected(name: String?) {
        _state.update { it.copy(selectedCategory = name) }
    }

    fun onResultClick(podcast: CatalogPodcast) {
        if (_state.value.openingFeedUrl != null) return
        _state.update { it.copy(openingFeedUrl = podcast.feedUrl) }
        viewModelScope.launch {
            val result = try {
                subscriptionRepository.open(podcast.feedUrl, podcast)
            } finally {
                _state.update { it.copy(openingFeedUrl = null) }
            }
            when (result) {
                is SubscriptionRepository.OpenResult.Ok ->
                    _events.send(SearchEvent.OpenPodcast(result.podcastId))
                is SubscriptionRepository.OpenResult.Failed ->
                    _events.send(SearchEvent.ShowMessage(result.message))
            }
        }
    }

    fun retry() {
        attempt.update { it + 1 }
    }

    private suspend fun load(request: DiscoverRequest): DiscoverState = try {
        val podcasts = if (request.query.isEmpty()) {
            catalog.trending(request.category)
        } else {
            catalog.search(request.query)
        }
        when {
            podcasts.isEmpty() -> DiscoverState.Empty
            request.query.isEmpty() -> DiscoverState.Trending(podcasts)
            else -> DiscoverState.Results(podcasts)
        }
    } catch (e: CatalogException) {
        DiscoverState.Error
    }

    private companion object {
        const val DEBOUNCE_MS = 300L
        const val MAX_CATEGORY_CHIPS = 20
    }
}
