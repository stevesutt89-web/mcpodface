package com.steve.mcpodface.data.network

import com.google.common.truth.Truth.assertThat
import com.steve.mcpodface.data.catalog.FakeClock
import com.steve.mcpodface.data.catalog.respond
import com.steve.mcpodface.data.catalog.testPodcastIndexApi
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.security.MessageDigest

class PodcastIndexAuthTest {
    private val server = MockWebServer()

    @Before fun setUp() = server.start()
    @After fun tearDown() = server.shutdown()

    @Test fun signatureIsSha1OfKeySecretDate() {
        val expected = MessageDigest.getInstance("SHA-1")
            .digest("keysecret1700000000".toByteArray())
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        assertThat(podcastIndexAuthorization("key", "secret", 1700000000L)).isEqualTo(expected)
    }

    @Test fun signatureMatchesFixedVector() {
        // sha1("keysecret1700000000"), computed independently with sha1sum.
        assertThat(podcastIndexAuthorization("key", "secret", 1700000000L))
            .isEqualTo("abaf71c02050c31e4d4e6b08c1625173af0445ba")
    }

    @Test fun signatureChangesWithDateAndSecret() {
        val base = podcastIndexAuthorization("key", "secret", 1700000000L)
        assertThat(podcastIndexAuthorization("key", "secret", 1700000001L)).isNotEqualTo(base)
        assertThat(podcastIndexAuthorization("key", "other", 1700000000L)).isNotEqualTo(base)
        assertThat(base).matches("[0-9a-f]{40}")
    }

    @Test fun interceptorAddsAuthHeadersToEveryRequest() = runTest {
        server.respond("""{"feeds":[]}""")
        val creds = PodcastIndexCredentials("key", "secret")
        val clock = FakeClock(1_700_000_123_456L)
        val api = testPodcastIndexApi(server, creds, clock)

        api.categories()

        val request = server.takeRequest()
        assertThat(request.headers["X-Auth-Key"]).isEqualTo("key")
        assertThat(request.headers["X-Auth-Date"]).isEqualTo("1700000123")
        assertThat(request.headers["Authorization"])
            .isEqualTo(podcastIndexAuthorization("key", "secret", 1700000123L))
        assertThat(request.path).isEqualTo("/api/1.0/categories/list")
    }

    @Test fun headersArePresentOnQueryEndpointsToo() = runTest {
        server.respond("""{"feeds":[]}""")
        val api = testPodcastIndexApi(server, PodcastIndexCredentials("key", "secret"), FakeClock(1_700_000_000_000L))

        api.searchByTerm("radiolab", 40)

        val request = server.takeRequest()
        assertThat(request.headers["X-Auth-Key"]).isEqualTo("key")
        assertThat(request.headers["Authorization"]).isNotNull()
        assertThat(request.requestUrl!!.queryParameter("q")).isEqualTo("radiolab")
        assertThat(request.requestUrl!!.queryParameter("max")).isEqualTo("40")
    }

    @Test fun credentialsToStringDoesNotLeakSecret() {
        assertThat(PodcastIndexCredentials("key", "topsecret").toString()).doesNotContain("topsecret")
    }

    @Test fun blankCredentialsAreNotUsable() {
        assertThat(PodcastIndexCredentials("", "secret").isUsable).isFalse()
        assertThat(PodcastIndexCredentials("key", "  ").isUsable).isFalse()
        assertThat(PodcastIndexCredentials("key", "secret").isUsable).isTrue()
    }
}
