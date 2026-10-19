package com.steve.mcpodface.ui.playlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.steve.mcpodface.data.db.PlaylistKind
import com.steve.mcpodface.data.playlist.PlaylistRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One playlist in the "Add to playlist" sheet. [contains] draws the tick. */
data class AddToPlaylistRow(
    val playlistId: Long,
    val name: String,
    val kind: PlaylistKind,
    val itemCount: Int,
    val contains: Boolean,
)

data class AddToPlaylistUiState(
    /** The manual playlists. Smart playlists are not offered. */
    val rows: List<AddToPlaylistRow> = emptyList(),
    /** False until the first emission for the bound episode. */
    val loaded: Boolean = false,
)

/**
 * State for the "Add to playlist" sheet (PLAN.md §8.11). The sheet is not a route, so the episode
 * is handed in with [bind] instead of through the nav arguments.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AddToPlaylistViewModel @Inject constructor(
    private val playlists: PlaylistRepository,
) : ViewModel() {

    private val boundEpisodeId = MutableStateFlow<Long?>(null)

    val state: StateFlow<AddToPlaylistUiState> = boundEpisodeId.flatMapLatest { episodeId ->
        if (episodeId == null) {
            flowOf(AddToPlaylistUiState())
        } else {
            combine(playlists.observePlaylists(), playlists.observePlaylistIdsFor(episodeId)) { lists, inIds ->
                AddToPlaylistUiState(
                    rows = lists
                        .filter { it.playlist.kind != PlaylistKind.SMART }
                        .map { p ->
                            AddToPlaylistRow(
                                playlistId = p.playlist.id,
                                name = p.playlist.name,
                                kind = p.playlist.kind,
                                itemCount = p.itemCount,
                                contains = p.playlist.id in inIds,
                            )
                        },
                    loaded = true,
                )
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AddToPlaylistUiState())

    fun bind(episodeId: Long) {
        boundEpisodeId.value = episodeId
    }

    /** Adds the bound episode when it's missing from [playlistId], otherwise removes it. */
    fun toggle(playlistId: Long) {
        val episodeId = boundEpisodeId.value ?: return
        if (state.value.rows.none { it.playlistId == playlistId }) return
        viewModelScope.launch {
            val inList = playlistId in playlists.observePlaylistIdsFor(episodeId).first()
            if (inList) playlists.remove(playlistId, episodeId) else playlists.add(playlistId, episodeId)
        }
    }

    /** "New playlist": creates a manual playlist and adds the bound episode to it. */
    fun createPlaylistAndAdd(name: String) {
        val episodeId = boundEpisodeId.value ?: return
        if (name.isBlank()) return
        viewModelScope.launch {
            val playlistId = playlists.createManual(name)
            playlists.add(playlistId, episodeId)
        }
    }
}
