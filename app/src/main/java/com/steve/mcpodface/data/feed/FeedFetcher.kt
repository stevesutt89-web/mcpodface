package com.steve.mcpodface.data.feed

import com.steve.mcpodface.core.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import javax.inject.Inject

sealed interface FetchResult {
    /** 304: the feed has not changed since [FeedRequest.etag] / [FeedRequest.lastModified]. */
    data object NotModified : FetchResult

    data class Success(
        val feed: ParsedFeed,
        val etag: String?,
        val lastModified: String?,
        /** Set when every redirect hop was permanent (301/308): store this as the new feedUrl. */
        val movedPermanentlyTo: String?,
    ) : FetchResult

    /** 410: the publisher has retired the feed. */
    data object Gone : FetchResult

    data class Failed(val message: String, val httpCode: Int? = null) : FetchResult
}

data class FeedRequest(
    val url: String,
    val etag: String? = null,
    val lastModified: String? = null,
    val authUser: String? = null,
    val authPassword: String? = null,
)

/** Downloads and parses one feed page with a conditional GET (PLAN.md §5). No API budget used. */
class FeedFetcher @Inject constructor(
    private val client: OkHttpClient,
    private val parser: RssParser,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    suspend fun fetch(request: FeedRequest): FetchResult = withContext(io) {
        val builder = Request.Builder().url(request.url)
            .header("Accept", "application/rss+xml, application/xml;q=0.9, text/xml;q=0.8, */*;q=0.5")
        request.etag?.let { builder.header("If-None-Match", it) }
        request.lastModified?.let { builder.header("If-Modified-Since", it) }
        if (!request.authUser.isNullOrEmpty()) {
            builder.header("Authorization", Credentials.basic(request.authUser, request.authPassword.orEmpty()))
        }
        val httpRequest = try {
            builder.build()
        } catch (e: IllegalArgumentException) {
            return@withContext FetchResult.Failed("Invalid feed URL: ${request.url}")
        }

        try {
            client.newCall(httpRequest).execute().use { response ->
                when {
                    response.code == 304 -> FetchResult.NotModified
                    response.code == 410 -> FetchResult.Gone
                    !response.isSuccessful -> FetchResult.Failed("HTTP ${response.code}", response.code)
                    else -> {
                        val feed = response.body.byteStream().use { parser.parse(it, request.url) }
                        FetchResult.Success(
                            feed = feed,
                            etag = response.header("ETag"),
                            lastModified = response.header("Last-Modified"),
                            movedPermanentlyTo = permanentRedirectTarget(response, request.url),
                        )
                    }
                }
            }
        } catch (e: FeedParseException) {
            FetchResult.Failed("Not a valid podcast feed: ${e.message}")
        } catch (e: IOException) {
            FetchResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    /** The final URL if the chain had at least one redirect and all of them were permanent. */
    private fun permanentRedirectTarget(response: Response, originalUrl: String): String? {
        var prior = response.priorResponse ?: return null
        while (true) {
            if (prior.code != 301 && prior.code != 308) return null
            prior = prior.priorResponse ?: break
        }
        val finalUrl = response.request.url.toString()
        return finalUrl.takeIf { it != originalUrl }
    }
}
