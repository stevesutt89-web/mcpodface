package com.steve.mcpodface.playback

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.MutableStateFlow
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
)

/** The UI's single entry point to playback. Implemented over Media3 in task 4.1. */
interface PlayerController {
    val nowPlaying: StateFlow<NowPlaying?>

    /** Starts [episodeId] from its saved position (local file if downloaded, else stream). */
    fun play(episodeId: Long)

    /** Plays [episodeId], or pauses/resumes it if it is the current episode. */
    fun togglePlayPause(episodeId: Long)

    fun pause()
    fun seekTo(positionMs: Long)
}

/** Placeholder until task 4.1. */
@Singleton
class NoOpPlayerController @Inject constructor() : PlayerController {
    override val nowPlaying: StateFlow<NowPlaying?> = MutableStateFlow(null)
    override fun play(episodeId: Long) = Unit
    override fun togglePlayPause(episodeId: Long) = Unit
    override fun pause() = Unit
    override fun seekTo(positionMs: Long) = Unit
}

@Module
@InstallIn(SingletonComponent::class)
abstract class PlaybackModule {
    @Binds abstract fun playerController(impl: NoOpPlayerController): PlayerController
}
