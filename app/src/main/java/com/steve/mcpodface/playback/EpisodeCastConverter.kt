package com.steve.mcpodface.playback

import androidx.annotation.OptIn
import androidx.media3.cast.DefaultMediaItemConverter
import androidx.media3.cast.MediaItemConverter
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import com.google.android.gms.cast.MediaQueueItem

/** Extras key on [MediaItem.RequestMetadata] holding the enclosure's MIME type. */
internal const val EXTRA_ENCLOSURE_TYPE = "com.steve.mcpodface.ENCLOSURE_TYPE"

/** Media3's converter, with episodes adjusted for a Cast device first (see [forCast]). */
@OptIn(UnstableApi::class) // media3-cast
class EpisodeCastConverter : MediaItemConverter {
    private val base = DefaultMediaItemConverter()

    override fun toMediaQueueItem(mediaItem: MediaItem): MediaQueueItem = base.toMediaQueueItem(forCast(mediaItem))

    override fun toMediaItem(mediaQueueItem: MediaQueueItem): MediaItem = base.toMediaItem(mediaQueueItem)
}

/**
 * A Cast device fetches the audio itself, so a downloaded episode's local file is swapped for its
 * web URL ([MediaItem.RequestMetadata.mediaUri], set by [EpisodeMediaResolver]). The receiver also
 * needs a MIME type: the enclosure's, or MP3 when the feed gave none.
 */
internal fun forCast(item: MediaItem): MediaItem {
    val config = item.localConfiguration ?: return item
    val builder = item.buildUpon()
    val scheme = config.uri.scheme?.lowercase()
    val remote = item.requestMetadata.mediaUri
    if (scheme != "http" && scheme != "https" && remote != null) builder.setUri(remote)
    if (config.mimeType == null) {
        val type = item.requestMetadata.extras?.getString(EXTRA_ENCLOSURE_TYPE)?.takeIf { it.startsWith("audio/") }
        builder.setMimeType(type ?: "audio/mpeg")
    }
    return builder.build()
}
