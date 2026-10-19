package com.steve.mcpodface.data.network

import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Query

const val ITUNES_BASE_URL = "https://itunes.apple.com/"

/** iTunes Search API (fallback catalogue, no key needed). */
interface ITunesApi {
    @GET("search?media=podcast&entity=podcast&limit=40")
    suspend fun search(@Query("term") term: String): ITunesSearchResponse
}

@Serializable
data class ITunesSearchResponse(val results: List<ITunesPodcast> = emptyList())

@Serializable
data class ITunesPodcast(
    val collectionId: Long? = null,
    val collectionName: String? = null,
    val artistName: String? = null,
    val feedUrl: String? = null,
    val artworkUrl600: String? = null,
    val artworkUrl100: String? = null,
    val genres: List<String> = emptyList(),
)
