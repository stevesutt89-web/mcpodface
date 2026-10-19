package com.steve.mcpodface.data.catalog

/** A podcast as described by a catalogue (Podcast Index or iTunes). Not stored until previewed. */
data class CatalogPodcast(
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

data class CatalogCategory(val id: Int, val name: String)

class CatalogException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Podcast discovery (PLAN.md §3). Podcast Index first, iTunes as the fallback when Index fails or
 * returns nothing. Results are cached for 24h in catalog_cache. Every Index call goes through the
 * RateLimiter. Episode data never comes from here: feeds are fetched directly.
 */
interface CatalogRepository {
    /** @throws CatalogException when every source fails. */
    suspend fun search(term: String): List<CatalogPodcast>

    /** Trending podcasts, optionally in one category (by name). Empty if unavailable. */
    suspend fun trending(category: String? = null): List<CatalogPodcast>

    /** Podcast Index categories. Empty if unavailable. */
    suspend fun categories(): List<CatalogCategory>

    /** One Podcast Index lookup on subscribe, for podcastIndexId + episodeCount. null if unknown or failed. */
    suspend fun lookupByFeedUrl(feedUrl: String): CatalogPodcast?
}
