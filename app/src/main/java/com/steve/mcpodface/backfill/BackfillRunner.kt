package com.steve.mcpodface.backfill

import android.util.Log
import com.steve.mcpodface.core.Clock
import com.steve.mcpodface.data.db.AppDatabase
import com.steve.mcpodface.data.db.BackfillStateEntity
import com.steve.mcpodface.data.db.BackfillStatus
import com.steve.mcpodface.data.db.EpisodeOrigin
import com.steve.mcpodface.data.db.PodcastEntity
import com.steve.mcpodface.data.feed.FeedFetcher
import com.steve.mcpodface.data.feed.FeedRepository
import com.steve.mcpodface.data.feed.FeedRequest
import com.steve.mcpodface.data.feed.FetchResult
import com.steve.mcpodface.data.feed.ParsedEpisode
import com.steve.mcpodface.data.network.ApiPurpose
import com.steve.mcpodface.data.network.PodcastIndexApi
import com.steve.mcpodface.data.network.PodcastIndexCredentials
import com.steve.mcpodface.data.network.PodcastIndexEpisode
import com.steve.mcpodface.data.network.RateLimiter
import com.steve.mcpodface.data.prefs.SettingsRepository
import com.steve.mcpodface.refresh.PolicyRunner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import retrofit2.HttpException
import javax.inject.Inject

/**
 * Archive backfill (PLAN.md §7.4): slowly fetches older episodes the live feed no longer lists.
 * Per podcast, cheapest source first: RSS paging (`atom:link rel="next"`, publisher's server, no
 * API budget), then one Podcast Index `/episodes/byfeedid` call through the [RateLimiter].
 * Backfilled episodes never notify or auto-queue; only the download policy runs afterwards (so an
 * OLDEST_FIRST window can pick them up).
 */
class BackfillRunner @Inject constructor(
    private val db: AppDatabase,
    private val fetcher: FeedFetcher,
    private val feeds: FeedRepository,
    private val indexApi: PodcastIndexApi,
    private val credentials: PodcastIndexCredentials,
    private val rateLimiter: RateLimiter,
    private val settingsRepository: SettingsRepository,
    private val policyRunner: PolicyRunner,
    private val clock: Clock,
) {
    /** Replaced in tests so spacing doesn't slow them down. */
    internal var sleep: suspend (Long) -> Unit = { delay(it) }

    data class Summary(val podcastsTouched: Int, val episodesAdded: Int, val indexRequests: Int)

    /** One worker run: a batch of pages per podcast, in priority order, until the run's limits. */
    suspend fun run(): Summary {
        val settings = settingsRepository.settings.first()
        if (!settings.backfillEnabled) return Summary(0, 0, 0)
        var touched = 0
        var added = 0
        var indexRequests = 0
        var pagesLeft = MAX_PAGES_PER_RUN
        var indexOpen = true
        for (podcast in candidates()) {
            if (pagesLeft <= 0 && !indexOpen) break
            val before = added
            val state = db.backfillStateDao().get(podcast.id) ?: BackfillStateEntity(podcastId = podcast.id)
            var s = state.copy(status = BackfillStatus.IN_PROGRESS, lastAttemptAt = clock.now())
            try {
                if (!s.rssPagingDone && pagesLeft > 0) {
                    val (next, pages, n) = pageRss(podcast, s, minOf(pagesLeft, MAX_PAGES_PER_PODCAST))
                    s = next
                    pagesLeft -= pages
                    added += n
                }
                if (s.rssPagingDone && !s.indexDone && indexOpen) {
                    when (val r = fetchIndex(podcast, s)) {
                        is IndexOutcome.Done -> {
                            s = r.state
                            added += r.added
                            if (r.requested) indexRequests++
                        }
                        IndexOutcome.NoBudget -> indexOpen = false
                    }
                }
                s = s.copy(
                    status = if (s.rssPagingDone && s.indexDone) BackfillStatus.COMPLETE else BackfillStatus.PENDING,
                    lastError = null,
                    nextEligibleAt = null,
                )
            } catch (e: CancellationException) {
                db.backfillStateDao().upsert(s.copy(status = BackfillStatus.PENDING))
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Backfill failed for ${podcast.id}", e)
                if (e is HttpException && e.code() == 429) {
                    rateLimiter.onRateLimited()
                    indexOpen = false
                }
                s = s.copy(
                    status = BackfillStatus.FAILED,
                    lastError = e.message ?: e.javaClass.simpleName,
                    nextEligibleAt = clock.now() + nextBackoff(state),
                )
            }
            db.backfillStateDao().upsert(s)
            touched++
            if (added > before) policyRunner.runDownloadPolicy(podcast.id)
        }
        return Summary(touched, added, indexRequests)
    }

    /** Subscribed podcasts with work left, in priority order: just subscribed, recently played, rest. */
    internal suspend fun candidates(): List<PodcastEntity> {
        val now = clock.now()
        val global = settingsRepository.settings.first()
        val states = db.backfillStateDao().getAll().associateBy { it.podcastId }
        val lastPlayed = db.episodeDao().lastPlayedPerPodcast().associate { it.podcastId to it.lastPlayedAt }
        return db.podcastDao().getSubscribed()
            .filter { p ->
                val st = states[p.id]
                val enabled = db.podcastSettingsDao().get(p.id)?.backfillEnabled ?: global.backfillEnabled
                enabled && st?.status != BackfillStatus.COMPLETE && st?.status != BackfillStatus.PAUSED &&
                    (st?.nextEligibleAt ?: 0) <= now
            }
            .sortedWith(
                compareByDescending<PodcastEntity> { (it.subscribedAt ?: 0) >= now - RECENT_MS }
                    .thenByDescending { lastPlayed[it.id] ?: 0 }
                    .thenByDescending { it.subscribedAt ?: 0 },
            )
    }

    private data class PageResult(val state: BackfillStateEntity, val pages: Int, val added: Int)

    /** Follows up to [maxPages] `rel="next"` pages. The first call discovers the link from the live feed. */
    private suspend fun pageRss(podcast: PodcastEntity, start: BackfillStateEntity, maxPages: Int): PageResult {
        var s = start
        var pages = 0
        var added = 0
        var url = s.rssNextPageUrl
        if (url == null) {
            val live = fetchPage(podcast, podcast.feedUrl) ?: throw IllegalStateException("Feed failed")
            pages++
            url = live.nextPageUrl
        }
        val seen = hashSetOf(podcast.feedUrl)
        while (url != null && pages < maxPages) {
            if (!seen.add(url)) { url = null; break } // a loop in the paging links
            sleep(RSS_SPACING_MS)
            val page = fetchPage(podcast, url) ?: throw IllegalStateException("Page failed: $url")
            pages++
            val n = feeds.mergeBackfill(podcast.id, page.episodes, EpisodeOrigin.FEED_PAGED)
            added += n
            s = s.copy(
                episodesAdded = s.episodesAdded + n,
                oldestKnownPubDate = minOfNullable(s.oldestKnownPubDate, page.episodes.mapNotNull { it.pubDate }.minOrNull()),
            )
            url = page.nextPageUrl
        }
        return PageResult(s.copy(rssNextPageUrl = url, rssPagingDone = url == null), pages, added)
    }

    private suspend fun fetchPage(podcast: PodcastEntity, url: String) =
        when (val r = fetcher.fetch(FeedRequest(url, authUser = podcast.authUser, authPassword = podcast.authPassword))) {
            is FetchResult.Success -> r.feed
            else -> null
        }

    private sealed interface IndexOutcome {
        data class Done(val state: BackfillStateEntity, val added: Int, val requested: Boolean) : IndexOutcome
        data object NoBudget : IndexOutcome
    }

    /** One `/episodes/byfeedid` call. The endpoint has no older-than cursor, so this is the whole pass. */
    private suspend fun fetchIndex(podcast: PodcastEntity, s: BackfillStateEntity): IndexOutcome {
        val indexId = podcast.podcastIndexId
        if (indexId == null || !credentials.isUsable) return IndexOutcome.Done(s.copy(indexDone = true), 0, false)
        if (!rateLimiter.tryAcquire(ApiPurpose.BACKFILL)) return IndexOutcome.NoBudget
        sleep(INDEX_SPACING_MS)
        val items = indexApi.episodesByFeedId(indexId, INDEX_MAX).items.mapNotNull { it.toParsed() }
        val n = feeds.mergeBackfill(podcast.id, items, EpisodeOrigin.PODCAST_INDEX)
        return IndexOutcome.Done(
            s.copy(
                indexDone = true,
                requestsMade = s.requestsMade + 1,
                episodesAdded = s.episodesAdded + n,
                oldestKnownPubDate = minOfNullable(s.oldestKnownPubDate, items.mapNotNull { it.pubDate }.minOrNull()),
            ),
            n,
            true,
        )
    }

    companion object {
        private const val TAG = "Backfill"
        const val MAX_PAGES_PER_RUN = 20
        const val MAX_PAGES_PER_PODCAST = 5
        const val RSS_SPACING_MS = 10_000L
        const val INDEX_SPACING_MS = 5_000L
        const val INDEX_MAX = 1000
        const val RECENT_MS = 7 * 24 * 60 * 60_000L
        private const val MIN_BACKOFF_MS = 60 * 60_000L
        private const val MAX_BACKOFF_MS = 24 * 60 * 60_000L

        /** Doubles after each consecutive failure: 1h, 2h, 4h … capped at 24h. */
        fun nextBackoff(previous: BackfillStateEntity): Long {
            val last = previous.nextEligibleAt?.let { at -> previous.lastAttemptAt?.let { at - it } }
            return if (previous.status == BackfillStatus.FAILED && last != null) {
                (last * 2).coerceIn(MIN_BACKOFF_MS, MAX_BACKOFF_MS)
            } else {
                MIN_BACKOFF_MS
            }
        }

        private fun minOfNullable(a: Long?, b: Long?): Long? = if (a == null) b else if (b == null) a else minOf(a, b)

        internal fun PodcastIndexEpisode.toParsed(): ParsedEpisode? {
            val url = enclosureUrl?.takeIf { it.isNotBlank() } ?: return null
            val pubMs = datePublished?.takeIf { it > 0 }?.times(1000)
            return ParsedEpisode(
                guid = guid?.takeIf { it.isNotBlank() } ?: url,
                title = title?.takeIf { it.isNotBlank() } ?: "Untitled episode",
                descriptionHtml = description,
                pubDate = pubMs,
                durationSec = duration?.takeIf { it > 0 },
                enclosureUrl = url,
                enclosureType = enclosureType,
                enclosureLength = enclosureLength?.takeIf { it > 0 },
                artworkUrl = image?.takeIf { it.isNotBlank() },
                season = season,
                episodeNumber = episode,
                chaptersUrl = chaptersUrl,
                transcriptUrl = transcriptUrl,
            )
        }
    }
}
