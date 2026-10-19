package com.steve.mcpodface.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.steve.mcpodface.core.Clock
import com.steve.mcpodface.data.db.EpisodeEntity
import com.steve.mcpodface.data.db.EpisodeListItem
import com.steve.mcpodface.data.episodes.EpisodeRepository
import com.steve.mcpodface.data.playlist.PlaylistRepository
import com.steve.mcpodface.playback.NowPlaying
import com.steve.mcpodface.playback.PlayerController
import com.steve.mcpodface.ui.components.playedToggleTarget
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class StarredUiState(
    val items: List<EpisodeListItem> = emptyList(),
    /** False until the first emission, so the empty state does not flash. */
    val loaded: Boolean = false,
    /** Wall-clock time for relative dates in the rows. */
    val now: Long = 0L,
    val nowPlaying: NowPlaying? = null,
    /** Episodes in Listen Later, for each row's swipe label (PLAN.md §8.11). */
    val listenLaterIds: Set<Long> = emptySet(),
)

/** Starred screen (PLAN.md §8.3): every starred episode, newest first. */
@HiltViewModel
class StarredViewModel @Inject constructor(
    private val episodes: EpisodeRepository,
    private val player: PlayerController,
    private val playlists: PlaylistRepository,
    private val clock: Clock,
) : ViewModel() {

    val state: StateFlow<StarredUiState> = combine(
        episodes.observeStarred(),
        player.nowPlaying,
        playlists.observeListenLaterIds(),
    ) { items, nowPlaying, listenLater ->
        StarredUiState(
            items = items,
            loaded = true,
            now = clock.now(),
            nowPlaying = nowPlaying,
            listenLaterIds = listenLater,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StarredUiState())

    fun togglePlay(episodeId: Long) = player.togglePlayPause(episodeId)

    fun removeStar(episodeId: Long) {
        viewModelScope.launch { episodes.setStarred(episodeId, false) }
    }

    /** Swipe right: Listen Later add or remove (PLAN.md §8.11). */
    fun toggleListenLater(episodeId: Long) {
        viewModelScope.launch { playlists.toggleListenLater(episodeId) }
    }

    /** Swipe left: played or unplayed. */
    fun togglePlayed(episode: EpisodeEntity) {
        val target = playedToggleTarget(episode.playState)
        viewModelScope.launch { episodes.setPlayed(episode.id, target) }
    }
}
