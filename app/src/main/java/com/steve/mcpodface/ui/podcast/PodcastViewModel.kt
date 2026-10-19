package com.steve.mcpodface.ui.podcast

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.steve.mcpodface.core.Clock
import com.steve.mcpodface.data.db.EpisodeListItem
import com.steve.mcpodface.data.db.FeedType
import com.steve.mcpodface.data.db.PodcastEntity
import com.steve.mcpodface.data.db.PodcastSettingsDao
import com.steve.mcpodface.data.db.PodcastSettingsEntity
import com.steve.mcpodface.data.db.SortOrder
import com.steve.mcpodface.data.episodes.EpisodeRepository
import com.steve.mcpodface.data.feed.FeedRepository
import com.steve.mcpodface.data.feed.SubscriptionRepository
import com.steve.mcpodface.data.playlist.PlaylistRepository
import com.steve.mcpodface.domain.policy.defaultSortOrder
import com.steve.mcpodface.playback.NowPlaying
import com.steve.mcpodface.playback.PlayerController
import com.steve.mcpodface.ui.components.playedToggleTarget
import com.steve.mcpodface.ui.navigation.PodcastRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Everything the podcast detail screen shows (PLAN.md §8.4). */
data class PodcastUiState(
    /** False until Room has emitted the podcast once, so a null podcast means "not found" rather than "loading". */
    val loaded: Boolean = false,
    val podcast: PodcastEntity? = null,
    val sortOrder: SortOrder = SortOrder.NEWEST_FIRST,
    val hidePlayed: Boolean = false,
    /** Every episode of the podcast, archived ones included, ignoring the hide-played filter. */
    val episodeCount: Int = 0,
    val episodes: List<EpisodeListItem> = emptyList(),
    val nowPlayingEpisodeId: Long? = null,
    val isPlaying: Boolean = false,
    val isRefreshing: Boolean = false,
    val now: Long = 0L,
    /** First fetch after opening from search is still running: show "Loading episodes…". */
    val feedLoading: Boolean = false,
    /** First fetch failed: show the message with a retry. */
    val feedError: String? = null,
    /** Episodes in Listen Later, for each row's swipe label (PLAN.md §8.11). */
    val listenLaterIds: Set<Long> = emptySet(),
) {
    val isLoading: Boolean get() = !loaded
    val notFound: Boolean get() = loaded && podcast == null
    val isSubscribed: Boolean get() = podcast?.isSubscribed == true
}

/** An explicit per-podcast override wins; otherwise serial feeds sort oldest first (PLAN.md §8.4, §6.1). */
internal fun effectiveSortOrder(settings: PodcastSettingsEntity?, feedType: FeedType?): SortOrder =
    settings?.sortOrder ?: defaultSortOrder(feedType ?: FeedType.EPISODIC)

internal fun SortOrder.toggled(): SortOrder =
    if (this == SortOrder.NEWEST_FIRST) SortOrder.OLDEST_FIRST else SortOrder.NEWEST_FIRST

@HiltViewModel
class PodcastViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val episodeRepository: EpisodeRepository,
    private val subscriptionRepository: SubscriptionRepository,
    private val feedRepository: FeedRepository,
    private val podcastSettingsDao: PodcastSettingsDao,
    private val playerController: PlayerController,
    private val playlists: PlaylistRepository,
    private val clock: Clock,
) : ViewModel() {

    private val podcastId: Long = savedStateHandle.toRoute<PodcastRoute>().podcastId

    private val hidePlayed = MutableStateFlow(false)
    private val isRefreshing = MutableStateFlow(false)

    private val podcast: Flow<PodcastEntity?> = episodeRepository.observePodcast(podcastId)
    private val settings: Flow<PodcastSettingsEntity?> = podcastSettingsDao.observe(podcastId)

    private val sortOrder: Flow<SortOrder> = combine(podcast, settings) { p, s ->
        effectiveSortOrder(s, p?.feedType)
    }.distinctUntilChanged()

    private val sortAndHide: Flow<Pair<SortOrder, Boolean>> = combine(sortOrder, hidePlayed) { s, h -> s to h }
        .distinctUntilChanged()

    private val episodes: Flow<List<EpisodeListItem>> = sortAndHide.flatMapLatest { (sort, hide) ->
        episodeRepository.observeForPodcast(podcastId, newestFirst = sort == SortOrder.NEWEST_FIRST, hidePlayed = hide)
    }

    private val episodeCount: Flow<Int> = episodeRepository
        .observeForPodcast(podcastId, newestFirst = true, hidePlayed = false)
        .map { it.size }
        .distinctUntilChanged()

    private val data: Flow<Data> = combine(podcast, sortAndHide, episodes, episodeCount) { p, (sort, hide), rows, count ->
        Data(p, sort, hide, rows, count)
    }

    /** Emits only once the podcast, settings and episode lists have all emitted, so `loaded` is true from then on. */
    private val firstFetch = subscriptionRepository.firstFetch.map { it[podcastId] }.distinctUntilChanged()

    val uiState: StateFlow<PodcastUiState> = combine(
        data,
        isRefreshing,
        playerController.nowPlaying,
        firstFetch,
        playlists.observeListenLaterIds(),
    ) { d, refreshing, np, ff, listenLater ->
        d.toState(refreshing, np, clock.now()).copy(
            feedLoading = ff == SubscriptionRepository.FirstFetch.Loading,
            feedError = (ff as? SubscriptionRepository.FirstFetch.Failed)?.message,
            listenLaterIds = listenLater,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PodcastUiState())

    private data class Data(
        val podcast: PodcastEntity?,
        val sortOrder: SortOrder,
        val hidePlayed: Boolean,
        val episodes: List<EpisodeListItem>,
        val episodeCount: Int,
    ) {
        fun toState(refreshing: Boolean, nowPlaying: NowPlaying?, now: Long) = PodcastUiState(
            loaded = true,
            podcast = podcast,
            sortOrder = sortOrder,
            hidePlayed = hidePlayed,
            episodeCount = episodeCount,
            episodes = episodes,
            nowPlayingEpisodeId = nowPlaying?.episodeId,
            isPlaying = nowPlaying?.isPlaying == true,
            isRefreshing = refreshing,
            now = now,
        )
    }

    fun onHidePlayedChange(hide: Boolean) {
        hidePlayed.value = hide
    }

    /** Flips the sort and saves it to PodcastSettingsEntity.sortOrder, keeping every other field. */
    fun toggleSortOrder() {
        viewModelScope.launch {
            val next = uiState.value.sortOrder.toggled()
            val existing = podcastSettingsDao.get(podcastId) ?: PodcastSettingsEntity(podcastId = podcastId)
            podcastSettingsDao.upsert(existing.copy(sortOrder = next))
        }
    }

    fun subscribe() {
        viewModelScope.launch { subscriptionRepository.subscribe(podcastId) }
    }

    fun unsubscribe(deleteDownloads: Boolean) {
        viewModelScope.launch { subscriptionRepository.unsubscribe(podcastId, deleteDownloads) }
    }

    fun markAllPlayed() {
        viewModelScope.launch { episodeRepository.markAllPlayed(podcastId) }
    }

    fun togglePlayPause(episodeId: Long) {
        playerController.togglePlayPause(episodeId)
    }

    /** Swipe right: Listen Later add or remove (PLAN.md §8.11). */
    fun toggleListenLater(episodeId: Long) {
        viewModelScope.launch { playlists.toggleListenLater(episodeId) }
    }

    /** Swipe left: played or unplayed. */
    fun togglePlayed(item: EpisodeListItem) {
        val target = playedToggleTarget(item.episode.playState)
        viewModelScope.launch { episodeRepository.setPlayed(item.episode.id, target) }
    }

    fun refresh() {
        if (subscriptionRepository.firstFetch.value[podcastId] is SubscriptionRepository.FirstFetch.Failed) {
            subscriptionRepository.startFirstFetch(podcastId)
            return
        }
        if (isRefreshing.value) return
        viewModelScope.launch {
            isRefreshing.value = true
            try {
                feedRepository.refresh(podcastId)
            } finally {
                isRefreshing.value = false
            }
        }
    }
}
