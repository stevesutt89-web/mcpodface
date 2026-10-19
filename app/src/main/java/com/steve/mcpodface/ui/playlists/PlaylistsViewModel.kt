package com.steve.mcpodface.ui.playlists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.steve.mcpodface.data.db.DownloadDao
import com.steve.mcpodface.data.db.DownloadState
import com.steve.mcpodface.data.db.EpisodeDao
import com.steve.mcpodface.data.db.PlaylistWithCount
import com.steve.mcpodface.data.playlist.PlaylistRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Tiles in a playlist mosaic (2×2, like Spotify). */
const val MOSAIC_TILES = 4

/** The first [MOSAIC_TILES] distinct, non-blank artwork URLs, in list order. */
fun mosaicArtworks(urls: List<String?>): List<String> =
    urls.asSequence().filterNotNull().filter { it.isNotBlank() }.distinct().take(MOSAIC_TILES).toList()

data class PlaylistsUiState(
    /** The user's manual and smart playlists, in their order (PLAN.md §8.11). */
    val playlists: List<PlaylistWithCount> = emptyList(),
    /** Playlist id → up to four distinct podcast artworks for its mosaic. */
    val artworks: Map<Long, List<String>> = emptyMap(),
    val starredArtworks: List<String> = emptyList(),
    val starredCount: Int = 0,
    val downloadArtworks: List<String> = emptyList(),
    val downloadCount: Int = 0,
    /** False until the first emission, so the grid does not flash. */
    val loaded: Boolean = false,
)

/** One-shot navigation from the Playlists tab. */
sealed interface PlaylistsEvent {
    data class OpenPlaylist(val playlistId: Long) : PlaylistsEvent
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PlaylistsViewModel @Inject constructor(
    private val playlists: PlaylistRepository,
    episodeDao: EpisodeDao,
    downloadDao: DownloadDao,
) : ViewModel() {

    private val eventChannel = Channel<PlaylistsEvent>(Channel.BUFFERED)
    val events: Flow<PlaylistsEvent> = eventChannel.receiveAsFlow()

    /** Each playlist with its mosaic artworks, re-read when the playlists or their episodes change. */
    private val playlistsWithArt: Flow<Pair<List<PlaylistWithCount>, Map<Long, List<String>>>> =
        playlists.observePlaylists().flatMapLatest { list ->
            if (list.isEmpty()) {
                flowOf(list to emptyMap())
            } else {
                combine(
                    list.map { entry ->
                        val id = entry.playlist.id
                        playlists.observeEpisodes(id)
                            .map { items -> id to mosaicArtworks(items.map { it.podcastArtworkUrl }) }
                            .distinctUntilChanged()
                    },
                ) { pairs -> list to pairs.toMap() }
            }
        }

    private val starred: Flow<Pair<List<String>, Int>> = episodeDao.observeStarred()
        .map { items -> mosaicArtworks(items.map { it.podcastArtworkUrl }) to items.size }
        .distinctUntilChanged()

    private val downloads: Flow<Pair<List<String>, Int>> = downloadDao.observeDownloadItems()
        .map { items ->
            val done = items.filter { it.download.state == DownloadState.COMPLETED }
                .sortedByDescending { it.download.downloadedAt ?: 0L }
            // One tile per podcast: the first artwork seen for each.
            mosaicArtworks(done.distinctBy { it.podcastId }.map { it.artworkUrl }) to done.size
        }
        .distinctUntilChanged()

    val state: StateFlow<PlaylistsUiState> = combine(playlistsWithArt, starred, downloads) { (list, art), star, down ->
        PlaylistsUiState(
            playlists = list,
            artworks = art,
            starredArtworks = star.first,
            starredCount = star.second,
            downloadArtworks = down.first,
            downloadCount = down.second,
            loaded = true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlaylistsUiState())

    /** "New playlist": creates a manual playlist, then asks the screen to open it. */
    fun createPlaylist(name: String) {
        viewModelScope.launch {
            val id = playlists.createManual(name)
            eventChannel.send(PlaylistsEvent.OpenPlaylist(id))
        }
    }
}
