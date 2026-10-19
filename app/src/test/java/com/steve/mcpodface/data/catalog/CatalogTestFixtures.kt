package com.steve.mcpodface.data.catalog

import com.steve.mcpodface.core.Clock
import com.steve.mcpodface.data.db.CatalogCacheDao
import com.steve.mcpodface.data.db.CatalogCacheEntity
import com.steve.mcpodface.data.network.ApiPurpose
import com.steve.mcpodface.data.network.ITunesApi
import com.steve.mcpodface.data.network.PodcastIndexApi
import com.steve.mcpodface.data.network.PodcastIndexAuthInterceptor
import com.steve.mcpodface.data.network.PodcastIndexCredentials
import com.steve.mcpodface.data.network.podcastIndexJson
import com.steve.mcpodface.data.network.RateLimiter
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class FakeClock(var millis: Long) : Clock {
    override fun now(): Long = millis
}

class FakeRateLimiter(var allow: Boolean = true) : RateLimiter {
    val purposes = mutableListOf<ApiPurpose>()
    var rateLimitedCount = 0

    override suspend fun tryAcquire(purpose: ApiPurpose): Boolean {
        purposes += purpose
        return allow
    }

    override suspend fun onRateLimited() {
        rateLimitedCount++
    }
}

class FakeCatalogCacheDao : CatalogCacheDao {
    val rows = mutableMapOf<String, CatalogCacheEntity>()

    override suspend fun upsert(entry: CatalogCacheEntity) {
        rows[entry.key] = entry
    }

    override suspend fun get(key: String): CatalogCacheEntity? = rows[key]

    override suspend fun deleteOlderThan(before: Long) {
        rows.values.removeAll { it.fetchedAt < before }
    }
}

/** Same wiring as CatalogModule, but pointed at a MockWebServer. */
fun testPodcastIndexApi(
    server: MockWebServer,
    credentials: PodcastIndexCredentials,
    clock: Clock,
): PodcastIndexApi = Retrofit.Builder()
    .baseUrl(server.url("/api/1.0/"))
    .client(OkHttpClient.Builder().addInterceptor(PodcastIndexAuthInterceptor(credentials, clock)).build())
    .addConverterFactory(podcastIndexJson.asConverterFactory("application/json".toMediaType()))
    .build()
    .create(PodcastIndexApi::class.java)

fun testITunesApi(server: MockWebServer): ITunesApi = Retrofit.Builder()
    .baseUrl(server.url("/"))
    .client(OkHttpClient())
    .addConverterFactory(podcastIndexJson.asConverterFactory("application/json".toMediaType()))
    .build()
    .create(ITunesApi::class.java)

fun MockWebServer.respond(body: String, code: Int = 200) {
    enqueue(
        MockResponse()
            .setResponseCode(code)
            .setHeader("Content-Type", "application/json")
            .setBody(body),
    )
}
