package com.steve.mcpodface.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.steve.mcpodface.core.Clock
import com.steve.mcpodface.data.db.EpisodeDao
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
class PlaybackEngine @Inject constructor(
    private val episodeDao: EpisodeDao,
    private val podcastDao: PodcastDao,
    private val podcastSettingsDao: PodcastSettingsDao,
    private val settingsRepository: SettingsRepository,
    private val queue: QueueRepository,
    private val resolver: EpisodeMediaResolver,
    private val clock: Clock,
) {
    private lateinit var player: ExoPlayer
    private lateinit var skipPlayer: SkipForwardingPlayer
    private lateinit var scope: CoroutineScope

    private var settings = GlobalSettings()
    private var currentId: Long? = null
    private var skipOutroSec = 0
    private var markedPlayed = false
    private var completing = false
    private var ticker: Job? = null
    private var lastSavedAt = 0L

    fun attach(player: ExoPlayer, skipPlayer: SkipForwardingPlayer, scope: CoroutineScope) {
        this.player = player
        this.skipPlayer = skipPlayer
        this.scope = scope
        scope.launch {
            settingsRepository.settings.collect { s ->
                settings = s
                skipPlayer.skipBackMs = s.skipBackSec * 1_000L
                skipPlayer.skipForwardMs = s.skipForwardSec * 1_000L
                player.skipSilenceEnabled = s.trimSilence
            }
        }
        player.addListener(listener)
    }

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            onEpisodeStarted(mediaItem?.episodeId())
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) startTicker() else {
                ticker?.cancel()
                scope.launch { savePosition() }
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
        // TODO(5.2): trigger the cleanup policy; TODO(4.4): honour "sleep at end of episode".

        val next = queue.observeIds().first().firstOrNull() ?: nextInPodcast(id)
        if (next == null) {
            player.pause()
            player.seekTo(0)
            return
        }
        val item = resolver.resolve(next) ?: return
        val episode = episodeDao.getById(next) ?: return
        val start = PlaybackDecisions.startPosition(
            episode.positionMs, episode.playState, episode.lastPlayedAt, now, settings.resumeRewind,
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
    }
}
