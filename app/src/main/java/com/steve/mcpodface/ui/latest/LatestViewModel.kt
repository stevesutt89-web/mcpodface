package com.steve.mcpodface.ui.latest

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.steve.mcpodface.core.Clock
import com.steve.mcpodface.data.db.EpisodeListItem
import com.steve.mcpodface.data.db.PlayState
import com.steve.mcpodface.data.episodes.EpisodeRepository
import com.steve.mcpodface.data.feed.FeedRepository
import com.steve.mcpodface.data.prefs.LatestFilter
import com.steve.mcpodface.data.prefs.SettingsRepository
import com.steve.mcpodface.data.queue.QueueRepository
import com.steve.mcpodface.playback.NowPlaying
import com.steve.mcpodface.playback.PlayerController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Which `EpisodeRepository.observeLatest` arguments a filter maps to (PLAN.md §8.2). */
data class LatestQuery(val unplayedOnly: Boolean, val downloadedOnly: Boolean)

fun latestQueryFor(filter: LatestFilter): LatestQuery = when (filter) {
    LatestFilter.ALL -> LatestQuery(unplayedOnly = false, downloadedOnly = false)
    LatestFilter.UNPLAYED -> LatestQuery(unplayedOnly = true, downloadedOnly = false)
    LatestFilter.DOWNLOADED -> LatestQuery(unplayedOnly = false, downloadedOnly = true)
}

/** Swipe left toggles: an episode that is played becomes unplayed, anything else becomes played. */
fun playedToggleTarget(current: PlayState): Boolean = current != PlayState.PLAYED

data class LatestUiState(
    val items: List<EpisodeListItem> = emptyList(),
    val filter: LatestFilter = LatestFilter.ALL,
    /** False until the first list emission, so the screen does not flash an empty state. */
    val loaded: Boolean = false,
    /** Wall-clock time for relative dates in the rows. */
    val now: Long = 0L,
    val refreshing: Boolean = false,
    val nowPlaying: NowPlaying? = null,
)

/** One-shot feedback for the screen to show as a Snackbar. */
sealed interface LatestMessage {
    data class AddedToUpNext(val title: String) : LatestMessage

    /** [nowPlayed] is the new state; undo restores the opposite. */
    data class PlayedChanged(val episodeId: Long, val nowPlayed: Boolean) : LatestMessage
}

@HiltViewModel
class LatestViewModel @Inject constructor(
    private val episodes: EpisodeRepository,
    private val queue: QueueRepository,
    private val feeds: FeedRepository,
    private val settings: SettingsRepository,
    private val player: PlayerController,
    private val clock: Clock,
) : ViewModel() {

    private val refreshing = MutableStateFlow(false)
    private val messageChannel = Channel<LatestMessage>(Channel.BUFFERED)

    val messages: Flow<LatestMessage> = messageChannel.receiveAsFlow()

    val state: StateFlow<LatestUiState> = combine(
        settings.settings.map { it.latestFilter }.distinctUntilChanged().flatMapLatest { filter ->
            val query = latestQueryFor(filter)
            episodes.observeLatest(query.unplayedOnly, query.downloadedOnly).map { filter to it }
        },
        player.nowPlaying,
        refreshing,
    ) { (filter, items), nowPlaying, isRefreshing ->
        LatestUiState(
            items = items,
            filter = filter,
            loaded = true,
            now = clock.now(),
            refreshing = isRefreshing,
            nowPlaying = nowPlaying,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LatestUiState())

    fun setFilter(filter: LatestFilter) {
        viewModelScope.launch { settings.update { it.copy(latestFilter = filter) } }
    }

    fun togglePlay(episodeId: Long) = player.togglePlayPause(episodeId)

    fun addToUpNext(item: EpisodeListItem) {
        viewModelScope.launch {
            queue.addLast(item.episode.id)
            messageChannel.send(LatestMessage.AddedToUpNext(item.episode.title))
        }
    }

    fun togglePlayed(item: EpisodeListItem) {
        val target = playedToggleTarget(item.episode.playState)
        viewModelScope.launch {
            episodes.setPlayed(item.episode.id, target)
            messageChannel.send(LatestMessage.PlayedChanged(item.episode.id, nowPlayed = target))
        }
    }

    /** Undo for [LatestMessage.PlayedChanged]: restores the state before the toggle. */
    fun undoPlayedChange(episodeId: Long, nowPlayed: Boolean) {
        viewModelScope.launch { episodes.setPlayed(episodeId, !nowPlayed) }
    }

    fun toggleStar(item: EpisodeListItem) {
        viewModelScope.launch { episodes.setStarred(item.episode.id, !item.episode.isStarred) }
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
}
