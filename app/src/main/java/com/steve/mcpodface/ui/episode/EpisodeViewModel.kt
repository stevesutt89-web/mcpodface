package com.steve.mcpodface.ui.episode

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.steve.mcpodface.core.Clock
import com.steve.mcpodface.data.db.DownloadDao
import com.steve.mcpodface.data.db.DownloadEntity
import com.steve.mcpodface.data.db.DownloadSource
import com.steve.mcpodface.data.db.DownloadState
import com.steve.mcpodface.data.db.EpisodeEntity
import com.steve.mcpodface.data.db.PlayState
import com.steve.mcpodface.data.db.PodcastEntity
import com.steve.mcpodface.data.episodes.EpisodeRepository
import com.steve.mcpodface.data.queue.QueueRepository
import com.steve.mcpodface.download.DownloadManager
import com.steve.mcpodface.playback.NowPlaying
import com.steve.mcpodface.playback.PlayerController
import com.steve.mcpodface.ui.navigation.EpisodeRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Everything the episode detail screen shows (PLAN.md §8.5). */
data class EpisodeUiState(
    /** False until the episode flow has emitted once, so a null episode means "not found" rather than "loading". */
    val loaded: Boolean = false,
    val episode: EpisodeEntity? = null,
    val podcast: PodcastEntity? = null,
    val inQueue: Boolean = false,
    /** Null when there is no downloads row. */
    val download: DownloadEntity? = null,
    /** The "Download on mobile data?" prompt is up (PLAN.md §6.6). */
    val showCellularPrompt: Boolean = false,
    val nowPlaying: NowPlaying? = null,
    val now: Long = 0L,
) {
    val isLoading: Boolean get() = !loaded
    val notFound: Boolean get() = loaded && episode == null

    val downloadState: DownloadState? get() = download?.state

    /** This episode is loaded in the player, playing or paused. */
    val isCurrent: Boolean get() = episode != null && nowPlaying?.episodeId == episode.id
    val isPlayingThis: Boolean get() = isCurrent && nowPlaying?.isPlaying == true
    val isPlayed: Boolean get() = episode?.playState == PlayState.PLAYED
    val isInProgress: Boolean get() = episode?.playState == PlayState.IN_PROGRESS
    val isStarred: Boolean get() = episode?.isStarred == true
    val isDownloaded: Boolean get() = downloadState == DownloadState.COMPLETED

    val playLabel: String
        get() = when {
            isPlayingThis -> "Pause"
            isCurrent || isInProgress -> "Resume"
            else -> "Play"
        }

    /** Saved position, or the live one when this episode is loaded in the player. */
    val positionMs: Long
        get() = if (isCurrent) nowPlaying?.positionMs ?: 0L else episode?.positionMs ?: 0L
}

@HiltViewModel
class EpisodeViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val episodeRepository: EpisodeRepository,
    private val queueRepository: QueueRepository,
    private val downloadDao: DownloadDao,
    private val downloadManager: DownloadManager,
    private val playerController: PlayerController,
    private val clock: Clock,
) : ViewModel() {

    private val episodeId: Long = savedStateHandle.toRoute<EpisodeRoute>().episodeId

    private val episode: Flow<EpisodeEntity?> = episodeRepository.observeEpisode(episodeId)

    private val podcast: Flow<PodcastEntity?> = episode
        .map { it?.podcastId }
        .distinctUntilChanged()
        .flatMapLatest { podcastId ->
            if (podcastId == null) flowOf(null) else episodeRepository.observePodcast(podcastId)
        }

    private val inQueue: Flow<Boolean> = queueRepository.observeIds()
        .map { episodeId in it }
        .distinctUntilChanged()

    private val showCellularPrompt = MutableStateFlow(false)

    /** The download row paired with the prompt flag, so the combine below stays at five sources. */
    private val downloadAndPrompt: Flow<Pair<DownloadEntity?, Boolean>> = combine(
        downloadDao.observe(episodeId).distinctUntilChanged(),
        showCellularPrompt,
    ) { download, prompt -> download to prompt }

    /** Emits once every source has emitted, so `loaded` is true from then on. */
    val uiState: StateFlow<EpisodeUiState> = combine(
        episode,
        podcast,
        inQueue,
        downloadAndPrompt,
        playerController.nowPlaying,
    ) { ep, pod, queued, downloadAndPrompt, nowPlaying ->
        EpisodeUiState(
            loaded = true,
            episode = ep,
            podcast = pod,
            inQueue = queued,
            download = downloadAndPrompt.first,
            showCellularPrompt = downloadAndPrompt.second,
            nowPlaying = nowPlaying,
            now = clock.now(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EpisodeUiState())

    fun togglePlayed() {
        val ep = uiState.value.episode ?: return
        val markPlayed = !uiState.value.isPlayed
        viewModelScope.launch { episodeRepository.setPlayed(ep.id, played = markPlayed) }
    }

    fun toggleStarred() {
        val ep = uiState.value.episode ?: return
        val star = !uiState.value.isStarred
        viewModelScope.launch { episodeRepository.setStarred(ep.id, starred = star) }
    }

    fun toggleQueue() {
        val inUpNext = uiState.value.inQueue
        viewModelScope.launch {
            if (inUpNext) queueRepository.remove(episodeId) else queueRepository.addFirst(episodeId)
        }
    }

    fun togglePlayPause() {
        playerController.togglePlayPause(episodeId)
    }

    /**
     * A tapped show-notes timestamp. If this episode is already loaded, seek there (resuming if paused);
     * otherwise start playback from that position.
     */
    fun onTimestampClick(positionMs: Long) {
        val current = playerController.nowPlaying.value
        if (current?.episodeId == episodeId) {
            playerController.seekTo(positionMs)
            if (!current.isPlaying) playerController.togglePlayPause(episodeId)
        } else {
            playerController.play(episodeId, positionMs)
        }
    }

    /**
     * The download button. Asks first when a manual download would run on mobile data (PLAN.md §6.6);
     * otherwise queues it straight away.
     */
    fun requestDownload() {
        viewModelScope.launch {
            if (downloadManager.needsCellularPrompt(episodeId)) {
                showCellularPrompt.value = true
            } else {
                downloadManager.enqueue(episodeId, DownloadSource.MANUAL)
            }
        }
    }

    /** The prompt's choice: "Download now" passes true, "Wait for Wi-Fi" passes false. */
    fun confirmCellularDownload(allowCellular: Boolean) {
        showCellularPrompt.value = false
        viewModelScope.launch { downloadManager.enqueue(episodeId, DownloadSource.MANUAL, allowCellular) }
    }

    fun dismissCellularPrompt() {
        showCellularPrompt.value = false
    }

    fun cancelDownload() {
        viewModelScope.launch { downloadManager.cancel(episodeId) }
    }

    /** "Remove download", after the screen has confirmed it. */
    fun deleteDownload() {
        viewModelScope.launch { downloadManager.delete(episodeId) }
    }

    fun retryDownload() {
        viewModelScope.launch { downloadManager.retry(episodeId) }
    }
}
