package com.steve.mcpodface.playback

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** What the mini player, rows and full player show. */
data class NowPlaying(
    val episodeId: Long,
    val isPlaying: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val speed: Float = 1f,
    val isBuffering: Boolean = false,
)

/** The UI's single entry point to playback (Media3PlayerController in the app). */
interface PlayerController {
    val nowPlaying: StateFlow<NowPlaying?>

    /** One-off messages for a Snackbar, e.g. "Streaming on mobile data is off". */
    val messages: SharedFlow<String>

    /**
     * Starts [episodeId] (local file if downloaded, else stream) from [startPositionMs], or from its
     * saved position when null. Tappable show-notes timestamps pass a position.
     */
    fun play(episodeId: Long, startPositionMs: Long? = null)

    /** Plays [episodeId], or pauses/resumes it if it is the current episode. */
    fun togglePlayPause(episodeId: Long)

    fun pause()
    fun seekTo(positionMs: Long)
    fun seekBy(deltaMs: Long)

    /** Skip by the user's configured intervals (default 10s back, 30s forward). */
    fun skipBack()
    fun skipForward()

    fun setSpeed(speed: Float)
}

/** Does nothing. For previews, and as a base for test fakes that override only what they need. */
open class NoOpPlayerController @Inject constructor() : PlayerController {
    override val nowPlaying: StateFlow<NowPlaying?> = MutableStateFlow(null)
    override val messages: SharedFlow<String> = MutableSharedFlow()
    override fun play(episodeId: Long, startPositionMs: Long?) = Unit
    override fun togglePlayPause(episodeId: Long) = Unit
    override fun pause() = Unit
    override fun seekTo(positionMs: Long) = Unit
    override fun seekBy(deltaMs: Long) = Unit
    override fun skipBack() = Unit
    override fun skipForward() = Unit
    override fun setSpeed(speed: Float) = Unit
}

@Module
@InstallIn(SingletonComponent::class)
abstract class PlaybackModule {
    @Binds @Singleton abstract fun playerController(impl: Media3PlayerController): PlayerController
}
