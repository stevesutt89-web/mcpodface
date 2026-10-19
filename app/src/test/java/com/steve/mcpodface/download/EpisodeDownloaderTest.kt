package com.steve.mcpodface.download

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.steve.mcpodface.core.Clock
import com.steve.mcpodface.data.db.AppDatabase
import com.steve.mcpodface.data.db.DownloadEntity
import com.steve.mcpodface.data.db.DownloadSource
import com.steve.mcpodface.data.db.DownloadState
import com.steve.mcpodface.data.db.EpisodeEntity
import com.steve.mcpodface.data.db.PodcastEntity
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EpisodeDownloaderTest {
    @get:Rule val tmp = TemporaryFolder()
    private val server = MockWebServer()
    private lateinit var db: AppDatabase
    private lateinit var paths: DownloadPaths
    private lateinit var downloader: EpisodeDownloader
    private var episodeId = 0L
    private val audio = ByteArray(300_000) { (it % 251).toByte() }

    @Before fun setUp() = runTest {
        server.start()
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        paths = DownloadPaths(ApplicationProvider.getApplicationContext()).apply { root = tmp.newFolder("podcasts") }
        downloader = EpisodeDownloader(OkHttpClient(), paths, db.episodeDao(), db.downloadDao(), Clock { 0L })
        val podcastId = db.podcastDao().insert(PodcastEntity(feedUrl = "https://x/feed", title = "X"))
        episodeId = db.episodeDao().insertAll(
            listOf(
                EpisodeEntity(
                    podcastId = podcastId, guid = "g", title = "Ep", pubDate = 0,
                    enclosureUrl = server.url("/audio/ep1.mp3?tracking=1").toString(), firstSeenAt = 0,
                ),
            ),
        ).single()
        db.downloadDao().upsert(DownloadEntity(episodeId = episodeId, state = DownloadState.QUEUED, source = DownloadSource.AUTO))
    }

    @After fun tearDown() {
        db.close()
        server.shutdown()
    }

    private fun body(bytes: ByteArray) = Buffer().write(bytes)
    private suspend fun episode() = db.episodeDao().getById(episodeId)!!

    @Test fun fullDownloadWritesFileAndCompletesRow() = runTest {
        server.enqueue(MockResponse().setBody(body(audio)))
        val outcome = downloader.download(episodeId) as DownloadOutcome.Completed

        assertThat(outcome.file.readBytes()).isEqualTo(audio)
        assertThat(outcome.file.name).isEqualTo("$episodeId.mp3")
        assertThat(paths.partFile(episode()).exists()).isFalse()
        val row = db.downloadDao().get(episodeId)!!
        assertThat(row.state).isEqualTo(DownloadState.COMPLETED)
        assertThat(row.filePath).isEqualTo(outcome.file.path)
        assertThat(row.totalBytes).isEqualTo(audio.size.toLong())
    }

    @Test fun resumesFromPartFileWithRangeRequest() = runTest {
        val part = paths.partFile(episode()).apply { parentFile!!.mkdirs(); writeBytes(audio.copyOfRange(0, 100_000)) }
        server.enqueue(
            MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "bytes 100000-299999/300000")
                .setBody(body(audio.copyOfRange(100_000, audio.size))),
        )

        val outcome = downloader.download(episodeId) as DownloadOutcome.Completed

        assertThat(server.takeRequest().getHeader("Range")).isEqualTo("bytes=100000-")
        assertThat(outcome.file.readBytes()).isEqualTo(audio)
        assertThat(part.exists()).isFalse()
    }

    @Test fun serverIgnoringRangeRestartsFromZero() = runTest {
        paths.partFile(episode()).apply { parentFile!!.mkdirs(); writeBytes(ByteArray(5_000) { 9 }) }
        server.enqueue(MockResponse().setBody(body(audio)))
        val outcome = downloader.download(episodeId) as DownloadOutcome.Completed
        assertThat(outcome.file.readBytes()).isEqualTo(audio)
    }

    @Test fun connectionCutShortIsRetryableAndKeepsPartialFile() = runTest {
        server.enqueue(
            MockResponse().setBody(body(audio)).setHeader("Content-Length", audio.size.toString())
                .setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
        )
        val outcome = downloader.download(episodeId)
        assertThat(outcome).isInstanceOf(DownloadOutcome.Retryable::class.java)
        assertThat(paths.finalFile(episode()).exists()).isFalse()
    }

    @Test fun notFoundIsAPermanentFailure() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        assertThat(downloader.download(episodeId)).isInstanceOf(DownloadOutcome.Failed::class.java)
    }

    @Test fun serverErrorIsRetryable() = runTest {
        server.enqueue(MockResponse().setResponseCode(503))
        assertThat(downloader.download(episodeId)).isInstanceOf(DownloadOutcome.Retryable::class.java)
    }

    @Test fun fullyDownloadedPartWith416Completes() = runTest {
        paths.partFile(episode()).apply { parentFile!!.mkdirs(); writeBytes(audio) }
        server.enqueue(MockResponse().setResponseCode(416))
        val outcome = downloader.download(episodeId) as DownloadOutcome.Completed
        assertThat(outcome.file.readBytes()).isEqualTo(audio)
    }

    @Test fun extensionComesFromUrlThenMimeType() {
        fun ep(url: String, type: String?) = EpisodeEntity(
            podcastId = 1, guid = "g", title = "t", pubDate = 0, enclosureUrl = url, enclosureType = type, firstSeenAt = 0,
        )
        assertThat(DownloadPaths.extensionFor(ep("https://a/b/ep.m4a?x=1", null))).isEqualTo("m4a")
        assertThat(DownloadPaths.extensionFor(ep("https://a/b/stream", "audio/mp4"))).isEqualTo("m4a")
        assertThat(DownloadPaths.extensionFor(ep("https://a/b/stream.php", "audio/ogg"))).isEqualTo("ogg")
        assertThat(DownloadPaths.extensionFor(ep("https://a/b/stream", null))).isEqualTo("mp3")
    }
}
