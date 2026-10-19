package com.steve.mcpodface.data.transcripts

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TranscriptRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()
    private val server = MockWebServer()

    @Before fun start() {
        server.start()
    }

    @After fun stop() {
        server.shutdown()
    }

    private val srt = "1\n00:00:00,000 --> 00:00:02,000\nHello there.\n"

    private fun repository(cache: String = "transcripts") = TranscriptRepository(
        client = OkHttpClient(),
        io = Dispatchers.Unconfined,
        cacheDirectory = tmp.root.resolve(cache),
    )

    @Test fun fetchesOnceThenServesFromMemory() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/x-subrip").setBody(srt))
        val repo = repository()
        val url = server.url("/t.srt").toString()

        assertThat(repo.load(url).single().text).isEqualTo("Hello there.")
        assertThat(repo.load(url).single().startMs).isEqualTo(0L)

        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test fun diskCacheKeepsTheDetectedFormatAcrossRestarts() = runBlocking<Unit> {
        // Served as plain text with no extension: only the body tells it is VTT, and that must survive the cache.
        val vtt = "WEBVTT\n\n00:00:01.000 --> 00:00:03.000\nCached cue.\n"
        server.enqueue(MockResponse().setHeader("Content-Type", "text/plain").setBody(vtt))
        val url = server.url("/transcript").toString()
        repository().load(url)

        server.shutdown()
        val segments = repository().load(url)

        assertThat(segments).containsExactly(TranscriptSegment(1_000L, 3_000L, null, "Cached cue."))
    }

    @Test fun serverErrorGivesEmptyAndIsNotCached() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setHeader("Content-Type", "application/x-subrip").setBody(srt))
        val repo = repository()
        val url = server.url("/t.srt").toString()

        assertThat(repo.load(url)).isEmpty()
        assertThat(repo.load(url)).hasSize(1)
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test fun blankBodyIsAFailure() = runBlocking {
        server.enqueue(MockResponse().setBody("   "))
        server.enqueue(MockResponse().setBody("Plain words."))
        val repo = repository()
        val url = server.url("/t").toString()

        assertThat(repo.load(url)).isEmpty()
        assertThat(repo.load(url).single().text).isEqualTo("Plain words.")
    }
}
