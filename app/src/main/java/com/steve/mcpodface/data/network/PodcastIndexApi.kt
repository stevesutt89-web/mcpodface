package com.steve.mcpodface.data.network

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Query

const val PODCAST_INDEX_BASE_URL = "https://api.podcastindex.org/api/1.0/"

/**
 * Lenient on purpose: Podcast Index is PHP-backed, so empty arrays show up where objects are expected
 * (e.g. `"feed": []`, `"categories": []`).
 */
@OptIn(ExperimentalSerializationApi::class)
internal val podcastIndexJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    isLenient = true
    explicitNulls = false
}

interface PodcastIndexApi {
    @GET("search/byterm")
    suspend fun searchByTerm(@Query("q") q: String, @Query("max") max: Int): PodcastIndexFeedsResponse

    @GET("podcasts/trending")
    suspend fun trending(
        @Query("max") max: Int,
        @Query("cat") cat: String?,
        @Query("lang") lang: String,
    ): PodcastIndexFeedsResponse

    @GET("categories/list")
    suspend fun categories(): PodcastIndexCategoriesResponse

    @GET("podcasts/byfeedurl")
    suspend fun byFeedUrl(@Query("url") url: String): PodcastIndexFeedResponse
}

@Serializable
data class PodcastIndexFeedsResponse(val feeds: List<PodcastIndexFeed> = emptyList())

@Serializable
data class PodcastIndexFeedResponse(val feed: JsonElement? = null) {
    /** `feed` is an object when found, `[]` or missing when not. */
    fun feedOrNull(): PodcastIndexFeed? =
        (feed as? JsonObject)?.let { podcastIndexJson.decodeFromJsonElement(PodcastIndexFeed.serializer(), it) }
}

@Serializable
data class PodcastIndexCategoriesResponse(val feeds: List<PodcastIndexCategory> = emptyList())

@Serializable
data class PodcastIndexCategory(val id: Int = 0, val name: String? = null)

@Serializable
data class PodcastIndexFeed(
    val id: Long? = null,
    val title: String? = null,
    val url: String? = null,
    val author: String? = null,
    val image: String? = null,
    val artwork: String? = null,
    val description: String? = null,
    val itunesId: Long? = null,
    val episodeCount: Int? = null,
    /** Object of id -> name, or `[]` when there are none. */
    val categories: JsonElement? = null,
) {
    fun categoryNames(): List<String> =
        (categories as? JsonObject)?.values?.mapNotNull { (it as? JsonPrimitive)?.content?.takeIf(String::isNotBlank) }
            ?: emptyList()
}
