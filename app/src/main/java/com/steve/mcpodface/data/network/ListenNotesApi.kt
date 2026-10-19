package com.steve.mcpodface.data.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.Interceptor
import okhttp3.Response
import retrofit2.http.GET
import retrofit2.http.Query

const val LISTEN_NOTES_BASE_URL = "https://listen-api.listennotes.com/api/v2/"

/** Listen Notes API key from local.properties (LISTENNOTES_KEY). Blank = source disabled. */
class ListenNotesKey(val value: String) {
    val isUsable: Boolean get() = value.isNotBlank()
    override fun toString() = "ListenNotesKey(${if (isUsable) "set" else "blank"})"
}

class ListenNotesAuthInterceptor(private val key: ListenNotesKey) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response =
        chain.proceed(chain.request().newBuilder().header("X-ListenAPI-Key", key.value).build())
}

/** Listen Notes Podcast API v2: an optional extra discovery source (search, best podcasts, genres). */
interface ListenNotesApi {
    @GET("search?type=podcast&safe_mode=0")
    suspend fun searchPodcasts(@Query("q") query: String): ListenNotesSearchResponse

    @GET("best_podcasts?safe_mode=0")
    suspend fun bestPodcasts(@Query("genre_id") genreId: Int? = null, @Query("region") region: String = "us"): ListenNotesBestResponse

    @GET("genres?top_level_only=1")
    suspend fun genres(): ListenNotesGenresResponse
}

/** A podcast from /search (the *_original fields) or /best_podcasts (plain fields). */
@Serializable
data class ListenNotesPodcast(
    val id: String? = null,
    val rss: String? = null,
    val title: String? = null,
    @SerialName("title_original") val titleOriginal: String? = null,
    val publisher: String? = null,
    @SerialName("publisher_original") val publisherOriginal: String? = null,
    val description: String? = null,
    @SerialName("description_original") val descriptionOriginal: String? = null,
    val image: String? = null,
    val thumbnail: String? = null,
    @SerialName("itunes_id") val itunesId: Long? = null,
    @SerialName("total_episodes") val totalEpisodes: Int? = null,
)

@Serializable
data class ListenNotesSearchResponse(val results: List<ListenNotesPodcast> = emptyList())

@Serializable
data class ListenNotesBestResponse(val podcasts: List<ListenNotesPodcast> = emptyList())

@Serializable
data class ListenNotesGenre(val id: Int, val name: String)

@Serializable
data class ListenNotesGenresResponse(val genres: List<ListenNotesGenre> = emptyList())
