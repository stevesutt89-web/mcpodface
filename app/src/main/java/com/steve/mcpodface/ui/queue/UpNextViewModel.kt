package com.steve.mcpodface.ui.queue

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.steve.mcpodface.core.Clock
import com.steve.mcpodface.data.db.EpisodeDao
import com.steve.mcpodface.data.db.EpisodeListItem
import com.steve.mcpodface.data.db.EpisodeEntity
import com.steve.mcpodface.data.db.PlayState
import com.steve.mcpodface.data.db.PodcastDao
import com.steve.mcpodface.data.db.PodcastEntity
import com.steve.mcpodface.data.db.QueueDao
import com.steve.mcpodface.data.queue.QueueRepository
import com.steve.mcpodface.playback.NowPlaying
import com.steve.mcpodface.playback.PlayerController
import com.steve.mcpodface.ui.components.formatDuration
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import kotlin.math.max

private const val PERSIST_TIMEOUT_MS = 3_000L

// ---- Pure helpers (PLAN.md §8.8), unit-tested without Android ----------------------------------

/** Seconds still to listen: the full duration when unplayed, the rest when in progress, 0 when played. */
internal fun remainingSeconds(item: EpisodeListItem): Int {
    val episode = item.episode
    val duration = max(0, episode.durationSec ?: 0)
    return when (episode.playState) {
        PlayState.PLAYED -> 0
        PlayState.UNPLAYED -> duration
        PlayState.IN_PROGRESS -> (max(0L, duration * 1000L - episode.positionMs) / 1000L).toInt()
    }
}

internal fun upNextTotalSeconds(items: List<EpisodeListItem>): Int = items.sumOf { remainingSeconds(it) }

/** "5 episodes · 3 h 20 min"; just the count when no time is known. */
internal fun upNextSummary(items: List<EpisodeListItem>): String {
    val noun = if (items.size == 1) "episode" else "episodes"
    val time = formatDuration(upNextTotalSeconds(items))
    return if (time.isEmpty()) "${items.size} $noun" else "${items.size} $noun · $time"
}

/** Moves [fromId] to the index currently held by [toId]. Unchanged when either id is missing. */
internal fun reorderedIds(ids: List<Long>, fromId: Long, toId: Long): List<Long> {
    val from = ids.indexOf(fromId)
    val to = ids.indexOf(toId)
    if (from < 0 || to < 0 || from == to) return ids
    return ids.toMutableList().apply { add(to, removeAt(from)) }
}

/**
 * Shows the local drag [order] over the database list. Ids missing from the database are dropped,
 * and rows the order does not mention keep their database order at the end.
 */
internal fun applyOrder(items: List<EpisodeListItem>, order: List<Long>?): List<EpisodeListItem> {
    if (order == null) return items
    val byId = items.associateBy { it.episode.id }
    val ordered = order.mapNotNull { byId[it] }
    return ordered + items.filter { it.episode.id !in order }
}

/** The playing episode gets its own header unless it is already first in line. */
internal fun showNowPlayingHeader(nowPlayingId: Long, firstQueueId: Long?): Boolean = nowPlayingId != firstQueueId

// ---- ViewModel ---------------------------------------------------------------------------------

data class UpNextUiState(
    val items: List<EpisodeListItem> = emptyList(),
    /** False until the first database emission, so the empty state does not flash. */
    val loaded: Boolean = false,
    val nowPlaying: NowPlaying? = null,
    /** The playing episode, shown above the queue when it is not first in line. */
    val nowPlayingHeader: EpisodeListItem? = null,
    /** Wall-clock time for relative dates in the rows. */
    val now: Long = 0L,
)

/** One-shot feedback for the screen to show as a Snackbar. */
sealed interface UpNextMessage {
    /** [index] is where the episode sat, so undo can put it back there. */
    data class Removed(val episodeId: Long, val index: Int) : UpNextMessage
}

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class UpNextViewModel @Inject constructor(
    private val queue: QueueRepository,
    private val queueDao: QueueDao,
    private val episodes: EpisodeDao,
    private val podcasts: PodcastDao,
    private val player: PlayerController,
    private val clock: Clock,
) : ViewModel() {

    /**
     * Local order while a drag is in progress, and until the database confirms the persisted move.
     * Null means "show the database order".
     */
    private val order = MutableStateFlow<List<Long>?>(null)
    private var draggingId: Long? = null
    private var dragOriginIds: List<Long> = emptyList()

    private val messageChannel = Channel<UpNextMessage>(Channel.BUFFERED)
    val messages: Flow<UpNextMessage> = messageChannel.receiveAsFlow()

    /** The episode behind [PlayerController.nowPlaying], with its podcast, for the header row. */
    private val nowPlayingItem: Flow<EpisodeListItem?> = player.nowPlaying
        .map { it?.episodeId }
        .distinctUntilChanged()
        .flatMapLatest { id ->
            if (id == null) {
                flowOf(null)
            } else {
                episodes.observeById(id).flatMapLatest { episode ->
                    if (episode == null) flowOf(null) else podcasts.observeById(episode.podcastId).map { podcast ->
                        episodeListItem(episode, podcast)
                    }
                }
            }
        }

    val state: StateFlow<UpNextUiState> = combine(
        queueDao.observeQueueItems(),
        order,
        player.nowPlaying,
        nowPlayingItem,
    ) { dbItems, pending, nowPlaying, playingItem ->
        val items = applyOrder(dbItems, pending)
        val ids = items.map { it.episode.id }
        val header = playingItem
            ?.takeIf { showNowPlayingHeader(it.episode.id, ids.firstOrNull()) }
            ?.let { it.copy(inQueue = it.episode.id in ids) }
        UpNextUiState(
            items = items,
            loaded = true,
            nowPlaying = nowPlaying,
            nowPlayingHeader = header,
            now = clock.now(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UpNextUiState())

    fun togglePlay(episodeId: Long) = player.togglePlayPause(episodeId)

    /** Swipe to remove. Undo is driven by [UpNextMessage.Removed]. */
    fun remove(episodeId: Long) {
        val index = state.value.items.indexOfFirst { it.episode.id == episodeId }
        if (index < 0) return
        viewModelScope.launch {
            queue.remove(episodeId)
            messageChannel.send(UpNextMessage.Removed(episodeId, index))
        }
    }

    /** Re-adds the episode at the end, then moves it back to [index]. */
    fun undoRemove(episodeId: Long, index: Int) {
        viewModelScope.launch {
            queue.addLast(episodeId)
            val last = queueDao.getAll().lastIndex
            queue.move(last, index.coerceIn(0, last))
        }
    }

    fun clearQueue() {
        viewModelScope.launch { queue.clear() }
    }

    fun onDragStarted(episodeId: Long) {
        draggingId = episodeId
        dragOriginIds = state.value.items.map { it.episode.id }
    }

    /** Called on every swap while dragging. Only the local order changes; nothing is written yet. */
    fun onDragMove(fromId: Long, toId: Long) {
        val current = order.value ?: state.value.items.map { it.episode.id }
        order.value = reorderedIds(current, fromId, toId)
    }

    /** Persists the drag as one [QueueRepository.move] and keeps the local order until the DB shows it. */
    fun onDragStopped() {
        val id = draggingId ?: return
        val origin = dragOriginIds
        draggingId = null
        dragOriginIds = emptyList()
        val target = order.value ?: return
        val from = origin.indexOf(id)
        val to = target.indexOf(id)
        if (from < 0 || to < 0 || from == to) {
            order.value = null
            return
        }
        viewModelScope.launch {
            try {
                queue.move(from, to)
                withTimeoutOrNull(PERSIST_TIMEOUT_MS) {
                    queueDao.observeQueueItems().first { items -> items.map { it.episode.id } == target }
                }
            } finally {
                if (order.value == target) order.value = null
            }
        }
    }

    private fun episodeListItem(episode: EpisodeEntity, podcast: PodcastEntity?) = EpisodeListItem(
        episode = episode,
        podcastTitle = podcast?.title.orEmpty(),
        podcastArtworkUrl = podcast?.artworkUrl,
        downloadState = null,
        downloadBytes = null,
        inQueue = false,
    )
}
