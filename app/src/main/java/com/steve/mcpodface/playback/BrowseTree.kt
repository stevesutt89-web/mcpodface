package com.steve.mcpodface.playback

import androidx.media3.common.MediaItem
import com.steve.mcpodface.core.Clock
import com.steve.mcpodface.data.db.AppDatabase
import com.steve.mcpodface.data.db.DownloadState
import com.steve.mcpodface.data.db.HistoryItem
import com.steve.mcpodface.data.db.PlayState
import com.steve.mcpodface.data.db.PodcastSettingsEntity
import com.steve.mcpodface.data.db.SortOrder
import com.steve.mcpodface.data.playlist.PlaylistRepository
import com.steve.mcpodface.data.search.SearchRepository
import com.steve.mcpodface.domain.policy.defaultSortOrder
import com.steve.mcpodface.playback.auto.BrowseItems
import com.steve.mcpodface.playback.auto.CarEpisode
import com.steve.mcpodface.playback.auto.toCarEpisode
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * The Android Auto (and other media browser) tree (docs/ANDROID-AUTO.md, task A2). Root has four tabs:
 * Up Next, Latest, Podcasts (a grid of subscriptions, each with its episodes) and Library
 * (Downloads, In progress, Starred, Playlists → each playlist, History). Rows are built by BrowseItems.
 * Episode items carry only the episode id as mediaId; PlaybackService resolves the stream or file
 * when one is played.
 */
class BrowseTree @Inject constructor(
    private val db: AppDatabase,
    private val search: SearchRepository,
    private val playlists: PlaylistRepository,
    private val clock: Clock,
) {
    suspend fun children(parentId: String): List<MediaItem> {
        val now = clock.now()
        return when (parentId) {
            ROOT -> listOf(
                BrowseItems.folder(UP_NEXT, "Up Next"),
                BrowseItems.folder(LATEST, "Latest"),
                BrowseItems.folder(PODCASTS, "Podcasts", childrenAsGrid = true),
                BrowseItems.folder(LIBRARY, "Library"),
            )
            UP_NEXT -> db.queueDao().observeQueueItems().first().take(LIST_LIMIT).map { BrowseItems.episodeItem(it.toCarEpisode(), now) }
            LATEST -> db.episodeDao()
                .observeLatest(since = now - LATEST_WINDOW_MS, limit = LIST_LIMIT, unplayedOnly = true, downloadedOnly = false)
                .first().map { item ->
                    BrowseItems.episodeItem(item.toCarEpisode(), now, groupTitle = BrowseItems.groupTitle(item.episode.pubDate, now))
                }
            PODCASTS -> db.podcastDao().getSubscribed().sortedBy { it.title.lowercase() }.map { p ->
                BrowseItems.folder(PODCAST_PREFIX + p.id, p.title, p.artworkUrl, isPodcast = true)
            }
            LIBRARY -> listOf(
                BrowseItems.folder(DOWNLOADS, "Downloads"),
                BrowseItems.folder(IN_PROGRESS, "In progress"),
                BrowseItems.folder(STARRED, "Starred"),
                BrowseItems.folder(PLAYLISTS, "Playlists"),
                BrowseItems.folder(HISTORY, "History"),
            )
            DOWNLOADS -> db.downloadDao().observeDownloadItems().first()
                .filter { it.download.state == DownloadState.COMPLETED }
                .sortedByDescending { it.download.downloadedAt ?: 0 }
                .take(LIST_LIMIT)
                .map { BrowseItems.episodeItem(it.toCarEpisode(), now) }
            // Approximation: there is no episodes query for positionMs > 0 and not PLAYED, so this filters
            // the existing history query (last 500 listened episodes, most recent first). An in-progress
            // episode whose last session is older than that window is not listed.
            IN_PROGRESS -> db.listeningSessionDao().observeRecentlyPlayed().first()
                .filter { isInProgress(it.episode.positionMs, it.episode.playState) }
                .take(LIST_LIMIT)
                .map { BrowseItems.episodeItem(it.toCarEpisode(), now) }
            STARRED -> db.episodeDao().observeStarred().first()
                .take(LIST_LIMIT)
                .map { BrowseItems.episodeItem(it.toCarEpisode(), now) }
            PLAYLISTS -> playlists.observePlaylists().first()
                .map { BrowseItems.folder(PLAYLIST_PREFIX + it.playlist.id, it.playlist.name) }
            RECENT -> listOfNotNull(lastPlayed(now))
            HISTORY -> db.listeningSessionDao().observeRecentlyPlayed().first()
                .take(LIST_LIMIT)
                .map { BrowseItems.episodeItem(it.toCarEpisode(), now) }
            else -> when {
                parentId.startsWith(PODCAST_PREFIX) -> podcastEpisodes(parentId, now)
                parentId.startsWith(PLAYLIST_PREFIX) -> playlistEpisodes(parentId, now)
                else -> emptyList()
            }
        }
    }

    /** Voice search ("play … on McPodface"): the best matching episodes across subscriptions. */
    suspend fun search(query: String): List<MediaItem> {
        if (query.isBlank()) return emptyList()
        val now = clock.now()
        return search.search(query, pageSize = LIST_LIMIT).first().items
            .map { BrowseItems.episodeItem(it.item.toCarEpisode(), now) }
    }

    private suspend fun podcastEpisodes(parentId: String, now: Long): List<MediaItem> {
        val podcastId = parentId.removePrefix(PODCAST_PREFIX).toLongOrNull()
        if (!parentId.startsWith(PODCAST_PREFIX) || podcastId == null) return emptyList()
        val podcast = db.podcastDao().getById(podcastId) ?: return emptyList()
        val settings: PodcastSettingsEntity? = db.podcastSettingsDao().get(podcastId)
        val order = settings?.sortOrder ?: defaultSortOrder(podcast.feedType)
        val episodes = db.episodeDao()
            .observeForPodcast(podcastId, newestFirst = order == SortOrder.NEWEST_FIRST, hidePlayed = false)
            .first()
        // Stable partition: in-progress episodes first, each group keeps the podcast's sort order.
        val (inProgress, rest) = episodes.partition { isInProgress(it.episode.positionMs, it.episode.playState) }
        return (inProgress + rest).take(LIST_LIMIT).map { BrowseItems.episodeItem(it.toCarEpisode(), now) }
    }

    private suspend fun playlistEpisodes(parentId: String, now: Long): List<MediaItem> {
        val playlistId = parentId.removePrefix(PLAYLIST_PREFIX).toLongOrNull() ?: return emptyList()
        return playlists.observeEpisodes(playlistId).first()
            .take(LIST_LIMIT)
            .map { BrowseItems.episodeItem(it.toCarEpisode(), now) }
    }

    /** The car's "resume" card (the library root asked for with LibraryParams.isRecent). */
    private suspend fun lastPlayed(now: Long): MediaItem? {
        val e = db.episodeDao().getLastPlayed() ?: return null
        val podcast = db.podcastDao().getById(e.podcastId) ?: return null
        val car = CarEpisode(
            id = e.id,
            title = e.title,
            podcastTitle = podcast.title,
            artworkUrl = e.artworkUrl ?: podcast.artworkUrl,
            pubDate = e.pubDate,
            durationSec = e.durationSec,
            positionMs = e.positionMs,
            playState = e.playState,
            downloaded = db.downloadDao().get(e.id)?.state == DownloadState.COMPLETED,
            isStarred = e.isStarred,
            inQueue = false,
        )
        return BrowseItems.episodeItem(car, now)
    }

    private fun isInProgress(positionMs: Long, playState: PlayState): Boolean =
        positionMs > 0 && playState != PlayState.PLAYED

    companion object {
        const val ROOT = "root"
        const val UP_NEXT = "upnext"
        const val LATEST = "latest"
        const val PODCASTS = "podcasts"
        const val LIBRARY = "library"
        const val DOWNLOADS = "downloads"
        const val IN_PROGRESS = "inprogress"
        const val STARRED = "starred"
        const val PLAYLISTS = "playlists"
        const val HISTORY = "history"
        /** Root for the car's resume card; its only child is the last played episode. */
        const val RECENT = "recent"
        const val PODCAST_PREFIX = "podcast:"
        const val PLAYLIST_PREFIX = "playlist:"
        /** Most rows in any episode list, a driving-friendly length (subscriptions aren't capped). */
        const val LIST_LIMIT = 100
        private const val LATEST_WINDOW_MS = 30L * 86_400_000
    }
}

/** A history row as a car episode, for the In progress and History lists. */
private fun HistoryItem.toCarEpisode() = CarEpisode(
    id = episode.id,
    title = episode.title,
    podcastTitle = podcastTitle,
    artworkUrl = episode.artworkUrl ?: podcastArtworkUrl,
    pubDate = episode.pubDate,
    durationSec = episode.durationSec,
    positionMs = episode.positionMs,
    playState = episode.playState,
    downloaded = downloadState == DownloadState.COMPLETED,
    isStarred = episode.isStarred,
    inQueue = inQueue,
)
