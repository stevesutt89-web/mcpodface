package com.steve.mcpodface.playback

import androidx.core.net.toUri
import androidx.core.os.bundleOf
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.steve.mcpodface.data.db.DownloadDao
import com.steve.mcpodface.data.db.DownloadState
import com.steve.mcpodface.data.db.EpisodeDao
import com.steve.mcpodface.data.db.EpisodeEntity
import com.steve.mcpodface.data.db.PodcastDao
import java.io.File
import javax.inject.Inject

/** Turns an episode id (the MediaItem mediaId) into a playable MediaItem. */
class EpisodeMediaResolver @Inject constructor(
    private val episodeDao: EpisodeDao,
    private val podcastDao: PodcastDao,
    private val downloadDao: DownloadDao,
) {
    suspend fun resolve(episodeId: Long): MediaItem? {
        val episode = episodeDao.getById(episodeId) ?: return null
        val podcast = podcastDao.getById(episode.podcastId)
        val uri = localFile(episodeId)?.toUri() ?: episode.enclosureUrl.toUri()
        return MediaItem.Builder()
            .setMediaId(episodeId.toString())
            .setUri(uri)
            // The web URL and type, for Chromecast when [uri] is a downloaded file (see forCast).
            .setRequestMetadata(
                MediaItem.RequestMetadata.Builder()
                    .setMediaUri(episode.enclosureUrl.toUri())
                    .setExtras(bundleOf(EXTRA_ENCLOSURE_TYPE to episode.enclosureType))
                    .build(),
            )
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(episode.title)
                    .setArtist(podcast?.title)
                    .setAlbumTitle(podcast?.title)
                    .setArtworkUri((episode.artworkUrl ?: podcast?.artworkUrl)?.toUri())
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE)
                    .build(),
            )
            .build()
    }

    suspend fun episode(episodeId: Long): EpisodeEntity? = episodeDao.getById(episodeId)

    /** The downloaded file, if the download finished and the file is still on disk. */
    suspend fun localFile(episodeId: Long): File? {
        val d = downloadDao.get(episodeId) ?: return null
        if (d.state != DownloadState.COMPLETED) return null
        return d.filePath?.let(::File)?.takeIf { it.exists() }
    }
}

internal fun MediaItem.episodeId(): Long? = mediaId.toLongOrNull()
