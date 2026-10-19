package com.steve.mcpodface.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.steve.mcpodface.data.db.EpisodeEntity
import com.steve.mcpodface.data.db.PodcastEntity
import com.steve.mcpodface.data.db.PodcastSettingsDao
import com.steve.mcpodface.data.db.PodcastSettingsEntity
import com.steve.mcpodface.data.episodes.EpisodeRepository
import com.steve.mcpodface.data.prefs.SettingsRepository
import com.steve.mcpodface.playback.NowPlaying
import com.steve.mcpodface.playback.PlayerController
import com.steve.mcpodface.playback.SleepTimer
import com.steve.mcpodface.playback.SleepTimerState
import com.steve.mcpodface.core.Clock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject
import kotlin.math.roundToLong

/** Everything the mini and full player show for the loaded episode (PLAN.md §8.1, §8.6). */
data class PlayerUiState(
    val episodeId: Long,
    val title: String,
    val podcastTitle: String,
    val podcastId: Long,
    val artworkUrl: String?,
    val descriptionHtml: String?,
    val isPlaying: Boolean,
    val isBuffering: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val speed: Float,
    val skipBackSec: Int,
    val skipForwardSec: Int,
    /** Settings → Tint player from artwork. */
    val tintFromArtwork: Boolean = true,
)

/** What the sleep timer button shows. */
sealed interface SleepTimerUi {
    data object Off : SleepTimerUi
    data class Remaining(val ms: Long) : SleepTimerUi
    data object EndOfEpisode : SleepTimerUi
}

/** A choice from the sleep timer sheet. */
sealed interface SleepChoice {
    data class Minutes(val minutes: Int) : SleepChoice
    data object EndOfEpisode : SleepChoice
    data object Extend : SleepChoice
    data object Off : SleepChoice
}

/**
 * Shared by the mini player and the full player. State is null when nothing is loaded in the player,
 * which hides the mini player.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val playerController: PlayerController,
    private val episodeRepository: EpisodeRepository,
    private val podcastSettingsDao: PodcastSettingsDao,
    private val settingsRepository: SettingsRepository,
    private val sleepTimer: SleepTimer,
    private val clock: Clock,
) : ViewModel() {

    /** Sleep timer for the full player's button; counts down once a second while running. */
    val sleepTimerUi: StateFlow<SleepTimerUi> = sleepTimer.state
        .flatMapLatest { s ->
            when (s) {
                SleepTimerState.Off -> flowOf(SleepTimerUi.Off)
                SleepTimerState.EndOfEpisode -> flowOf(SleepTimerUi.EndOfEpisode)
                is SleepTimerState.Running -> flow {
                    while (true) {
                        emit(SleepTimerUi.Remaining((s.endsAt - clock.now()).coerceAtLeast(0)))
                        delay(1_000)
                    }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SleepTimerUi.Off)

    fun setSleepTimer(choice: SleepChoice) = when (choice) {
        is SleepChoice.Minutes -> sleepTimer.start(choice.minutes)
        SleepChoice.EndOfEpisode -> sleepTimer.stopAtEndOfEpisode()
        SleepChoice.Extend -> sleepTimer.extend(SleepTimer.EXTEND_MS)
        SleepChoice.Off -> sleepTimer.cancel()
    }

    /** One-off messages for a Snackbar (PlayerController.messages). */
    val messages: SharedFlow<String> = playerController.messages

    private val episode: Flow<EpisodeEntity?> = playerController.nowPlaying
        .map { it?.episodeId }
        .distinctUntilChanged()
        .flatMapLatest { id -> if (id == null) flowOf(null) else episodeRepository.observeEpisode(id) }

    private val podcast: Flow<PodcastEntity?> = episode
        .map { it?.podcastId }
        .distinctUntilChanged()
        .flatMapLatest { id -> if (id == null) flowOf(null) else episodeRepository.observePodcast(id) }

    /** Null when nothing is loaded. Keeps running while a screen collects it (5s grace). */
    val uiState: StateFlow<PlayerUiState?> = combine(
        playerController.nowPlaying,
        episode,
        podcast,
        settingsRepository.settings,
    ) { nowPlaying, ep, pod, settings ->
        if (nowPlaying == null || ep == null) {
            null
        } else {
            PlayerUiState(
                episodeId = ep.id,
                title = ep.title,
                podcastTitle = pod?.title.orEmpty(),
                podcastId = ep.podcastId,
                artworkUrl = ep.artworkUrl ?: pod?.artworkUrl,
                descriptionHtml = ep.descriptionHtml,
                isPlaying = nowPlaying.isPlaying,
                isBuffering = nowPlaying.isBuffering,
                positionMs = nowPlaying.positionMs,
                durationMs = fallbackDuration(nowPlaying, ep),
                speed = nowPlaying.speed,
                skipBackSec = settings.skipBackSec,
                skipForwardSec = settings.skipForwardSec,
                tintFromArtwork = settings.tintPlayer,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun togglePlayPause() {
        playerController.nowPlaying.value?.episodeId?.let { playerController.togglePlayPause(it) }
    }

    fun pause() = playerController.pause()

    fun seekTo(positionMs: Long) = playerController.seekTo(positionMs)

    fun skipBack() = playerController.skipBack()

    fun skipForward() = playerController.skipForward()

    /**
     * Applies [speed] now. With [rememberForPodcast] it is saved on the current podcast's settings
     * (other fields kept); otherwise it becomes the global default speed.
     */
    fun setSpeed(speed: Float, rememberForPodcast: Boolean) {
        playerController.setSpeed(speed)
        viewModelScope.launch {
            val episodeId = playerController.nowPlaying.value?.episodeId
            val podcastId = episodeId?.let { episodeRepository.observeEpisode(it).first()?.podcastId }
            if (rememberForPodcast && podcastId != null) {
                val current = podcastSettingsDao.get(podcastId) ?: PodcastSettingsEntity(podcastId = podcastId)
                podcastSettingsDao.upsert(current.copy(playbackSpeed = speed))
            } else {
                settingsRepository.update { it.copy(defaultSpeed = speed) }
            }
        }
    }

    private fun fallbackDuration(nowPlaying: NowPlaying, episode: EpisodeEntity): Long =
        if (nowPlaying.durationMs > 0) nowPlaying.durationMs else (episode.durationSec ?: 0).toLong() * 1000L
}

/** Clock-style time: "0:05", "32:10", "1:02:03". Negative input is shown as 0:00. */
internal fun formatClock(ms: Long): String {
    val totalSec = ms.coerceAtLeast(0L) / 1000L
    val hours = totalSec / 3600L
    val minutes = (totalSec % 3600L) / 60L
    val seconds = totalSec % 60L
    return if (hours > 0L) {
        String.format(Locale.ENGLISH, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.ENGLISH, "%d:%02d", minutes, seconds)
    }
}

/**
 * Real time left in the episode, given that playback runs at [speed] (PLAN.md §8.6): the remaining
 * media time divided by the speed. A non-positive speed is treated as 1x.
 */
internal fun remainingPlaybackMs(durationMs: Long, positionMs: Long, speed: Float): Long {
    val left = (durationMs - positionMs).coerceAtLeast(0L)
    val rate = if (speed > 0f) speed.toDouble() else 1.0
    return (left / rate).roundToLong()
}

/** The scrubber's right-hand label, e.g. "-32:10". */
internal fun formatRemaining(durationMs: Long, positionMs: Long, speed: Float): String =
    "-" + formatClock(remainingPlaybackMs(durationMs, positionMs, speed))

/** Speed label, e.g. "1.0×" or "1.5×". */
internal fun formatSpeed(speed: Float): String = String.format(Locale.ENGLISH, "%.1f×", speed)
