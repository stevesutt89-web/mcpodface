package com.steve.mcpodface.playback

import android.content.ComponentName
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.steve.mcpodface.core.di.ApplicationScope
import com.steve.mcpodface.data.prefs.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** PlayerController over a Media3 MediaController connected to PlaybackService. */
@Singleton
class Media3PlayerController @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope private val appScope: CoroutineScope,
    private val resolver: EpisodeMediaResolver,
    private val settingsRepository: SettingsRepository,
) : PlayerController {

    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    override val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    override val messages: SharedFlow<String> = _messages

    private var controller: MediaController? = null
    private var positionTicker: Job? = null

    init {
        // Connect early so the UI reflects playback that is already running (e.g. after reopening the app).
        launchMain { runCatching { controller() } }
    }

    override fun play(episodeId: Long, startPositionMs: Long?) {
        launchMain {
            if (!canPlay(episodeId)) return@launchMain
            val c = controller()
            val item = MediaItem.Builder().setMediaId(episodeId.toString()).build()
            // Without a position the service picks the saved one (PlaybackService.onSetMediaItems).
            if (startPositionMs != null) c.setMediaItem(item, startPositionMs) else c.setMediaItem(item)
            c.prepare()
            c.play()
        }
    }

    override fun togglePlayPause(episodeId: Long) {
        launchMain {
            val c = controller()
            if (c.currentMediaItem?.episodeId() == episodeId) {
                if (c.isPlaying) c.pause() else c.play()
            } else {
                play(episodeId)
            }
        }
    }

    override fun pause() = launchMain { controller?.pause() }

    override fun seekTo(positionMs: Long) = launchMain { controller().seekTo(positionMs) }

    override fun seekBy(deltaMs: Long) = launchMain {
        val c = controller()
        c.seekTo((c.currentPosition + deltaMs).coerceAtLeast(0))
    }

    override fun skipBack() = launchMain { controller().seekBack() }

    override fun skipForward() = launchMain { controller().seekForward() }

    override fun setSpeed(speed: Float) = launchMain { controller().setPlaybackSpeed(speed) }

    /** Respects "Stream on mobile data" (PLAN.md §6.6) for episodes that aren't downloaded. */
    private suspend fun canPlay(episodeId: Long): Boolean {
        if (resolver.localFile(episodeId) != null) return true
        if (settingsRepository.settings.first().streamOnCellular || !isMetered()) return true
        _messages.tryEmit("Streaming on mobile data is off. Download the episode or connect to Wi-Fi.")
        return false
    }

    private fun isMetered(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private fun launchMain(block: suspend () -> Unit) {
        appScope.launch(Dispatchers.Main.immediate) { block() }
    }

    private suspend fun controller(): MediaController = withContext(Dispatchers.Main.immediate) {
        controller?.takeIf { it.isConnected } ?: connect().also { c ->
            controller = c
            c.addListener(listener)
            publish(c)
        }
    }

    private suspend fun connect(): MediaController = suspendCancellableCoroutine { cont ->
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            try {
                cont.resume(future.get())
            } catch (e: Exception) {
                cont.resumeWithException(e)
            }
        }, ContextCompat.getMainExecutor(context))
        cont.invokeOnCancellation { MediaController.releaseFuture(future) }
    }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            publish(player)
            if (events.contains(Player.EVENT_IS_PLAYING_CHANGED)) {
                if (player.isPlaying) startTicker(player) else positionTicker?.cancel()
            }
        }
    }

    private fun startTicker(player: Player) {
        positionTicker?.cancel()
        positionTicker = appScope.launch(Dispatchers.Main.immediate) {
            while (isActive) {
                publish(player)
                delay(500)
            }
        }
    }

    private fun publish(player: Player) {
        val id = player.currentMediaItem?.episodeId()
        _nowPlaying.value = id?.let {
            NowPlaying(
                episodeId = it,
                isPlaying = player.isPlaying,
                positionMs = player.currentPosition.coerceAtLeast(0),
                durationMs = player.duration.coerceAtLeast(0),
                speed = player.playbackParameters.speed,
                isBuffering = player.playbackState == Player.STATE_BUFFERING,
            )
        }
    }
}
