package com.steve.mcpodface.ui.playlists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.steve.mcpodface.data.db.PlaylistWithCount
import com.steve.mcpodface.data.playlist.PlaylistRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PlaylistsUiState(
    /** The user's manual and smart playlists, in their order (PLAN.md §8.11). */
    val playlists: List<PlaylistWithCount> = emptyList(),
    /** False until the first emission, so the section does not flash. */
    val loaded: Boolean = false,
)

/** One-shot navigation from the Playlists tab. */
sealed interface PlaylistsEvent {
    data class OpenPlaylist(val playlistId: Long) : PlaylistsEvent
}

@HiltViewModel
class PlaylistsViewModel @Inject constructor(
    private val playlists: PlaylistRepository,
) : ViewModel() {

    private val eventChannel = Channel<PlaylistsEvent>(Channel.BUFFERED)
    val events: Flow<PlaylistsEvent> = eventChannel.receiveAsFlow()

    val state: StateFlow<PlaylistsUiState> = playlists.observePlaylists()
        .map { PlaylistsUiState(playlists = it, loaded = true) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlaylistsUiState())

    /** "New playlist": creates a manual playlist, then asks the screen to open it. */
    fun createPlaylist(name: String) {
        viewModelScope.launch {
            val id = playlists.createManual(name)
            eventChannel.send(PlaylistsEvent.OpenPlaylist(id))
        }
    }
}
