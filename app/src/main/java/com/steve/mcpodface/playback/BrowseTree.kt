package com.steve.mcpodface.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.steve.mcpodface.core.Clock
import com.steve.mcpodface.data.db.AppDatabase
import com.steve.mcpodface.data.db.DownloadState
import com.steve.mcpodface.data.db.EpisodeListItem
import com.steve.mcpodface.data.db.PodcastSettingsEntity
import com.steve.mcpodface.data.db.SortOrder
import com.steve.mcpodface.data.search.SearchRepository
import com.steve.mcpodface.domain.policy.defaultSortOrder
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * The Android Auto (and other media browser) tree (PLAN.md §14, task 9.5):
 * root → Up Next, Latest, Downloads, Podcasts → episodes. Episode items carry only
 * the episode id as mediaId; PlaybackService resolves the stream or file when one is played.
 */
class BrowseTree @Inject constructor(
    private val db: AppDatabase,
    private val search: SearchRepository,
    private val clock: Clock,
) {
    suspend fun children(parentId: String): List<MediaItem> = when (parentId) {
        ROOT -> listOf(
            folder(UP_NEXT, "Up Next"),
            folder(LATEST, "Latest"),
            folder(DOWNLOADS, "Downloads"),
            folder(PODCASTS, "Podcasts"),
        )
        UP_NEXT -> db.queueDao().observeQueueItems().first().map(::episode)
        LATEST -> db.episodeDao()
            .observeLatest(since = clock.now() - LATEST_WINDOW_MS, limit = LIST_LIMIT, unplayedOnly = false, downloadedOnly = false)
            .first().map(::episode)
        DOWNLOADS -> db.downloadDao().observeDownloadItems().first()
            .filter { it.download.state == DownloadState.COMPLETED }
            .sortedByDescending { it.download.downloadedAt ?: 0 }
            .map { d -> episodeItem(d.download.episodeId, d.episodeTitle, d.podcastTitle, d.artworkUrl) }
        PODCASTS -> db.podcastDao().getSubscribed().sortedBy { it.title.lowercase() }.map { p ->
            folder(PODCAST_PREFIX + p.id, p.title, p.artworkUrl)
        }
        else -> podcastEpisodes(parentId)
    }

    /** Voice search ("play … on McPodface"): the best matching episodes across subscriptions. */
    suspend fun search(query: String): List<MediaItem> =
        if (query.isBlank()) emptyList() else search.search(query, pageSize = LIST_LIMIT).first().items.map { episode(it.item) }

    private suspend fun podcastEpisodes(parentId: String): List<MediaItem> {
        val podcastId = parentId.removePrefix(PODCAST_PREFIX).toLongOrNull()
        if (!parentId.startsWith(PODCAST_PREFIX) || podcastId == null) return emptyList()
        val podcast = db.podcastDao().getById(podcastId) ?: return emptyList()
        val settings: PodcastSettingsEntity? = db.podcastSettingsDao().get(podcastId)
        val order = settings?.sortOrder ?: defaultSortOrder(podcast.feedType)
        return db.episodeDao().observeForPodcast(podcastId, newestFirst = order == SortOrder.NEWEST_FIRST, hidePlayed = false)
            .first().take(LIST_LIMIT).map(::episode)
    }

    private fun episode(item: EpisodeListItem): MediaItem = episodeItem(
        item.episode.id, item.episode.title, item.podcastTitle, item.episode.artworkUrl ?: item.podcastArtworkUrl,
    )

    companion object {
        const val ROOT = "root"
        const val UP_NEXT = "upnext"
        const val LATEST = "latest"
        const val DOWNLOADS = "downloads"
        const val PODCASTS = "podcasts"
        const val PODCAST_PREFIX = "podcast:"
        const val LIST_LIMIT = 100
        private const val LATEST_WINDOW_MS = 30L * 86_400_000

        fun folder(id: String, title: String, artworkUrl: String? = null): MediaItem = MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(if (id.startsWith(PODCAST_PREFIX)) MediaMetadata.MEDIA_TYPE_PODCAST else MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .apply { artworkUrl?.let { setArtworkUri(Uri.parse(it)) } }
                    .build(),
            )
            .build()

        fun episodeItem(episodeId: Long, title: String, podcastTitle: String, artworkUrl: String?): MediaItem = MediaItem.Builder()
            .setMediaId(episodeId.toString())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(podcastTitle)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE)
                    .apply { artworkUrl?.let { setArtworkUri(Uri.parse(it)) } }
                    .build(),
            )
            .build()
    }
}
