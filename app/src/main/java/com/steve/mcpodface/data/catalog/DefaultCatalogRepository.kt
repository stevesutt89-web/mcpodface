package com.steve.mcpodface.data.catalog

import com.steve.mcpodface.core.Clock
import com.steve.mcpodface.core.di.IoDispatcher
import com.steve.mcpodface.data.db.CatalogCacheDao
import com.steve.mcpodface.data.db.CatalogCacheEntity
import com.steve.mcpodface.data.network.ApiPurpose
import com.steve.mcpodface.data.network.ITunesApi
import com.steve.mcpodface.data.network.ITunesPodcast
import com.steve.mcpodface.data.network.PodcastIndexApi
import com.steve.mcpodface.data.network.PodcastIndexCredentials
import com.steve.mcpodface.data.network.PodcastIndexFeed
import com.steve.mcpodface.data.network.RateLimiter
import com.steve.mcpodface.data.network.podcastIndexJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import retrofit2.HttpException
import javax.inject.Inject

private const val CACHE_TTL_MS = 24L * 60 * 60 * 1000
private const val MAX_RESULTS = 40
private const val CATEGORIES_KEY = "categories"

/** Cache shape for CatalogPodcast (which is deliberately not @Serializable). */
@Serializable
private data class CachedPodcast(
    val feedUrl: String,
    val title: String,
    val author: String? = null,
    val artworkUrl: String? = null,
    val description: String? = null,
    val podcastIndexId: Long? = null,
    val itunesId: Long? = null,
    val episodeCount: Int? = null,
    val categories: List<String> = emptyList(),
)

@Serializable
private data class CachedCategory(val id: Int, val name: String)

private val podcastListSerializer = ListSerializer(CachedPodcast.serializer())
private val categoryListSerializer = ListSerializer(CachedCategory.serializer())

/**
 * PLAN.md §3. Podcast Index first (behind the RateLimiter, skipped entirely without credentials),
 * iTunes when Index fails or returns nothing. Successful non-empty results are cached for 24h.
 */
class DefaultCatalogRepository @Inject constructor(
    private val podcastIndexApi: PodcastIndexApi,
    private val iTunesApi: ITunesApi,
    private val credentials: PodcastIndexCredentials,
    private val rateLimiter: RateLimiter,
    private val cacheDao: CatalogCacheDao,
    private val clock: Clock,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : CatalogRepository {

    private val cacheJson = Json { ignoreUnknownKeys = true }

    override suspend fun search(term: String): List<CatalogPodcast> = withContext(ioDispatcher) {
        val query = term.trim()
        if (query.isEmpty()) return@withContext emptyList()
        val key = "search:" + query.lowercase()
        readPodcasts(key)?.let { return@withContext it }

        val index: List<CatalogPodcast>? = if (indexAllowed(ApiPurpose.DISCOVERY)) {
            indexCall { podcastIndexApi.searchByTerm(query, MAX_RESULTS).feeds.toIndexCatalog() }
        } else {
            null
        }
        if (!index.isNullOrEmpty()) {
            writePodcasts(key, index)
            return@withContext index
        }

        val itunes = try {
            iTunesApi.search(query).results.toITunesCatalog()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Index answered, just with nothing: that is an empty result, not a failure.
            if (index != null) return@withContext emptyList()
            throw CatalogException("Catalogue search failed for \"$query\"", e)
        }
        if (itunes.isNotEmpty()) writePodcasts(key, itunes)
        itunes
    }

    override suspend fun trending(category: String?): List<CatalogPodcast> = withContext(ioDispatcher) {
        val cat = category?.trim()?.takeIf { it.isNotEmpty() }
        val key = "trending:" + (cat ?: "")
        readPodcasts(key)?.let { return@withContext it }
        if (!indexAllowed(ApiPurpose.DISCOVERY)) return@withContext emptyList()

        val results = indexCall { podcastIndexApi.trending(MAX_RESULTS, cat, "en").feeds.toIndexCatalog() }
            ?: return@withContext emptyList()
        if (results.isEmpty()) return@withContext emptyList()
        writePodcasts(key, results)
        results
    }

    override suspend fun categories(): List<CatalogCategory> = withContext(ioDispatcher) {
        readCategories(CATEGORIES_KEY)?.let { return@withContext it }
        if (!indexAllowed(ApiPurpose.DISCOVERY)) return@withContext emptyList()

        val results = indexCall {
            podcastIndexApi.categories().feeds.mapNotNull { c ->
                c.name?.trim()?.takeIf { it.isNotEmpty() }?.let { CatalogCategory(c.id, it) }
            }
        } ?: return@withContext emptyList()
        if (results.isEmpty()) return@withContext emptyList()
        writeCategories(CATEGORIES_KEY, results)
        results
    }

    override suspend fun lookupByFeedUrl(feedUrl: String): CatalogPodcast? = withContext(ioDispatcher) {
        val url = feedUrl.trim()
        if (url.isEmpty() || !indexAllowed(ApiPurpose.SUBSCRIBE)) return@withContext null
        // Not cached: this runs once per subscribe.
        indexCall { podcastIndexApi.byFeedUrl(url).feedOrNull()?.toCatalog() }
    }

    /** Index is usable only with credentials, and only if the limiter grants a request. */
    private suspend fun indexAllowed(purpose: ApiPurpose): Boolean =
        credentials.isUsable && rateLimiter.tryAcquire(purpose)

    /** Runs one Index call. Null on any failure; a 429 is reported to the limiter first. */
    private suspend fun <T> indexCall(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        if (e is HttpException && e.code() == 429) rateLimiter.onRateLimited()
        null
    }

    // ---- cache ----

    private suspend fun readPodcasts(key: String): List<CatalogPodcast>? =
        readCache(key, podcastListSerializer)?.map { c ->
            CatalogPodcast(
                feedUrl = c.feedUrl,
                title = c.title,
                author = c.author,
                artworkUrl = c.artworkUrl,
                description = c.description,
                podcastIndexId = c.podcastIndexId,
                itunesId = c.itunesId,
                episodeCount = c.episodeCount,
                categories = c.categories,
            )
        }

    private suspend fun writePodcasts(key: String, podcasts: List<CatalogPodcast>) =
        writeCache(key, podcastListSerializer, podcasts.map { p ->
            CachedPodcast(
                feedUrl = p.feedUrl,
                title = p.title,
                author = p.author,
                artworkUrl = p.artworkUrl,
                description = p.description,
                podcastIndexId = p.podcastIndexId,
                itunesId = p.itunesId,
                episodeCount = p.episodeCount,
                categories = p.categories,
            )
        })

    private suspend fun readCategories(key: String): List<CatalogCategory>? =
        readCache(key, categoryListSerializer)?.map { CatalogCategory(it.id, it.name) }

    private suspend fun writeCategories(key: String, categories: List<CatalogCategory>) =
        writeCache(key, categoryListSerializer, categories.map { CachedCategory(it.id, it.name) })

    /** Null when missing, older than 24h, or unreadable. */
    private suspend fun <T> readCache(key: String, serializer: KSerializer<List<T>>): List<T>? {
        val row = cacheDao.get(key) ?: return null
        if (clock.now() - row.fetchedAt >= CACHE_TTL_MS) return null
        return try {
            cacheJson.decodeFromString(serializer, row.json)
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun <T> writeCache(key: String, serializer: KSerializer<List<T>>, items: List<T>) {
        cacheDao.upsert(CatalogCacheEntity(key, cacheJson.encodeToString(serializer, items), clock.now()))
    }

    // ---- mapping ----

    private fun PodcastIndexFeed.toCatalog(): CatalogPodcast? {
        val feedUrl = url?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return CatalogPodcast(
            feedUrl = feedUrl,
            title = title?.trim()?.takeIf { it.isNotEmpty() } ?: feedUrl,
            author = author?.takeIf { it.isNotBlank() },
            artworkUrl = listOf(artwork, image).firstOrNull { !it.isNullOrBlank() },
            description = description?.takeIf { it.isNotBlank() },
            podcastIndexId = id,
            itunesId = itunesId?.takeIf { it > 0 },
            episodeCount = episodeCount,
            categories = categoryNames(),
        )
    }

    private fun List<PodcastIndexFeed>.toIndexCatalog(): List<CatalogPodcast> = mapNotNull { it.toCatalog() }

    private fun ITunesPodcast.toCatalog(): CatalogPodcast? {
        val feedUrl = feedUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return CatalogPodcast(
            feedUrl = feedUrl,
            title = collectionName?.trim()?.takeIf { it.isNotEmpty() } ?: feedUrl,
            author = artistName?.takeIf { it.isNotBlank() },
            artworkUrl = listOf(artworkUrl600, artworkUrl100).firstOrNull { !it.isNullOrBlank() },
            itunesId = collectionId,
            categories = genres,
        )
    }

    private fun List<ITunesPodcast>.toITunesCatalog(): List<CatalogPodcast> = mapNotNull { it.toCatalog() }
}
