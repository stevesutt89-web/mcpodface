package com.steve.mcpodface.data.network

import com.steve.mcpodface.core.Clock
import okhttp3.Interceptor
import okhttp3.Response
import java.security.MessageDigest

/** Podcast Index API key and secret (from BuildConfig). Blank values mean Podcast Index is unavailable. */
data class PodcastIndexCredentials(val key: String, val secret: String) {
    val isUsable: Boolean get() = key.isNotBlank() && secret.isNotBlank()

    /** Never print the secret. */
    override fun toString(): String = "PodcastIndexCredentials(key=$key, secret=***)"
}

/**
 * Podcast Index `Authorization` header: lowercase hex SHA-1 of `key + secret + unixSeconds`.
 * Pure function so the signing rule can be tested against a fixed vector.
 */
fun podcastIndexAuthorization(key: String, secret: String, unixSeconds: Long): String {
    val digest = MessageDigest.getInstance("SHA-1")
        .digest("$key$secret$unixSeconds".toByteArray(Charsets.UTF_8))
    return buildString(digest.size * 2) {
        for (b in digest) {
            val v = b.toInt() and 0xff
            append(HEX[v shr 4])
            append(HEX[v and 0x0f])
        }
    }
}

private const val HEX = "0123456789abcdef"

/** Adds the Podcast Index auth headers to every request on the Index client. */
class PodcastIndexAuthInterceptor(
    private val credentials: PodcastIndexCredentials,
    private val clock: Clock,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val unixSeconds = clock.now() / 1000
        val request = chain.request().newBuilder()
            .header("X-Auth-Key", credentials.key)
            .header("X-Auth-Date", unixSeconds.toString())
            .header("Authorization", podcastIndexAuthorization(credentials.key, credentials.secret, unixSeconds))
            .build()
        return chain.proceed(request)
    }
}
