package com.steve.mcpodface.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.steve.mcpodface.MainActivity
import com.steve.mcpodface.core.Clock
import com.steve.mcpodface.data.db.EpisodeDao
import com.steve.mcpodface.data.db.PodcastSettingsDao
import com.steve.mcpodface.data.prefs.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import javax.inject.Inject

/**
 * Background playback (PLAN.md §12). Media3 gives us the notification, lock screen, Bluetooth,
 * Galaxy Buds and Samsung quick-panel controls. MediaItems arrive with only a mediaId (the episode
 * id); onSetMediaItems resolves the URI (local file or stream) and the start position.
 */
@AndroidEntryPoint
class PlaybackService : MediaLibraryService() {

    @Inject lateinit var okHttpClient: OkHttpClient
    @Inject lateinit var resolver: EpisodeMediaResolver
    @Inject lateinit var engine: PlaybackEngine
    @Inject lateinit var episodeDao: EpisodeDao
    @Inject lateinit var podcastSettingsDao: PodcastSettingsDao
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var clock: Clock

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var player: ExoPlayer
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
        val sessionPlayer = SkipForwardingPlayer(player)
        engine.attach(player, sessionPlayer, scope)

        session = MediaLibrarySession.Builder(this, sessionPlayer, LibraryCallback())
            .setSessionActivity(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .setMediaButtonPreferences(
                ImmutableList.of(
                    CommandButton.Builder(CommandButton.ICON_SKIP_BACK)
                        .setPlayerCommand(Player.COMMAND_SEEK_BACK)
                        .setDisplayName("Skip back")
                        .setSlots(CommandButton.SLOT_BACK)
                        .build(),
                    CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD)
                        .setPlayerCommand(Player.COMMAND_SEEK_FORWARD)
                        .setDisplayName("Skip forward")
                        .setSlots(CommandButton.SLOT_FORWARD)
                        .build(),
                ),
            )
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        // Last chance to persist the position; the DB write is quick.
        runBlocking { engine.savePosition() }
        session?.run {
            release()
            session = null
        }
        player.release()
        scope.cancel()
        super.onDestroy()
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val root = MediaItem.Builder()
                .setMediaId(ROOT_ID)
                .setMediaMetadata(MediaMetadata.Builder().setIsBrowsable(true).setIsPlayable(false).build())
                .build()
            return com.google.common.util.concurrent.Futures.immediateFuture(LibraryResult.ofItem(root, params))
        }

        // TODO(9.5): Android Auto browse tree (Up Next, Latest, Downloads, Podcasts).
        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
            com.google.common.util.concurrent.Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.of(), params))

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = future {
            mediaItems.mapNotNull { item -> item.episodeId()?.let { resolver.resolve(it) } }.toMutableList()
        }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = future {
            val resolved = mediaItems.mapNotNull { item -> item.episodeId()?.let { resolver.resolve(it) } }
            val first = resolved.firstOrNull()?.episodeId()
            val start = if (startPositionMs != C.TIME_UNSET || first == null) startPositionMs else startFor(first)
            MediaSession.MediaItemsWithStartPosition(resolved, 0, start)
        }

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = future {
            val last = episodeDao.getLastPlayed()
            val item = last?.let { resolver.resolve(it.id) }
            if (last == null || item == null) {
                MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0)
            } else {
                MediaSession.MediaItemsWithStartPosition(listOf(item), 0, startFor(last.id))
            }
        }
    }

    private suspend fun startFor(episodeId: Long): Long {
        val e = episodeDao.getById(episodeId) ?: return 0
        val s = settingsRepository.settings.first()
        return PlaybackDecisions.startPosition(
            savedPositionMs = e.positionMs,
            playState = e.playState,
            lastPlayedAt = e.lastPlayedAt,
            now = clock.now(),
            resumeRewind = s.resumeRewind,
            skipIntroSec = podcastSettingsDao.get(e.podcastId)?.skipIntroSec ?: 0,
            durationMs = e.durationSec?.times(1_000L),
        )
    }

    private fun <T> future(block: suspend () -> T): ListenableFuture<T> {
        val f = SettableFuture.create<T>()
        scope.launch {
            try {
                f.set(block())
            } catch (e: Throwable) {
                f.setException(e)
            }
        }
        return f
    }

    companion object {
        const val ROOT_ID = "root"
    }
}
