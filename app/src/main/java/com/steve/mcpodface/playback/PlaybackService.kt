package com.steve.mcpodface.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import android.app.PendingIntent
import android.content.Intent
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.RemoteCastPlayer
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.steve.mcpodface.MainActivity
import com.steve.mcpodface.playback.auto.AutoCommands
import com.steve.mcpodface.playback.auto.BrowseInvalidator
import com.steve.mcpodface.playback.auto.NowPlayingButtons
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import javax.inject.Inject

/**
 * Background playback (PLAN.md §12). Media3 gives us the notification, lock screen, Bluetooth,
 * Galaxy Buds and Samsung quick-panel controls. LibraryCallback handles connections, custom commands
 * and the browse tree, and resolves mediaIds (episode ids) into a local file or stream.
 */
@AndroidEntryPoint
@OptIn(UnstableApi::class) // Media3 APIs marked unstable (ForwardingPlayer, skip silence, session tweaks)
class PlaybackService : MediaLibraryService() {

    @Inject lateinit var okHttpClient: OkHttpClient
    @Inject lateinit var engine: PlaybackEngine
    @Inject lateinit var libraryCallback: LibraryCallback
    @Inject lateinit var nowPlayingButtons: NowPlayingButtons
    @Inject lateinit var browseInvalidator: BrowseInvalidator
    @Inject lateinit var browseTree: BrowseTree

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var player: ExoPlayer
    private lateinit var castPlayer: CastPlayer
    private var session: MediaLibrarySession? = null

    override fun onCreate() {
        super.onCreate()
        val dataSources = DefaultDataSource.Factory(this, OkHttpDataSource.Factory(okHttpClient))
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSources))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        // Plays locally, and moves playback to a Chromecast when one is picked (and back again).
        castPlayer = CastPlayer.Builder(this)
            .setLocalPlayer(player)
            .setRemotePlayer(RemoteCastPlayer.Builder(this).setMediaItemConverter(EpisodeCastConverter()).build())
            .build()
        val sessionPlayer = SkipForwardingPlayer(castPlayer)
        engine.attach(player, castPlayer, sessionPlayer, scope)

        libraryCallback.scope = scope
        libraryCallback.engine = engine
        session = MediaLibrarySession.Builder(this, sessionPlayer, libraryCallback)
            .setSessionActivity(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .setMediaButtonPreferences(AutoCommands.skipButtons)
            .setCommandButtonsForMediaItems(AutoCommands.rowButtons)
            .build()
            .also { s ->
                nowPlayingButtons.start(scope, s)
                // Refresh car lists when the queue, downloads or episodes change.
                browseInvalidator.start(scope) { parentId ->
                    scope.launch { s.notifyChildrenChanged(parentId, browseTree.children(parentId).size, null) }
                }
            }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        // Last chance to persist the position; the DB write is quick.
        runBlocking {
            engine.savePosition()
            engine.endSession()
        }
        engine.detach()
        session?.run {
            release()
            session = null
        }
        castPlayer.release() // releases the local ExoPlayer too
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ROOT_ID = BrowseTree.ROOT
    }
}
