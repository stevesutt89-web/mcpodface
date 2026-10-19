package com.steve.mcpodface.backfill

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.steve.mcpodface.core.Clock
import com.steve.mcpodface.data.catalog.testPodcastIndexApi
import com.steve.mcpodface.data.db.AppDatabase
import com.steve.mcpodface.data.db.BackfillStateEntity
import com.steve.mcpodface.data.db.BackfillStatus
import com.steve.mcpodface.data.db.EpisodeEntity
import com.steve.mcpodface.data.db.EpisodeOrigin
import com.steve.mcpodface.data.db.PodcastEntity
import com.steve.mcpodface.data.db.PodcastSettingsEntity
import com.steve.mcpodface.data.feed.EpisodeMerger
import com.steve.mcpodface.data.feed.FeedFetcher
import com.steve.mcpodface.data.feed.FeedRepository
import com.steve.mcpodface.data.feed.RssParser
import com.steve.mcpodface.data.network.NetworkModule
import com.steve.mcpodface.data.network.PersistedRateLimiter
import com.steve.mcpodface.data.network.PodcastIndexCredentials
import com.steve.mcpodface.data.prefs.GlobalSettings
import com.steve.mcpodface.data.prefs.SettingsRepository
import com.steve.mcpodface.download.DownloadManager
import com.steve.mcpodface.playback.NoOpPlayerController
import com.steve.mcpodface.refresh.PolicyRunner
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackfillRunnerTest {
    private val server = MockWebServer()
    private lateinit var db: AppDatabase
    private val now = 1_800_000_000_000L
    private val clock = Clock { now }
    private val credentials = PodcastIndexCredentials("key", "secret")

    private val settings = object : SettingsRepository {
        val state = MutableStateFlow(GlobalSettings())
        override val settings = state
        override suspend fun update(transform: (GlobalSettings) -> GlobalSettings) =
            transform(state.value).also { state.value = it }
    }

    /** path -> response body (or an HTTP code as "429"). */
    private val routes = mutableMapOf<String, String>()
    private val requested = mutableListOf<String>()

    @Before fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path!!.substringBefore('?')
                synchronized(requested) { requested += path }
                val body = routes[path] ?: return MockResponse().setResponseCode(404)
                return if (body.toIntOrNull() != null) MockResponse().setResponseCode(body.toInt()) else MockResponse().setBody(body)
            }
        }
        server.start()
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After fun tearDown() {
        db.close()
        server.shutdown()
    }

    private fun runner(): BackfillRunner {
        val fetcher = FeedFetcher(NetworkModule.okHttpClient(), RssParser(), Dispatchers.Unconfined)
        val policy = PolicyRunner(db, settings, mockk<DownloadManager>(relaxed = true), NoOpPlayerController(), clock)
        return BackfillRunner(
            db, fetcher, FeedRepository(db, fetcher, EpisodeMerger(), clock),
            testPodcastIndexApi(server, credentials, clock), credentials,
            PersistedRateLimiter(settings, clock), settings, policy, clock,
        ).also { it.sleep = {} }
    }

    private fun url(path: String) = server.url(path).toString()

    private fun feed(guids: List<String>, next: String? = null) = """
        <rss version="2.0" xmlns:atom="http://www.w3.org/2005/Atom"><channel><title>Show</title>
        ${next?.let { """<atom:link rel="next" href="${url(it)}"/>""" } ?: ""}
        ${guids.joinToString("") { g -> """<item><guid>$g</guid><title>$g</title><pubDate>Mon, 19 Oct 2026 10:00:00 GMT</pubDate><enclosure url="https://cdn.example.com/$g.mp3" type="audio/mpeg"/></item>""" }}
        </channel></rss>
    """.trimIndent()

    private fun indexJson(vararg guids: String) =
        """{"status":"true","items":[${guids.joinToString(",") { g -> """{"guid":"$g","title":"$g","datePublished":1500000000,"enclosureUrl":"https://cdn.example.com/$g.mp3","duration":60}""" }}]}"""

    /** A subscribed podcast whose live feed (a1, a2) is already stored. */
    private suspend fun podcast(indexId: Long? = 7, subscribedAt: Long = now - 30L * 86_400_000, path: String = "/feed"): Long {
        val id = db.podcastDao().insert(
            PodcastEntity(feedUrl = url(path), title = "Show", isSubscribed = true, subscribedAt = subscribedAt, podcastIndexId = indexId),
        )
        db.episodeDao().insertAll(
            listOf("a1", "a2").map {
                EpisodeEntity(podcastId = id, guid = it, title = it, pubDate = now, enclosureUrl = "https://cdn.example.com/$it.mp3", firstSeenAt = now)
            },
        )
        db.backfillStateDao().upsert(BackfillStateEntity(podcastId = id))
        return id
    }

    @Test fun pagesThroughRssThenPodcastIndexAndCompletes() = runTest {
        val id = podcast()
        routes["/feed"] = feed(listOf("a1", "a2"), next = "/p2")
        routes["/p2"] = feed(listOf("b1"), next = "/p3")
        routes["/p3"] = feed(listOf("c1"))
        routes["/api/1.0/episodes/byfeedid"] = indexJson("a1", "d1")

        val summary = runner().run()

        val episodes = db.episodeDao().getForPodcast(id).associateBy { it.guid }
        assertThat(episodes.keys).containsExactly("a1", "a2", "b1", "c1", "d1")
        assertThat(episodes.getValue("b1").origin).isEqualTo(EpisodeOrigin.FEED_PAGED)
        assertThat(episodes.getValue("d1").origin).isEqualTo(EpisodeOrigin.PODCAST_INDEX)
        assertThat(episodes.getValue("d1").inCurrentFeed).isFalse()
        val state = db.backfillStateDao().get(id)!!
        assertThat(state.status).isEqualTo(BackfillStatus.COMPLETE)
        assertThat(state.episodesAdded).isEqualTo(3)
        assertThat(state.requestsMade).isEqualTo(1)
        assertThat(summary.indexRequests).isEqualTo(1)
        assertThat(settings.state.value.apiRequestsToday).isEqualTo(1)
    }

    @Test fun resumesFromTheStoredPageCursor() = runTest {
        val id = podcast(indexId = null)
        db.backfillStateDao().upsert(BackfillStateEntity(podcastId = id, rssNextPageUrl = url("/p3")))
        routes["/p3"] = feed(listOf("c1"))

        runner().run()

        assertThat(requested).containsExactly("/p3")
        assertThat(db.backfillStateDao().get(id)!!.status).isEqualTo(BackfillStatus.COMPLETE)
    }

    @Test fun stopsAfterTheBatchAndSavesTheCursor() = runTest {
        val id = podcast(indexId = null)
        routes["/feed"] = feed(listOf("a1"), next = "/p1")
        for (n in 1..10) routes["/p$n"] = feed(listOf("x$n"), next = "/p${n + 1}")

        runner().run()

        val state = db.backfillStateDao().get(id)!!
        assertThat(state.rssPagingDone).isFalse()
        assertThat(state.rssNextPageUrl).isEqualTo(url("/p5"))
        assertThat(state.status).isEqualTo(BackfillStatus.PENDING)
        assertThat(requested).hasSize(BackfillRunner.MAX_PAGES_PER_PODCAST)
    }

    @Test fun pagingLoopIsDetected() = runTest {
        val id = podcast(indexId = null)
        routes["/feed"] = feed(listOf("a1"), next = "/p2")
        routes["/p2"] = feed(listOf("b1"), next = "/p2")

        runner().run()

        assertThat(db.backfillStateDao().get(id)!!.rssPagingDone).isTrue()
    }

    @Test fun noBudgetLeavesThePodcastIndexStepForLater() = runTest {
        settings.state.value = GlobalSettings(apiDailyBudget = 1) // backfill allowance rounds to 0
        val id = podcast()
        routes["/feed"] = feed(listOf("a1"))

        runner().run()

        val state = db.backfillStateDao().get(id)!!
        assertThat(state.rssPagingDone).isTrue()
        assertThat(state.indexDone).isFalse()
        assertThat(state.status).isEqualTo(BackfillStatus.PENDING)
        assertThat(requested).doesNotContain("/api/1.0/episodes/byfeedid")
    }

    @Test fun http429PausesBackfillAndBacksOff() = runTest {
        val id = podcast()
        routes["/feed"] = feed(listOf("a1"))
        routes["/api/1.0/episodes/byfeedid"] = "429"
        val r = runner()

        r.run()

        val state = db.backfillStateDao().get(id)!!
        assertThat(state.status).isEqualTo(BackfillStatus.FAILED)
        assertThat(state.nextEligibleAt).isEqualTo(now + 60 * 60_000L)
        assertThat(settings.state.value.apiPausedUntil).isEqualTo(now + PersistedRateLimiter.PAUSE_MS)
        assertThat(r.candidates()).isEmpty()
    }

    @Test fun backoffDoublesAndIsCapped() {
        val failed = BackfillStateEntity(podcastId = 1, status = BackfillStatus.FAILED, lastAttemptAt = 0, nextEligibleAt = 60 * 60_000L)
        assertThat(BackfillRunner.nextBackoff(failed)).isEqualTo(2 * 60 * 60_000L)
        assertThat(BackfillRunner.nextBackoff(failed.copy(nextEligibleAt = 20 * 60 * 60_000L))).isEqualTo(24 * 60 * 60_000L)
        assertThat(BackfillRunner.nextBackoff(BackfillStateEntity(podcastId = 1))).isEqualTo(60 * 60_000L)
    }

    @Test fun candidatesSkipCompleteAndDisabledAndPutNewSubscriptionsFirst() = runTest {
        val old = podcast()
        val fresh = podcast(subscribedAt = now - 86_400_000, path = "/fresh")
        val done = podcast(path = "/done")
        db.backfillStateDao().upsert(BackfillStateEntity(podcastId = done, status = BackfillStatus.COMPLETE))
        val off = podcast(path = "/off")
        db.podcastSettingsDao().upsert(PodcastSettingsEntity(podcastId = off, backfillEnabled = false))

        assertThat(runner().candidates().map { it.id }).containsExactly(fresh, old).inOrder()
    }

    @Test fun globallyDisabledDoesNothing() = runTest {
        settings.state.value = GlobalSettings(backfillEnabled = false)
        podcast()
        assertThat(runner().run().podcastsTouched).isEqualTo(0)
        assertThat(requested).isEmpty()
    }
}
