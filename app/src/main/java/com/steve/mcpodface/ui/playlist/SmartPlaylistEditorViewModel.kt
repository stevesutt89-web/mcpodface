package com.steve.mcpodface.ui.playlist

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.steve.mcpodface.core.Clock
import com.steve.mcpodface.data.db.EpisodeListItem
import com.steve.mcpodface.data.db.PlaylistKind
import com.steve.mcpodface.data.db.PodcastDao
import com.steve.mcpodface.data.db.PodcastEntity
import com.steve.mcpodface.data.playlist.PlaylistRepository
import com.steve.mcpodface.data.playlist.SmartRules
import com.steve.mcpodface.ui.navigation.SmartPlaylistEditorRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Everything the smart playlist editor shows. [preview] is null until the first preview arrives. */
data class SmartPlaylistEditorUiState(
    val isNew: Boolean = true,
    val loaded: Boolean = false,
    val notFound: Boolean = false,
    val name: String = "",
    val rules: SmartRules = SmartRules(),
    val subscriptions: List<PodcastEntity> = emptyList(),
    val preview: List<EpisodeListItem>? = null,
    val now: Long = 0L,
    val saving: Boolean = false,
    val canSave: Boolean = false,
)

private data class EditorForm(
    val loaded: Boolean = false,
    val notFound: Boolean = false,
    val name: String = "",
    val rules: SmartRules = SmartRules(),
    val saving: Boolean = false,
) {
    val canSave: Boolean get() = loaded && !notFound && !saving && name.isNotBlank()
}

/**
 * Creates or edits a smart playlist (PLAN.md §8.11). `playlistId` 0 = new. The live preview runs
 * the saved-rules query for the current rules, debounced so typing doesn't search per keystroke.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class SmartPlaylistEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val playlists: PlaylistRepository,
    podcasts: PodcastDao,
    private val clock: Clock,
) : ViewModel() {

    private val playlistId: Long = savedStateHandle.toRoute<SmartPlaylistEditorRoute>().playlistId
    val isNew: Boolean = playlistId == 0L

    private val form = MutableStateFlow(EditorForm(loaded = isNew))

    private val subscriptions: Flow<List<PodcastEntity>> = podcasts.observeSubscribed()

    /** Nothing is previewed until an edit's saved rules have loaded, so defaults never flash. */
    private val preview: Flow<List<EpisodeListItem>?> = form
        .filter { it.loaded }
        .map { it.rules }
        .distinctUntilChanged()
        .debounce(PREVIEW_DEBOUNCE_MS)
        // The debounce timer runs on Default, so it counts real time, not the Main dispatcher's.
        .flowOn(Dispatchers.Default)
        .flatMapLatest { playlists.smartEpisodes(it) }
        .map<List<EpisodeListItem>, List<EpisodeListItem>?> { it }
        .onStart { emit(null) }

    val uiState: StateFlow<SmartPlaylistEditorUiState> = combine(form, subscriptions, preview) { f, subs, prev ->
        SmartPlaylistEditorUiState(
            isNew = isNew,
            loaded = f.loaded,
            notFound = f.notFound,
            name = f.name,
            rules = f.rules,
            subscriptions = subs,
            preview = prev,
            now = clock.now(),
            saving = f.saving,
            canSave = f.canSave,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SmartPlaylistEditorUiState(isNew = isNew))

    init {
        if (!isNew) {
            viewModelScope.launch {
                val playlist = playlists.observePlaylist(playlistId).first()
                form.update { f ->
                    if (playlist?.kind == PlaylistKind.SMART) {
                        f.copy(loaded = true, name = playlist.name, rules = SmartRules.fromJson(playlist.rulesJson))
                    } else {
                        f.copy(loaded = true, notFound = true)
                    }
                }
            }
        }
    }

    fun onNameChange(name: String) {
        form.update { it.copy(name = name) }
    }

    fun onRulesChange(transform: (SmartRules) -> SmartRules) {
        form.update { it.copy(rules = transform(it.rules)) }
    }

    /** Adds or removes one podcast from the scope. */
    fun togglePodcast(podcastId: Long) = onRulesChange { rules ->
        val ids = rules.podcastIds
        rules.copy(podcastIds = if (podcastId in ids) ids - podcastId else (ids + podcastId).sorted())
    }

    /** Back to every subscription (an empty selection). */
    fun selectAllPodcasts() = onRulesChange { it.copy(podcastIds = emptyList()) }

    /**
     * Saves the name and rules and returns the playlist id, or null when nothing was saved (blank
     * name, or the editor hasn't loaded or found its playlist).
     */
    suspend fun save(): Long? {
        val f = form.value
        if (!f.canSave) return null
        form.update { it.copy(saving = true) }
        return try {
            val name = f.name.trim()
            if (isNew) {
                playlists.createSmart(name, f.rules)
            } else {
                playlists.rename(playlistId, name)
                playlists.updateRules(playlistId, f.rules)
                playlistId
            }
        } finally {
            form.update { it.copy(saving = false) }
        }
    }

    private companion object {
        const val PREVIEW_DEBOUNCE_MS = 300L
    }
}
