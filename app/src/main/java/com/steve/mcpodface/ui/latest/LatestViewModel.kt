package com.steve.mcpodface.ui.latest

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.steve.mcpodface.core.Clock
import com.steve.mcpodface.data.db.DownloadSource
import com.steve.mcpodface.data.db.DownloadState
import com.steve.mcpodface.data.db.EpisodeListItem
import com.steve.mcpodface.data.episodes.EpisodeRepository
import com.steve.mcpodface.data.feed.FeedRepository
import com.steve.mcpodface.data.playlist.PlaylistRepository
import com.steve.mcpodface.data.prefs.LatestFilter
import com.steve.mcpodface.data.prefs.SettingsRepository
import com.steve.mcpodface.data.queue.QueueRepository
import com.steve.mcpodface.download.DownloadManager
import com.steve.mcpodface.playback.NowPlaying
import com.steve.mcpodface.playback.PlayerController
import com.steve.mcpodface.ui.components.playedToggleTarget
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

/** What the long-press menu's download item does for a row, chosen from its download state (PLAN.md §8.2). */
enum class DownloadMenuAction { DOWNLOAD, REMOVE, CANCEL }

fun downloadMenuAction(state: DownloadState?): DownloadMenuAction = when (state) {
    DownloadState.COMPLETED -> DownloadMenuAction.REMOVE
    DownloadState.QUEUED, DownloadState.DOWNLOADING, DownloadState.PAUSED_NETWORK -> DownloadMenuAction.CANCEL
    // No row, or FAILED: a fresh manual download (FAILED re-queues over the old attempt).
    null, DownloadState.FAILED -> DownloadMenuAction.DOWNLOAD
}

fun downloadMenuLabel(state: DownloadState?): String = when (downloadMenuAction(state)) {
    DownloadMenuAction.DOWNLOAD -> "Download"
    DownloadMenuAction.REMOVE -> "Remove download"
    DownloadMenuAction.CANCEL -> "Cancel download"
}

data class LatestUiState(
    val items: List<EpisodeListItem> = emptyList(),
    val filter: LatestFilter = LatestFilter.ALL,
    /** False until the first list emission, so the screen does not flash an empty state. */
    val loaded: Boolean = false,
    /** Wall-clock time for relative dates in the rows. */
    val now: Long = 0L,
    val refreshing: Boolean = false,
    val nowPlaying: NowPlaying? = null,
    /** The episode whose manual download is waiting on the mobile-data prompt, or null (PLAN.md §6.6). */
    val cellularPromptEpisodeId: Long? = null,
    /** Episodes in Listen Later, for each row's swipe label (PLAN.md §8.11). */
    val listenLaterIds: Set<Long> = emptySet(),
)

/** One-shot feedback for the screen to show as a Snackbar. */
sealed interface LatestMessage {
    data class AddedToUpNext(val title: String) : LatestMessage

    data class ListenLaterChanged(val episodeId: Long, val added: Boolean) : LatestMessage

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
    private val downloads: DownloadManager,
    private val playlists: PlaylistRepository,
    private val clock: Clock,
) : ViewModel() {

    private val refreshing = MutableStateFlow(false)
    private val cellularPromptEpisodeId = MutableStateFlow<Long?>(null)
    private val messageChannel = Channel<LatestMessage>(Channel.BUFFERED)

    val messages: Flow<LatestMessage> = messageChannel.receiveAsFlow()

    val state: StateFlow<LatestUiState> = combine(
        settings.settings.map { it.latestFilter }.distinctUntilChanged().flatMapLatest { filter ->
            val query = latestQueryFor(filter)
            episodes.observeLatest(query.unplayedOnly, query.downloadedOnly).map { filter to it }
        },
        player.nowPlaying,
        refreshing,
        cellularPromptEpisodeId,
        playlists.observeListenLaterIds(),
    ) { (filter, items), nowPlaying, isRefreshing, promptEpisodeId, listenLater ->
        LatestUiState(
            items = items,
            filter = filter,
            loaded = true,
            now = clock.now(),
            refreshing = isRefreshing,
            nowPlaying = nowPlaying,
            cellularPromptEpisodeId = promptEpisodeId,
            listenLaterIds = listenLater,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LatestUiState())

    fun setFilter(filter: LatestFilter) {
        viewModelScope.launch { settings.update { it.copy(latestFilter = filter) } }
    }

    fun togglePlay(episodeId: Long) = player.togglePlayPause(episodeId)

    /** Swipe right: adds to Listen Later, or removes it when already there (PLAN.md §8.11). */
    fun toggleListenLater(episodeId: Long) {
        viewModelScope.launch {
            val added = playlists.toggleListenLater(episodeId)
            messageChannel.send(LatestMessage.ListenLaterChanged(episodeId, added))
        }
    }

    /** The long-press menu's "Add to Up Next". */
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

    /** The long-press menu's download item: download, remove or cancel, by the row's state. */
    fun onDownloadMenuAction(item: EpisodeListItem) {
        val id = item.episode.id
        when (downloadMenuAction(item.downloadState)) {
            DownloadMenuAction.DOWNLOAD -> requestDownload(item)
            DownloadMenuAction.REMOVE -> viewModelScope.launch { downloads.delete(id) }
            DownloadMenuAction.CANCEL -> viewModelScope.launch { downloads.cancel(id) }
        }
    }

    /** Queues a manual download, or asks first when it would run on mobile data (PLAN.md §6.6). */
    fun requestDownload(item: EpisodeListItem) {
        viewModelScope.launch {
            val id = item.episode.id
            if (downloads.needsCellularPrompt(id)) {
                cellularPromptEpisodeId.value = id
            } else {
                downloads.enqueue(id, DownloadSource.MANUAL)
            }
        }
    }

    /** The prompt's choice: "Download now" passes true, "Wait for Wi-Fi" passes false. */
    fun confirmCellularDownload(allowCellular: Boolean) {
        val id = cellularPromptEpisodeId.value ?: return
        cellularPromptEpisodeId.value = null
        viewModelScope.launch { downloads.enqueue(id, DownloadSource.MANUAL, allowCellular) }
    }

    fun dismissCellularPrompt() {
        cellularPromptEpisodeId.value = null
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
