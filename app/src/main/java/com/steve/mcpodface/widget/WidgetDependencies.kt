package com.steve.mcpodface.widget

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.steve.mcpodface.data.db.EpisodeDao
import com.steve.mcpodface.data.db.EpisodeEntity
import com.steve.mcpodface.data.db.PodcastDao
import com.steve.mcpodface.playback.PlaybackService
import com.steve.mcpodface.playback.PlayerController
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Hilt graph access for code that Glance runs outside Hilt's reach (the widget itself and its
 * action callbacks).
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun playerController(): PlayerController
    fun episodeDao(): EpisodeDao
    fun podcastDao(): PodcastDao
}

internal fun widgetEntryPoint(context: Context): WidgetEntryPoint =
    EntryPointAccessors.fromApplication(context.applicationContext, WidgetEntryPoint::class.java)

/** Loads the row the widget shows, with its podcast's title and artwork as fallback. */
internal suspend fun WidgetEntryPoint.widgetEpisode(episode: EpisodeEntity): WidgetEpisode {
    val podcast = podcastDao().getById(episode.podcastId)
    return WidgetEpisode(
        episodeId = episode.id,
        title = episode.title,
        podcastTitle = podcast?.title,
        artworkUrl = episode.artworkUrl ?: podcast?.artworkUrl,
    )
}

/**
 * Runs [block] on a MediaController for PlaybackService, then releases it. The playback service
 * keeps playing after the controller is released.
 */
internal suspend fun <T> withMediaController(context: Context, block: (MediaController) -> T): T =
    withContext(Dispatchers.Main) {
        val controller = connectMediaController(context)
        try {
            block(controller)
        } finally {
            controller.release()
        }
    }

private suspend fun connectMediaController(context: Context): MediaController =
    suspendCancellableCoroutine { cont ->
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
