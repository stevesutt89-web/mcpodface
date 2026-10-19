package com.steve.mcpodface.ui.playlist

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.steve.mcpodface.core.Clock
import com.steve.mcpodface.data.db.EpisodeListItem
import com.steve.mcpodface.data.db.PlaylistEntity
import com.steve.mcpodface.data.db.PlaylistKind
import com.steve.mcpodface.data.playlist.PlaylistRepository
import com.steve.mcpodface.data.queue.QueueRepository
import com.steve.mcpodface.playback.NowPlaying
import com.steve.mcpodface.playback.PlayerController
import com.steve.mcpodface.ui.navigation.PlaylistRoute
import com.steve.mcpodface.ui.queue.applyOrder
import com.steve.mcpodface.ui.queue.reorderedIds
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

private const val PERSIST_TIMEOUT_MS = 3_000L

/** "Play all" replaces Up Next in playlist order; "Shuffle" replaces it in a random order. */
enum class ReplaceAction { PLAY_ALL, SHUFFLE }

data class PlaylistUiState(
    val playlist: PlaylistEntity? = null,
    /** False until the first database emission, so the empty state does not flash. */
    val loaded: Boolean = false,
    /** The episodes in display order: the local drag order while dragging, else the playlist order. */
    val items: List<EpisodeListItem> = emptyList(),
    /** Set while Play all / Shuffle waits for confirmation because Up Next is not empty. */
    val pendingReplace: ReplaceAction? = null,
    val nowPlaying: NowPlaying? = null,
    /** Wall-clock time for relative dates in the rows. */
    val now: Long = 0L,
) {
    val isSmart: Boolean get() = playlist?.kind == PlaylistKind.SMART
    /** Only manual lists take drag and swipe edits; smart lists are read-only. */
    val isEditable: Boolean get() = playlist != null && !isSmart
}

/** One-shot feedback for the screen. */
sealed interface PlaylistEvent {
    data object Deleted : PlaylistEvent
}

/**
 * One playlist (PLAN.md §8.11): its episodes, Play all / Shuffle into Up Next, drag to reorder and
 * swipe to remove for manual lists, plus rename and delete.
 */
@HiltViewModel
class PlaylistViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val playlists: PlaylistRepository,
    private val queue: QueueRepository,
    private val player: PlayerController,
    private val clock: Clock,
) : ViewModel() {

    private val playlistId: Long = savedStateHandle.toRoute<PlaylistRoute>().playlistId

    /** Local order while a drag is in progress, and until the database confirms the persisted move. */
    private val order = MutableStateFlow<List<Long>?>(null)
    private val pendingReplace = MutableStateFlow<ReplaceAction?>(null)
    private var draggingId: Long? = null
    private var dragOriginIds: List<Long> = emptyList()

    private val eventChannel = Channel<PlaylistEvent>(Channel.BUFFERED)
    val events: Flow<PlaylistEvent> = eventChannel.receiveAsFlow()

    val state: StateFlow<PlaylistUiState> = combine(
        playlists.observePlaylist(playlistId),
        playlists.observeEpisodes(playlistId),
        order,
        pendingReplace,
        player.nowPlaying,
    ) { playlist, episodes, localOrder, pending, nowPlaying ->
        PlaylistUiState(
            playlist = playlist,
            loaded = true,
            items = applyOrder(episodes, localOrder),
            pendingReplace = pending,
            nowPlaying = nowPlaying,
            now = clock.now(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlaylistUiState())

    fun togglePlay(episodeId: Long) = player.togglePlayPause(episodeId)

    /** Play all: now, or after the confirm dialog when Up Next already has episodes. */
    fun playAll() = startReplace(ReplaceAction.PLAY_ALL)

    fun shuffle() = startReplace(ReplaceAction.SHUFFLE)

    /** The confirm dialog's "Replace": runs the pending action. */
    fun confirmReplace() {
        val action = pendingReplace.value ?: return
        pendingReplace.value = null
        viewModelScope.launch { replaceQueue(action) }
    }

    fun dismissReplace() {
        pendingReplace.value = null
    }

    private fun startReplace(action: ReplaceAction) {
        viewModelScope.launch {
            if (queue.observeIds().first().isEmpty()) {
                replaceQueue(action)
            } else {
                pendingReplace.value = action
            }
        }
    }

    /** Replaces Up Next with the playlist (shuffled if asked) and starts the first episode. */
    private suspend fun replaceQueue(action: ReplaceAction) {
        val ids = playlists.episodeIds(playlistId).let { if (action == ReplaceAction.SHUFFLE) it.shuffled() else it }
        val first = ids.firstOrNull() ?: return
        queue.replaceAll(ids)
        player.play(first)
    }

    /** Swipe left. The row disappears when the database emits again. */
    fun remove(episodeId: Long) {
        viewModelScope.launch { playlists.remove(playlistId, episodeId) }
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

    /** Persists the drag as one [PlaylistRepository.move] and keeps the local order until the DB shows it. */
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
                playlists.move(playlistId, from, to)
                withTimeoutOrNull(PERSIST_TIMEOUT_MS) {
                    playlists.observeEpisodes(playlistId).first { items -> items.map { it.episode.id } == target }
                }
            } finally {
                if (order.value == target) order.value = null
            }
        }
    }

    fun rename(name: String) {
        viewModelScope.launch { playlists.rename(playlistId, name) }
    }

    /** Deletes the playlist, then tells the screen to leave. */
    fun delete() {
        viewModelScope.launch {
            playlists.observePlaylist(playlistId).first() ?: return@launch
            playlists.delete(playlistId)
            eventChannel.send(PlaylistEvent.Deleted)
        }
    }
}
