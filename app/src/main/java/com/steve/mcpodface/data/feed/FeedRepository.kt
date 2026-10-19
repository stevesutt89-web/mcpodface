package com.steve.mcpodface.data.feed

import androidx.room.withTransaction
import com.steve.mcpodface.core.Clock
import com.steve.mcpodface.data.db.AppDatabase
import com.steve.mcpodface.data.db.EpisodeOrigin
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import javax.inject.Inject
import javax.inject.Singleton

sealed interface RefreshResult {
    val podcastId: Long

    data class Updated(
        override val podcastId: Long,
        /** Genuinely new live-feed episodes, for notifications and auto-queue (PLAN.md §5 step 5). */
        val newEpisodeIds: List<Long>,
        val insertedCount: Int,
    ) : RefreshResult

    data class NotModified(override val podcastId: Long) : RefreshResult
    data class Gone(override val podcastId: Long) : RefreshResult
    data class Failed(override val podcastId: Long, val message: String) : RefreshResult
}

/** Fetches feeds and merges them into Room. Never deletes episode rows (PLAN.md §5). */
@Singleton
class FeedRepository @Inject constructor(
    private val db: AppDatabase,
    private val fetcher: FeedFetcher,
    private val merger: EpisodeMerger,
    private val clock: Clock,
) {
    private val podcastDao = db.podcastDao()
    private val episodeDao = db.episodeDao()

    /** Refreshes one podcast. [force] skips the conditional GET headers. */
    suspend fun refresh(podcastId: Long, force: Boolean = false): RefreshResult {
        val podcast = podcastDao.getById(podcastId)
            ?: return RefreshResult.Failed(podcastId, "Podcast $podcastId not found")
        val result = fetcher.fetch(
            FeedRequest(
                url = podcast.feedUrl,
                etag = podcast.httpEtag.takeUnless { force },
                lastModified = podcast.httpLastModified.takeUnless { force },
                authUser = podcast.authUser,
                authPassword = podcast.authPassword,
            ),
        )
        val now = clock.now()
        return when (result) {
            FetchResult.NotModified -> {
                podcastDao.markRefreshed(podcastId, now, gone = false)
                RefreshResult.NotModified(podcastId)
            }
            FetchResult.Gone -> {
                podcastDao.markRefreshed(podcastId, now, gone = true)
                RefreshResult.Gone(podcastId)
            }
            is FetchResult.Failed -> RefreshResult.Failed(podcastId, result.message)
            is FetchResult.Success -> applyFeed(podcastId, result, now)
        }
    }

    /** Refreshes every subscribed podcast, at most 4 feeds at a time (PLAN.md §5). */
    suspend fun refreshAll(): List<RefreshResult> = coroutineScope {
        val gate = Semaphore(4)
        podcastDao.getSubscribed()
            .map { p -> async { gate.withPermit { refresh(p.id) } } }
            .awaitAll()
        // TODO(5.2): run DownloadPolicy per podcast, then one CleanupPolicy pass, then notify.
    }

    /**
     * Adds episodes from a backfill source (RSS paging or Podcast Index). Only unknown episodes are
     * inserted, with inCurrentFeed = false; they never notify. Returns the number inserted.
     */
    suspend fun mergeBackfill(podcastId: Long, episodes: List<ParsedEpisode>, origin: EpisodeOrigin): Int {
        require(origin != EpisodeOrigin.FEED) { "Use refresh() for the live feed" }
        return db.withTransaction {
            val existing = episodeDao.getForPodcast(podcastId)
            val merge = merger.merge(podcastId, existing, episodes, origin, clock.now())
            episodeDao.insertAll(merge.toInsert).count { it > 0 }
        }
    }

    private suspend fun applyFeed(podcastId: Long, fetched: FetchResult.Success, now: Long): RefreshResult {
        val feed = fetched.feed
        val started = System.nanoTime()
        return db.withTransaction {
            // Re-read inside the transaction: the user may have (un)subscribed or changed the podcast
            // while the fetch was in flight, and writing back a stale copy would undo that.
            val podcast = podcastDao.getById(podcastId)
                ?: return@withTransaction RefreshResult.Failed(podcastId, "Podcast was removed")
            val movedTo = fetched.movedPermanentlyTo?.takeIf { podcastDao.getByFeedUrl(it) == null }
            val existing = episodeDao.getForPodcast(podcast.id)
            val merge = merger.merge(podcast.id, existing, feed.episodes, EpisodeOrigin.FEED, now)

            val insertedIds = episodeDao.insertAll(merge.toInsert)
            if (merge.toUpdate.isNotEmpty()) episodeDao.updateAll(merge.toUpdate)

            // New = first seen now and published after the previous refresh. The first refresh of a
            // podcast has no "previous", so nothing on it counts as new.
            val previousRefresh = podcast.lastRefreshAt
            val newIds = if (previousRefresh == null) {
                emptyList()
            } else {
                merge.toInsert.zip(insertedIds)
                    .filter { (e, id) -> id > 0 && e.pubDate > previousRefresh }
                    .map { (_, id) -> id }
            }

            podcastDao.update(
                podcast.copy(
                    feedUrl = movedTo ?: podcast.feedUrl,
                    title = feed.title.ifBlank { podcast.title },
                    author = feed.author ?: podcast.author,
                    descriptionHtml = feed.descriptionHtml ?: podcast.descriptionHtml,
                    artworkUrl = feed.artworkUrl ?: podcast.artworkUrl,
                    websiteUrl = feed.websiteUrl ?: podcast.websiteUrl,
                    feedType = feed.feedType,
                    httpEtag = fetched.etag,
                    httpLastModified = fetched.lastModified,
                    lastRefreshAt = now,
                    lastNewEpisodeAt = maxOfNotNull(
                        podcast.lastNewEpisodeAt,
                        merge.toInsert.maxOfOrNull { it.pubDate },
                    ),
                    feedGone = false,
                ),
            )
            android.util.Log.d("McPodface.Feed", "merge+save ${merge.toInsert.size} new, ${merge.toUpdate.size} updated in ${(System.nanoTime() - started) / 1_000_000}ms")
            RefreshResult.Updated(podcast.id, newIds, insertedIds.count { it > 0 })
        }
    }
}

private fun maxOfNotNull(a: Long?, b: Long?): Long? = if (a == null) b else if (b == null) a else maxOf(a, b)
