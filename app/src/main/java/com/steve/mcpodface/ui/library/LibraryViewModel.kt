package com.steve.mcpodface.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.steve.mcpodface.data.db.AppDatabase
import com.steve.mcpodface.data.db.LibraryPodcast
import com.steve.mcpodface.data.feed.FeedRepository
import com.steve.mcpodface.data.feed.SubscriptionRepository
import com.steve.mcpodface.data.prefs.LibraryLayout
import com.steve.mcpodface.data.prefs.LibrarySort
import com.steve.mcpodface.data.prefs.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

private val LEADING_THE = Regex("^the ", RegexOption.IGNORE_CASE)

/** Title used for A–Z ordering: trimmed, with a leading "The " ignored (PLAN.md §8.3). */
internal fun librarySortTitle(title: String): String = title.trim().replaceFirst(LEADING_THE, "")

private val byTitle: Comparator<LibraryPodcast> = Comparator { a, b ->
    String.CASE_INSENSITIVE_ORDER.compare(librarySortTitle(a.podcast.title), librarySortTitle(b.podcast.title))
}

/** Descending by [key], with podcasts that have no value last. */
private fun nullsLastDescending(key: (LibraryPodcast) -> Long?): Comparator<LibraryPodcast> =
    Comparator { a, b ->
        val x = key(a)
        val y = key(b)
        when {
            x == null && y == null -> 0
            x == null -> 1
            y == null -> -1
            else -> y.compareTo(x)
        }
    }

/**
 * Orders the Library grid (PLAN.md §8.3). The DAO returns alphabetical order; the other modes are
 * sorted here. Ties fall back to the title.
 */
fun sortLibrary(podcasts: List<LibraryPodcast>, sort: LibrarySort): List<LibraryPodcast> = when (sort) {
    LibrarySort.ALPHABETICAL -> podcasts.sortedWith(byTitle)
    LibrarySort.RECENTLY_UPDATED ->
        podcasts.sortedWith(nullsLastDescending { it.podcast.lastNewEpisodeAt }.then(byTitle))
    LibrarySort.RECENTLY_ADDED ->
        podcasts.sortedWith(nullsLastDescending { it.podcast.subscribedAt }.then(byTitle))
}

data class LibraryUiState(
    val podcasts: List<LibraryPodcast> = emptyList(),
    val sort: LibrarySort = LibrarySort.RECENTLY_UPDATED,
    val layout: LibraryLayout = LibraryLayout.GRID,
    /** False until the first emission, so the empty state does not flash. */
    val loaded: Boolean = false,
    val refreshing: Boolean = false,
)

@HiltViewModel
class LibraryViewModel @Inject constructor(
    db: AppDatabase,
    private val settings: SettingsRepository,
    private val feeds: FeedRepository,
    private val subscriptions: SubscriptionRepository,
) : ViewModel() {

    private val refreshing = MutableStateFlow(false)

    val state: StateFlow<LibraryUiState> = combine(
        db.podcastDao().observeLibrary(),
        settings.settings.map { it.librarySort to it.libraryLayout }.distinctUntilChanged(),
        refreshing,
    ) { podcasts, (sort, layout), isRefreshing ->
        LibraryUiState(
            podcasts = sortLibrary(podcasts, sort),
            sort = sort,
            layout = layout,
            loaded = true,
            refreshing = isRefreshing,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    fun setSort(sort: LibrarySort) {
        viewModelScope.launch { settings.update { it.copy(librarySort = sort) } }
    }

    fun setLayout(layout: LibraryLayout) {
        viewModelScope.launch { settings.update { it.copy(libraryLayout = layout) } }
    }

    fun refresh() {
        viewModelScope.launch {
            refreshing.value = true
            try {
                feeds.refreshAll()
            } finally {
                refreshing.value = false
            }
        }
    }

    fun unsubscribe(podcastId: Long, deleteDownloads: Boolean) {
        viewModelScope.launch { subscriptions.unsubscribe(podcastId, deleteDownloads) }
    }
}
