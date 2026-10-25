package com.steve.mcpodface.playback

import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.steve.mcpodface.core.Clock
import com.steve.mcpodface.data.db.EpisodeDao
import com.steve.mcpodface.data.db.PodcastSettingsDao
import com.steve.mcpodface.data.prefs.SettingsRepository
import com.steve.mcpodface.playback.auto.AutoCommands
import com.steve.mcpodface.playback.auto.BrowseActions
import com.steve.mcpodface.playback.auto.CarActions
import com.steve.mcpodface.playback.auto.NowPlayingButtons
import com.steve.mcpodface.playback.auto.VoiceSearch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The session callback for PlaybackService: connections and their commands, custom commands, the
 * browse tree for Android Auto and other media browsers, and turning mediaIds into playable items.
 */
@OptIn(UnstableApi::class) // Media3 APIs marked unstable (session tweaks)
class LibraryCallback @Inject constructor(
    private val browseTree: BrowseTree,
    private val resolver: EpisodeMediaResolver,
    private val episodeDao: EpisodeDao,
    private val podcastSettingsDao: PodcastSettingsDao,
    private val settingsRepository: SettingsRepository,
    private val clock: Clock,
    private val voiceSearch: VoiceSearch,
    private val carActions: CarActions,
    private val browseActions: BrowseActions,
    private val nowPlayingButtons: NowPlayingButtons,
) : MediaLibrarySession.Callback {

    /** The service's scope and engine; PlaybackService sets them before building the session. */
    lateinit var scope: CoroutineScope
    lateinit var engine: PlaybackEngine

    /**
     * The default grants, plus our custom commands for every controller (car, notification, watch).
     * Media3 gives untrusted controllers read-only commands, and the Android Auto app is untrusted
     * unless it has notification access, so its play/pause taps were dropped: cars get full control.
     */
    override fun onConnectAsync(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): ListenableFuture<MediaSession.ConnectionResult> {
        val car = session.isAutoCompanionController(controller) || session.isAutomotiveController(controller)
        val defaults = MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller).build()
        val baseSessionCommands =
            if (car) MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS else defaults.availableSessionCommands
        val sessionCommands = baseSessionCommands.buildUpon()
            .apply { AutoCommands.all.forEach(::add) }
            .build()
        val playerCommands =
            if (car) MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS else defaults.availablePlayerCommands
        if (car) nowPlayingButtons.carConnected(controller)
        return Futures.immediateFuture(
            MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                .setAvailableSessionCommands(sessionCommands)
                .setAvailablePlayerCommands(playerCommands)
                .build(),
        )
    }

    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle,
    ): ListenableFuture<SessionResult> {
        val action = customCommand.customAction
        when (action) {
            // session.player is the SkipForwardingPlayer, so these use the user's intervals.
            AutoCommands.SKIP_BACK -> session.player.seekBack()
            AutoCommands.SKIP_FORWARD -> session.player.seekForward()
            in AutoCommands.NOW_PLAYING_ACTIONS -> return future { carActions.run(action, session.player, engine) }
            // Row actions from car lists carry the row's mediaId (the episode id).
            in AutoCommands.ROW_ACTIONS -> return future {
                val episodeId = args.getString(MediaConstants.EXTRA_KEY_MEDIA_ID)?.toLongOrNull()
                if (episodeId == null) SessionResult(SessionError.ERROR_BAD_VALUE) else browseActions.run(action, episodeId)
            }
            else -> return super.onCustomCommand(session, controller, customCommand, args)
        }
        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
    }

    override fun onDisconnected(session: MediaSession, controller: MediaSession.ControllerInfo) {
        nowPlayingButtons.carDisconnected(controller)
    }

    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<MediaItem>> {
        // The car's "resume" card asks for a recent root; its only child is the last played episode.
        val recent = params?.isRecent == true
        val root = MediaItem.Builder()
            .setMediaId(if (recent) BrowseTree.RECENT else BrowseTree.ROOT)
            .setMediaMetadata(MediaMetadata.Builder().setIsBrowsable(true).setIsPlayable(false).build())
            .build()
        // Default list styles for the whole tree; the Podcasts folder asks for a grid itself.
        val rootParams = LibraryParams.Builder()
            .setRecent(recent)
            .setExtras(
                Bundle().apply {
                    putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
                    putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
                },
            )
            .build()
        return Futures.immediateFuture(LibraryResult.ofItem(root, rootParams))
    }

    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = future {
        val all = browseTree.children(parentId)
        val from = (page * pageSize).coerceAtMost(all.size)
        val to = if (pageSize == Int.MAX_VALUE) all.size else (from + pageSize).coerceAtMost(all.size)
        LibraryResult.ofItemList(ImmutableList.copyOf(all.subList(from, to)), params)
    }

    override fun onSearch(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<Void>> = future {
        session.notifySearchResultChanged(browser, query, browseTree.search(query).size, params)
        LibraryResult.ofVoid()
    }

    override fun onGetSearchResult(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = future {
        LibraryResult.ofItemList(ImmutableList.copyOf(browseTree.search(query)), params)
    }

    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
    ): ListenableFuture<MutableList<MediaItem>> = future { resolveRequest(mediaItems).toMutableList() }

    override fun onSetMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = future {
        val resolved = resolveRequest(mediaItems)
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

    /**
     * Turns requested items into playable ones. Most carry an episode id; a voice request ("play ...
     * on McPodface") carries `requestMetadata.searchQuery` instead, which VoiceSearch turns into the
     * one episode to play.
     */
    private suspend fun resolveRequest(items: List<MediaItem>): List<MediaItem> = items.mapNotNull { item ->
        val query = item.requestMetadata.searchQuery
        val episodeId = item.episodeId()
            ?: query?.let { voiceSearch.resolve(it, item.requestMetadata.extras ?: Bundle.EMPTY).firstOrNull() }
        episodeId?.let { resolver.resolve(it) }
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
            rewindSec = s.resumeRewindSec,
            longPauseMinutes = s.longPauseMinutes,
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
}
