package com.steve.mcpodface.data.feed

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.steve.mcpodface.core.Clock
import com.steve.mcpodface.data.catalog.CatalogCategory
import com.steve.mcpodface.data.catalog.CatalogPodcast
import com.steve.mcpodface.data.catalog.CatalogRepository
import com.steve.mcpodface.data.db.AppDatabase
import com.steve.mcpodface.data.db.BackfillStatus
import com.steve.mcpodface.data.network.NetworkModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SubscriptionRepositoryTest {
    private val server = MockWebServer()
    private lateinit var db: AppDatabase
    private val now = 1_800_000_000_000L
    private val downloads = io.mockk.mockk<com.steve.mcpodface.download.DownloadManager>(relaxed = true)

    private val feedXml = """
        <rss version="2.0"><channel><title>Real Title</title>
          <item><guid>a</guid><title>A</title><pubDate>Mon, 19 Oct 2026 10:00:00 GMT</pubDate>
            <enclosure url="https://cdn.example.com/a.mp3" type="audio/mpeg"/></item>
        </channel></rss>
    """.trimIndent()

    private class FakeCatalog(private val found: CatalogPodcast?) : CatalogRepository {
        var lookups = 0
        override suspend fun search(term: String) = emptyList<CatalogPodcast>()
        override suspend fun trending(category: String?) = emptyList<CatalogPodcast>()
        override suspend fun categories() = emptyList<CatalogCategory>()
        override suspend fun lookupByFeedUrl(feedUrl: String): CatalogPodcast? { lookups++; return found }
    }

    @Before fun setUp() {
        server.start()
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After fun tearDown() {
        db.close()
        server.shutdown()
    }

    /** The post-subscribe work runs on Room's executor, outside the test scheduler: poll in real time. */
    private suspend fun eventually(condition: suspend () -> Boolean) = withContext(Dispatchers.Default) {
        withTimeout(5_000) { while (!condition()) delay(20) }
    }

    private fun TestScope.repo(catalog: CatalogRepository = FakeCatalog(null)): SubscriptionRepository {
        val clock = Clock { now }
        val feeds = FeedRepository(db, FeedFetcher(NetworkModule.okHttpClient(), RssParser(), Dispatchers.Unconfined), EpisodeMerger(), clock)
        return SubscriptionRepository(db, feeds, catalog, { downloads }, clock, TestScope(UnconfinedTestDispatcher(testScheduler)))
    }

    @Test fun openPreviewStoresUnsubscribedPodcastWithEpisodes() = runTest {
        server.enqueue(MockResponse().setBody(feedXml))
        val url = server.url("/feed").toString()
        val r = repo().open(url, CatalogPodcast(feedUrl = url, title = "Hint Title")) as SubscriptionRepository.OpenResult.Ok

        val p = db.podcastDao().getById(r.podcastId)!!
        assertThat(p.isSubscribed).isFalse()
        assertThat(p.title).isEqualTo("Real Title")
        assertThat(db.episodeDao().getForPodcast(p.id)).hasSize(1)
    }

    @Test fun openingAgainDoesNotRefetch() = runTest {
        server.enqueue(MockResponse().setBody(feedXml))
        val url = server.url("/feed").toString()
        val repo = repo()
        val first = repo.open(url) as SubscriptionRepository.OpenResult.Ok
        val second = repo.open(url) as SubscriptionRepository.OpenResult.Ok
        assertThat(second.podcastId).isEqualTo(first.podcastId)
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test fun addingCredentialsToAKnownFeedStoresThemAndRefetches() = runTest {
        server.enqueue(MockResponse().setBody(feedXml))
        server.enqueue(MockResponse().setBody(feedXml))
        val url = server.url("/feed").toString()
        val repo = repo()
        val id = (repo.open(url) as SubscriptionRepository.OpenResult.Ok).podcastId
        server.takeRequest()

        repo.open(url, authUser = "me", authPassword = "pw")

        val p = db.podcastDao().getById(id)!!
        assertThat(p.authUser).isEqualTo("me")
        assertThat(p.authPassword).isEqualTo("pw")
        assertThat(server.takeRequest().getHeader("Authorization")).isNotNull()
    }

    @Test fun failedPreviewLeavesNoPlaceholder() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        val r = repo().open(server.url("/missing").toString())
        assertThat(r).isInstanceOf(SubscriptionRepository.OpenResult.Failed::class.java)
        assertThat(db.podcastDao().getByFeedUrl(server.url("/missing").toString())).isNull()
    }

    @Test fun subscribeSetsFlagQueuesBackfillAndLooksUpIndexOnce() = runTest {
        server.enqueue(MockResponse().setBody(feedXml))
        server.enqueue(MockResponse().setResponseCode(304))
        val url = server.url("/feed").toString()
        val catalog = FakeCatalog(CatalogPodcast(feedUrl = url, title = "X", podcastIndexId = 42, episodeCount = 1200))
        val repo = repo(catalog)
        val id = (repo.open(url) as SubscriptionRepository.OpenResult.Ok).podcastId

        repo.subscribe(id)
        repo.subscribe(id) // second call is a no-op
        eventually { catalog.lookups == 1 && db.podcastDao().getById(id)!!.podcastIndexId != null }

        val p = db.podcastDao().getById(id)!!
        assertThat(p.isSubscribed).isTrue()
        assertThat(p.subscribedAt).isEqualTo(now)
        assertThat(p.podcastIndexId).isEqualTo(42)
        assertThat(p.podcastIndexEpisodeCount).isEqualTo(1200)
        assertThat(db.backfillStateDao().get(id)!!.status).isEqualTo(BackfillStatus.PENDING)
        assertThat(catalog.lookups).isEqualTo(1)
    }

    @Test fun unsubscribeKeepsEpisodes() = runTest {
        server.enqueue(MockResponse().setBody(feedXml))
        server.enqueue(MockResponse().setResponseCode(304))
        val repo = repo()
        val id = (repo.open(server.url("/feed").toString()) as SubscriptionRepository.OpenResult.Ok).podcastId
        repo.subscribe(id)
        eventually { server.requestCount == 2 }
        repo.unsubscribe(id, deleteDownloads = false)
        assertThat(db.podcastDao().getById(id)!!.isSubscribed).isFalse()
        assertThat(db.episodeDao().getForPodcast(id)).hasSize(1)
    }
}
