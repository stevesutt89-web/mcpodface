package com.steve.mcpodface.data.feed

import androidx.room.withTransaction
import com.steve.mcpodface.core.Clock
import com.steve.mcpodface.core.di.ApplicationScope
import com.steve.mcpodface.data.catalog.CatalogPodcast
import com.steve.mcpodface.data.catalog.CatalogRepository
import com.steve.mcpodface.data.db.AppDatabase
import com.steve.mcpodface.data.db.BackfillStateEntity
import com.steve.mcpodface.data.db.PodcastEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Preview, subscribe and unsubscribe. A previewed podcast is stored with isSubscribed = false
 * (PLAN.md §4), so the podcast detail screen always reads from Room, subscribed or not.
 */
@Singleton
class SubscriptionRepository @Inject constructor(
    private val db: AppDatabase,
    private val feedRepository: FeedRepository,
    private val catalog: CatalogRepository,
    private val clock: Clock,
    @ApplicationScope private val appScope: CoroutineScope,
) {
    private val podcastDao = db.podcastDao()

    sealed interface OpenResult {
        data class Ok(val podcastId: Long) : OpenResult
        data class Failed(val message: String) : OpenResult
    }

    /** First fetch of a podcast opened from search, running in the background (see [open]). */
    sealed interface FirstFetch {
        data object Loading : FirstFetch
        data class Failed(val message: String) : FirstFetch
    }

    private val _firstFetch = MutableStateFlow<Map<Long, FirstFetch>>(emptyMap())

    /** Podcasts whose first fetch is still running or has failed. Absent = fetched (or not started). */
    val firstFetch: StateFlow<Map<Long, FirstFetch>> = _firstFetch

    /**
     * Makes sure a row exists for [feedUrl] (filled from [hint] while the feed loads) and fetches the
     * feed if it has never been fetched. Returns the podcast id to open the detail screen with.
     */
    suspend fun open(
        feedUrl: String,
        hint: CatalogPodcast? = null,
        authUser: String? = null,
        authPassword: String? = null,
        waitForFeed: Boolean = true,
    ): OpenResult {
        val url = feedUrl.trim()
        val existing = podcastDao.getByFeedUrl(url)
        val id = existing?.id ?: insertPlaceholder(url, hint, authUser, authPassword)
        // New credentials for a feed we already know (e.g. it was first previewed publicly):
        // store them and fetch again with them.
        val newCredentials = existing != null && !authUser.isNullOrEmpty() &&
            (authUser != existing.authUser || authPassword != existing.authPassword)
        if (newCredentials) {
            podcastDao.update(existing!!.copy(authUser = authUser, authPassword = authPassword))
            return when (val r = feedRepository.refresh(id, force = true)) {
                is RefreshResult.Failed -> OpenResult.Failed(r.message)
                is RefreshResult.Gone -> OpenResult.Failed("This feed no longer exists")
                else -> OpenResult.Ok(id)
            }
        }
        if (existing?.lastRefreshAt != null) return OpenResult.Ok(id)

        // From search, open the page at once (the placeholder already has the catalogue's title,
        // artwork and description) and let episodes stream in. Big feeds take a while.
        if (!waitForFeed) {
            startFirstFetch(id)
            return OpenResult.Ok(id)
        }

        return when (val r = feedRepository.refresh(id)) {
            is RefreshResult.Failed -> {
                if (existing == null) podcastDao.deleteIfUnsubscribed(id) // don't leave a broken placeholder
                OpenResult.Failed(r.message)
            }
            is RefreshResult.Gone -> OpenResult.Failed("This feed no longer exists")
            else -> OpenResult.Ok(id)
        }
    }

    /** Runs (or re-runs after a failure) the background first fetch started by [open]. */
    fun startFirstFetch(podcastId: Long) {
        if (_firstFetch.value[podcastId] == FirstFetch.Loading) return
        _firstFetch.update { it + (podcastId to FirstFetch.Loading) }
        appScope.launch {
            val outcome = when (val r = feedRepository.refresh(podcastId)) {
                is RefreshResult.Failed -> FirstFetch.Failed(r.message)
                is RefreshResult.Gone -> FirstFetch.Failed("This feed no longer exists")
                else -> null
            }
            _firstFetch.update { if (outcome == null) it - podcastId else it + (podcastId to outcome) }
        }
    }

    /** Subscribes, queues archive backfill, and does the one Podcast Index lookup in the background. */
    suspend fun subscribe(podcastId: Long) {
        val podcast = podcastDao.getById(podcastId) ?: return
        if (podcast.isSubscribed) return
        db.withTransaction {
            podcastDao.update(podcast.copy(isSubscribed = true, subscribedAt = clock.now()))
            if (db.backfillStateDao().get(podcastId) == null) {
                db.backfillStateDao().upsert(BackfillStateEntity(podcastId = podcastId))
            }
        }
        appScope.launch {
            feedRepository.refresh(podcastId)
            // TODO(5.2): run DownloadPolicy for this podcast once downloads exist.
            if (podcast.podcastIndexId == null) {
                catalog.lookupByFeedUrl(podcast.feedUrl)?.let { found ->
                    podcastDao.getById(podcastId)?.let {
                        podcastDao.update(
                            it.copy(
                                podcastIndexId = found.podcastIndexId,
                                podcastIndexEpisodeCount = found.episodeCount,
                                itunesId = it.itunesId ?: found.itunesId,
                            ),
                        )
                    }
                }
            }
        }
    }

    /**
     * Unsubscribes. Episode rows and play history are kept so re-subscribing restores them.
     * TODO(5.1): when [deleteDownloads] is true, delete this podcast's downloaded files.
     */
    suspend fun unsubscribe(podcastId: Long, deleteDownloads: Boolean) {
        val podcast = podcastDao.getById(podcastId) ?: return
        podcastDao.update(podcast.copy(isSubscribed = false, subscribedAt = null))
    }

    private suspend fun insertPlaceholder(url: String, hint: CatalogPodcast?, user: String?, pass: String?): Long {
        val id = podcastDao.insert(
            PodcastEntity(
                feedUrl = url,
                title = hint?.title ?: url,
                author = hint?.author,
                descriptionHtml = hint?.description,
                artworkUrl = hint?.artworkUrl,
                podcastIndexId = hint?.podcastIndexId,
                podcastIndexEpisodeCount = hint?.episodeCount,
                itunesId = hint?.itunesId,
                authUser = user,
                authPassword = pass,
            ),
        )
        return if (id > 0) id else podcastDao.getByFeedUrl(url)!!.id
    }
}
