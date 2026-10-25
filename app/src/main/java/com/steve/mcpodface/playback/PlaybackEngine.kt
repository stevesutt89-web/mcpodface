package com.steve.mcpodface.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import android.media.audiofx.LoudnessEnhancer
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.steve.mcpodface.core.Clock
import com.steve.mcpodface.data.db.EpisodeDao
import com.steve.mcpodface.data.db.ListeningSessionDao
import com.steve.mcpodface.data.db.PlayState
import com.steve.mcpodface.data.db.PodcastDao
import com.steve.mcpodface.data.db.PodcastSettingsDao
import com.steve.mcpodface.data.db.SortOrder
import com.steve.mcpodface.data.prefs.GlobalSettings
import com.steve.mcpodface.data.prefs.SettingsRepository
import com.steve.mcpodface.data.queue.QueueRepository
import com.steve.mcpodface.domain.policy.PlayedRule
import com.steve.mcpodface.domain.policy.defaultSortOrder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Lives inside PlaybackService. Applies per-podcast settings when an episode starts, saves the
 * position every 10s and on pause, marks episodes played (PLAN.md §6.0), skips outros, and moves
 * on to the next Up Next item (or the next episode of the podcast if autoplay is on) at the end.
 */
@OptIn(UnstableApi::class) // Media3 APIs marked unstable (ForwardingPlayer, skip silence, session tweaks)
class PlaybackEngine @Inject constructor(
    private val episodeDao: EpisodeDao,
    private val podcastDao: PodcastDao,
    private val podcastSettingsDao: PodcastSettingsDao,
    private val settingsRepository: SettingsRepository,
    private val queue: QueueRepository,
    private val resolver: EpisodeMediaResolver,
    private val clock: Clock,
    private val listeningSessionDao: ListeningSessionDao,
    private val sleepTimer: SleepTimer,
) {
    private val sessions = ListeningSessionRecorder(clock)

    /** The local ExoPlayer, for what only it has: trim silence, the audio session, volume fades. */
    private lateinit var exo: ExoPlayer

    /** The session's player: local or Chromecast (Media3's CastPlayer switches between them). */
    private lateinit var player: Player
    private lateinit var skipPlayer: SkipForwardingPlayer
    private lateinit var scope: CoroutineScope

    private var settings = GlobalSettings()
    private var currentId: Long? = null
    private var skipOutroSec = 0
    private var markedPlayed = false
    private var completing = false
    private var ticker: Job? = null
    private var lastSavedAt = 0L
    private var loudness: LoudnessEnhancer? = null

    fun attach(exo: ExoPlayer, player: Player, skipPlayer: SkipForwardingPlayer, scope: CoroutineScope) {
        this.exo = exo
        this.player = player
        this.skipPlayer = skipPlayer
        this.scope = scope
        scope.launch {
            settingsRepository.settings.collect { s ->
                settings = s
                skipPlayer.skipBackMs = s.skipBackSec * 1_000L
                skipPlayer.skipForwardMs = s.skipForwardSec * 1_000L
                exo.skipSilenceEnabled = s.trimSilence
                applyVoiceBoost()
            }
        }
        // Sleep timer: fade the volume over its last seconds, then pause. The fade is local only, so
        // it never changes a Chromecast's (or TV's) own volume.
        sleepTimer.onExpire = { player.pause() }
        scope.launch { sleepTimer.volume.collect { exo.volume = it } }
        player.addListener(listener)
        exo.addListener(audioSessionListener)
    }

    /** Releases audio effects. Called when the service is destroyed. */
    fun detach() {
        sleepTimer.onExpire = {}
        loudness?.release()
        loudness = null
    }

    /** Voice boost (PLAN.md §8.6): a gentle loudness lift on the player's audio session. */
    private fun applyVoiceBoost() {
        val session = exo.audioSessionId
        if (!settings.voiceBoost || session == C.AUDIO_SESSION_ID_UNSET) {
            loudness?.enabled = false
            return
        }
        try {
            val effect = loudness ?: LoudnessEnhancer(session).also { loudness = it }
            effect.setTargetGain(VOICE_BOOST_MB)
            effect.enabled = true
        } catch (e: RuntimeException) {
            Log.w("PlaybackEngine", "Voice boost unavailable", e) // some devices lack the effect
        }
    }

    private val audioSessionListener = object : Player.Listener {
        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            loudness?.release()
            loudness = null
            applyVoiceBoost()
        }
    }

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            scope.launch { endSession() }
            onEpisodeStarted(mediaItem?.episodeId())
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                startTicker()
                currentId?.let { sessions.start(it, player.playbackParameters.speed) }
            } else {
                ticker?.cancel()
                scope.launch {
                    savePosition()
                    endSession()
                }
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) scope.launch { complete() }
        }
    }

    private fun onEpisodeStarted(episodeId: Long?) {
        currentId = episodeId
        markedPlayed = false
        completing = false
        skipOutroSec = 0
        if (episodeId == null) return
        scope.launch {
            val episode = episodeDao.getById(episodeId) ?: return@launch
            val podcastSettings = podcastSettingsDao.get(episode.podcastId)
            skipOutroSec = podcastSettings?.skipOutroSec ?: 0
            markedPlayed = episode.playState == PlayState.PLAYED && episode.positionMs > 0
            val speed = podcastSettings?.playbackSpeed ?: settings.defaultSpeed
            player.playbackParameters = PlaybackParameters(speed)
        }
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                delay(1_000)
                tick()
            }
        }
    }

    private suspend fun tick() {
        val id = currentId ?: return
        val pos = player.currentPosition
        val duration = player.duration.coerceAtLeast(0)
        if (duration > 0) {
            val reachedOutro = skipOutroSec > 0 && pos >= duration - skipOutroSec * 1_000L
            if (reachedOutro) {
                complete()
                return
            }
            if (!markedPlayed && PlayedRule.isPlayed(pos, duration, settings.markPlayedPercent)) {
                markedPlayed = true
                episodeDao.setPlayState(id, PlayState.PLAYED, playedAt = clock.now(), positionMs = pos)
            }
        }
        if (clock.now() - lastSavedAt >= SAVE_INTERVAL_MS) savePosition()
    }

    /** Saves the current position. Safe to call on pause, on a timer and on service destroy. */
    suspend fun savePosition() = withContext(NonCancellable) {
        val id = currentId ?: return@withContext
        if (completing) return@withContext
        val pos = player.currentPosition
        if (pos <= 0) return@withContext
        lastSavedAt = clock.now()
        val state = if (markedPlayed) PlayState.PLAYED else PlayState.IN_PROGRESS
        episodeDao.updatePosition(id, pos, state, lastSavedAt)
    }

    /** Records the listening session in progress, if any. Also called when the service stops. */
    suspend fun endSession() = withContext(NonCancellable) {
        sessions.stop()?.let { listeningSessionDao.insert(it) }
    }

    /** End of episode (or outro reached): mark played, drop it from Up Next, play what's next. */
    private suspend fun complete() {
        val id = currentId ?: return
        if (completing) return
        completing = true
        val now = clock.now()
        if (!markedPlayed) episodeDao.setPlayState(id, PlayState.PLAYED, playedAt = now, positionMs = 0)
        episodeDao.updatePosition(id, 0, PlayState.PLAYED, now)
        markedPlayed = true
        queue.remove(id)

        val next = if (sleepTimer.consumeEndOfEpisode()) null else nextEpisode(id)
        if (next == null) {
            player.pause()
            player.seekTo(0)
            return
        }
        playNext(next, now)
    }

    /** Mark the current episode played and move on, as at the end of the episode (car "Mark played & next"). */
    suspend fun completeNow() = complete()

    private suspend fun nextEpisode(id: Long): Long? =
        queue.observeIds().first().firstOrNull() ?: nextInPodcast(id)

    private suspend fun playNext(next: Long, now: Long) {
        val item = resolver.resolve(next) ?: return
        val episode = episodeDao.getById(next) ?: return
        val start = PlaybackDecisions.startPosition(
            episode.positionMs, episode.playState, episode.lastPlayedAt, now, settings.resumeRewind,
            settings.resumeRewindSec, settings.longPauseMinutes,
            podcastSettingsDao.get(episode.podcastId)?.skipIntroSec ?: 0, episode.durationSec?.times(1_000L),
        )
        player.setMediaItem(item, start)
        player.prepare()
        player.play()
    }

    private suspend fun nextInPodcast(episodeId: Long): Long? {
        if (!settings.autoplayNext) return null
        val episode = episodeDao.getById(episodeId) ?: return null
        val podcast = podcastDao.getById(episode.podcastId) ?: return null
        val order = podcastSettingsDao.get(podcast.id)?.sortOrder ?: defaultSortOrder(podcast.feedType)
        return episodeDao.getNextUnplayedInPodcast(podcast.id, episode.pubDate, episode.id, order == SortOrder.NEWEST_FIRST)?.id
    }

    companion object {
        const val SAVE_INTERVAL_MS = 10_000L
        /** Voice boost gain in millibels (+6 dB). */
        const val VOICE_BOOST_MB = 600
    }
}
