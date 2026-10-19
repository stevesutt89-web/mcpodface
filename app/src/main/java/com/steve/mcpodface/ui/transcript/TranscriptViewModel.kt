package com.steve.mcpodface.ui.transcript

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.steve.mcpodface.data.episodes.EpisodeRepository
import com.steve.mcpodface.data.transcripts.TranscriptRepository
import com.steve.mcpodface.data.transcripts.TranscriptSegment
import com.steve.mcpodface.data.transcripts.segmentIndexAt
import com.steve.mcpodface.playback.PlayerController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the transcript sheet shows. [episodeId] is null until [TranscriptViewModel.open] runs. */
data class TranscriptUiState(
    val loading: Boolean = true,
    val episodeId: Long? = null,
    /** The episode has a transcript URL (so an empty result means "could not load it"). */
    val hasTranscript: Boolean = false,
    val segments: List<TranscriptSegment> = emptyList(),
)

@HiltViewModel
class TranscriptViewModel @Inject constructor(
    private val episodeRepository: EpisodeRepository,
    private val transcripts: TranscriptRepository,
    private val playerController: PlayerController,
) : ViewModel() {

    private val _state = MutableStateFlow(TranscriptUiState())
    val state: StateFlow<TranscriptUiState> = _state.asStateFlow()

    /**
     * Index of the segment being spoken, or null when another episode is loaded or the position is
     * between segments. Emits only when the index changes, so the sheet redraws two or three times a
     * minute rather than twice a second.
     */
    val currentIndex: StateFlow<Int?> = combine(playerController.nowPlaying, _state) { nowPlaying, loaded ->
        if (nowPlaying == null || loaded.episodeId == null || nowPlaying.episodeId != loaded.episodeId) {
            null
        } else {
            segmentIndexAt(loaded.segments, nowPlaying.positionMs)
        }
    }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private var openedId: Long? = null

    /** Loads the transcript for [episodeId]. Repeat calls for the same episode do nothing. */
    fun open(episodeId: Long) {
        if (openedId == episodeId) return
        openedId = episodeId
        viewModelScope.launch {
            val url = episodeRepository.observeEpisode(episodeId).first()?.transcriptUrl?.takeIf { it.isNotBlank() }
            val segments = if (url == null) emptyList() else transcripts.load(url)
            _state.value = TranscriptUiState(
                loading = false,
                episodeId = episodeId,
                hasTranscript = url != null,
                segments = segments,
            )
        }
    }

    /**
     * A tapped segment. Seeks this episode there (resuming if paused) when it is loaded, otherwise
     * starts it from that position.
     */
    fun onSegmentClick(startMs: Long) {
        val episodeId = _state.value.episodeId ?: return
        val current = playerController.nowPlaying.value
        if (current?.episodeId == episodeId) {
            playerController.seekTo(startMs)
            if (!current.isPlaying) playerController.togglePlayPause(episodeId)
        } else {
            playerController.play(episodeId, startMs)
        }
    }
}
