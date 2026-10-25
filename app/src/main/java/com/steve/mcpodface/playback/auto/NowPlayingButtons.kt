package com.steve.mcpodface.playback.auto

import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import com.steve.mcpodface.data.db.AppDatabase
import com.steve.mcpodface.playback.SleepTimer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Keeps the session's media button preferences current (docs/ANDROID-AUTO.md, task A7): skip back
 * and forward always, plus the car overflow buttons (speed, star, mark played & next, sleep) while a
 * car is connected. Android Auto reads the same platform session as the phone's media controls, so
 * the overflow buttons are left out when no car is connected to keep the phone notification as it was.
 */
@OptIn(UnstableApi::class, ExperimentalCoroutinesApi::class)
class NowPlayingButtons @Inject constructor(
    private val db: AppDatabase,
    private val carActions: CarActions,
    private val sleepTimer: SleepTimer,
) {
    private val cars = MutableStateFlow<Set<MediaSession.ControllerInfo>>(emptySet())

    fun carConnected(controller: MediaSession.ControllerInfo) = cars.update { it + controller }

    fun carDisconnected(controller: MediaSession.ControllerInfo) = cars.update { it - controller }

    /** Watches the player, the current episode's star and the sleep timer for as long as [scope] lives. */
    fun start(scope: CoroutineScope, session: MediaSession) {
        val player = session.player
        val current = MutableStateFlow(player.currentMediaItem.episodeId())
        val speed = MutableStateFlow(player.playbackParameters.speed)
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                current.value = mediaItem.episodeId()
            }

            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                speed.value = playbackParameters.speed
            }
        })
        // Only the star matters here; the episode row also changes on every 10 s position save.
        val starred = current.flatMapLatest { id ->
            if (id == null) flowOf(null) else db.episodeDao().observeById(id).map { it?.isStarred }
        }.distinctUntilChanged()
        val carConnected = cars.map { it.isNotEmpty() }.distinctUntilChanged()
        scope.launch {
            combine(carConnected, current, starred, speed, sleepTimer.state) { car, id, _, _, _ -> car to id }
                .collectLatest { (car, id) ->
                    val overflow = if (car) carActions.overflowButtons(id, player) else emptyList()
                    session.setMediaButtonPreferences(AutoCommands.skipButtons + overflow)
                }
        }
    }

    private fun MediaItem?.episodeId(): Long? = this?.mediaId?.toLongOrNull()
}
