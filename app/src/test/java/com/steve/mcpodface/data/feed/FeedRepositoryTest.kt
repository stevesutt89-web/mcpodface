package com.steve.mcpodface.data.feed

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.steve.mcpodface.core.Clock
import com.steve.mcpodface.data.db.AppDatabase
import com.steve.mcpodface.data.db.EpisodeOrigin
import com.steve.mcpodface.data.db.PlayState
import com.steve.mcpodface.data.db.PodcastEntity
import com.steve.mcpodface.data.network.NetworkModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import okhttp3.Credentials
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** FeedFetcher + FeedRepository against MockWebServer and an in-memory Room DB (task 1.5). */
@RunWith(AndroidJUnit4::class)
class FeedRepositoryTest {
    private val server = MockWebServer()
    private lateinit var db: AppDatabase
    private lateinit var repo: FeedRepository
    private var now = ZonedDateTime.of(2026, 10, 19, 12, 0, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()
    private val day = 86_400_000L

    @Before fun setUp() {
        server.start()
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        val fetcher = FeedFetcher(NetworkModule.okHttpClient(), RssParser(), Dispatchers.Unconfined)
        repo = FeedRepository(db, fetcher, EpisodeMerger(), Clock { now })
    }

    @After fun tearDown() {
        db.close()
        server.shutdown()
    }

    private fun rfc822(millis: Long): String =
        DateTimeFormatter.RFC_1123_DATE_TIME.format(java.time.Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC))

    /** item = guid to pubDate millis */
    private fun feed(vararg items: Pair<String, Long>, title: String = "Test Show") = """
        <?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd">
          <channel>
            <title>$title</title>
            <itunes:author>Host</itunes:author>
            <description>About the show</description>
            <itunes:image href="https://img.example.com/show.jpg"/>
            ${items.joinToString("\n") { (guid, date) -> """
            <item>
              <guid>$guid</guid>
              <title>Episode $guid</title>
              <description><![CDATA[<p>Notes for $guid</p>]]></description>
              <pubDate>${rfc822(date)}</pubDate>
              <enclosure url="https://cdn.example.com/$guid.mp3" type="audio/mpeg" length="1000"/>
              <itunes:duration>30:00</itunes:duration>
            </item>""" }}
          </channel>
        </rss>
    """.trimIndent()

    private suspend fun addPodcast(path: String = "/feed", user: String? = null, pass: String? = null): Long =
        db.podcastDao().insert(
            PodcastEntity(feedUrl = server.url(path).toString(), title = "Placeholder", isSubscribed = true,
                authUser = user, authPassword = pass),
        )

    @Test fun firstRefreshInsertsEpisodesUpdatesPodcastAndNotifiesNothing() = runTest {
        server.enqueue(MockResponse().setBody(feed("a" to now - 2 * day, "b" to now - day)).setHeader("ETag", "\"v1\""))
        val id = addPodcast()

        val r = repo.refresh(id) as RefreshResult.Updated
        assertThat(r.insertedCount).isEqualTo(2)
        assertThat(r.newEpisodeIds).isEmpty()

        val p = db.podcastDao().getById(id)!!
        assertThat(p.title).isEqualTo("Test Show")
        assertThat(p.author).isEqualTo("Host")
        assertThat(p.artworkUrl).isEqualTo("https://img.example.com/show.jpg")
        assertThat(p.httpEtag).isEqualTo("\"v1\"")
        assertThat(p.lastRefreshAt).isEqualTo(now)
        assertThat(p.lastNewEpisodeAt).isEqualTo(now - day)
        val e = db.episodeDao().getForPodcast(id).first { it.guid == "b" }
        assertThat(e.descriptionText).isEqualTo("Notes for b")
        assertThat(e.durationSec).isEqualTo(1800)
    }

    @Test fun conditionalGetSendsValidatorsAndHandles304() = runTest {
        server.enqueue(MockResponse().setBody(feed("a" to now - day)).setHeader("ETag", "\"v1\"").setHeader("Last-Modified", "Mon, 19 Oct 2026 10:00:00 GMT"))
        server.enqueue(MockResponse().setResponseCode(304))
        val id = addPodcast()
        repo.refresh(id)
        server.takeRequest()

        assertThat(repo.refresh(id)).isEqualTo(RefreshResult.NotModified(id))
        val second = server.takeRequest()
        assertThat(second.getHeader("If-None-Match")).isEqualTo("\"v1\"")
        assertThat(second.getHeader("If-Modified-Since")).isEqualTo("Mon, 19 Oct 2026 10:00:00 GMT")
        assertThat(second.getHeader("User-Agent")).startsWith("McPodface/")
    }

    @Test fun forceRefreshOmitsValidators() = runTest {
        server.enqueue(MockResponse().setBody(feed("a" to now - day)).setHeader("ETag", "\"v1\""))
        server.enqueue(MockResponse().setBody(feed("a" to now - day)))
        val id = addPodcast()
        repo.refresh(id)
        server.takeRequest()
        repo.refresh(id, force = true)
        assertThat(server.takeRequest().getHeader("If-None-Match")).isNull()
    }

    @Test fun laterRefreshReportsOnlyGenuinelyNewEpisodes() = runTest {
        server.enqueue(MockResponse().setBody(feed("a" to now - 2 * day)))
        val id = addPodcast()
        repo.refresh(id)
        val firstRefresh = now

        now += 4 * 3_600_000L
        // "c" is new and published after the last refresh; "old" is newly seen but published long ago.
        server.enqueue(MockResponse().setBody(feed("a" to now - 2 * day, "old" to now - 300 * day, "c" to firstRefresh + 60_000)))
        val r = repo.refresh(id) as RefreshResult.Updated
        val c = db.episodeDao().getForPodcast(id).first { it.guid == "c" }
        assertThat(r.insertedCount).isEqualTo(2)
        assertThat(r.newEpisodeIds).containsExactly(c.id)
    }

    @Test fun episodesDroppedFromFeedAreKeptAsArchivedWithUserState() = runTest {
        server.enqueue(MockResponse().setBody(feed("a" to now - 2 * day, "b" to now - day)))
        val id = addPodcast()
        repo.refresh(id)
        val a = db.episodeDao().getForPodcast(id).first { it.guid == "a" }
        db.episodeDao().updatePosition(a.id, 60_000, PlayState.IN_PROGRESS, now)

        server.enqueue(MockResponse().setBody(feed("b" to now - day)))
        repo.refresh(id)

        val kept = db.episodeDao().getById(a.id)!!
        assertThat(kept.inCurrentFeed).isFalse()
        assertThat(kept.positionMs).isEqualTo(60_000)
        assertThat(kept.playState).isEqualTo(PlayState.IN_PROGRESS)
        assertThat(db.episodeDao().getForPodcast(id)).hasSize(2)
    }

    @Test fun refreshNeverResetsPlayState() = runTest {
        server.enqueue(MockResponse().setBody(feed("a" to now - day)))
        val id = addPodcast()
        repo.refresh(id)
        val a = db.episodeDao().getForPodcast(id).single()
        db.episodeDao().updatePosition(a.id, 1_799_000, PlayState.PLAYED, now)

        server.enqueue(MockResponse().setBody(feed("a" to now - day, title = "Renamed Show")))
        repo.refresh(id)
        assertThat(db.episodeDao().getById(a.id)!!.playState).isEqualTo(PlayState.PLAYED)
    }

    @Test fun permanentRedirectUpdatesFeedUrl() = runTest {
        server.enqueue(MockResponse().setResponseCode(301).setHeader("Location", server.url("/new-feed")))
        server.enqueue(MockResponse().setBody(feed("a" to now - day)))
        val id = addPodcast("/old-feed")
        repo.refresh(id)
        assertThat(db.podcastDao().getById(id)!!.feedUrl).isEqualTo(server.url("/new-feed").toString())
    }

    @Test fun temporaryRedirectKeepsFeedUrl() = runTest {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/elsewhere")))
        server.enqueue(MockResponse().setBody(feed("a" to now - day)))
        val id = addPodcast("/feed")
        repo.refresh(id)
        assertThat(db.podcastDao().getById(id)!!.feedUrl).isEqualTo(server.url("/feed").toString())
    }

    @Test fun goneFlagsThePodcast() = runTest {
        server.enqueue(MockResponse().setResponseCode(410))
        val id = addPodcast()
        assertThat(repo.refresh(id)).isEqualTo(RefreshResult.Gone(id))
        assertThat(db.podcastDao().getById(id)!!.feedGone).isTrue()
    }

    @Test fun httpErrorAndGarbageFailWithoutTouchingData() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setBody("<html><body>Not a feed</body></html>"))
        val id = addPodcast()
        assertThat(repo.refresh(id)).isInstanceOf(RefreshResult.Failed::class.java)
        assertThat(repo.refresh(id)).isInstanceOf(RefreshResult.Failed::class.java)
        assertThat(db.podcastDao().getById(id)!!.lastRefreshAt).isNull()
    }

    @Test fun privateFeedSendsBasicAuth() = runTest {
        server.enqueue(MockResponse().setBody(feed("a" to now - day)))
        val id = addPodcast(user = "me", pass = "secret")
        repo.refresh(id)
        assertThat(server.takeRequest().getHeader("Authorization")).isEqualTo(Credentials.basic("me", "secret"))
    }

    @Test fun backfillAddsArchivedEpisodesWithoutDuplicates() = runTest {
        server.enqueue(MockResponse().setBody(feed("a" to now - day)))
        val id = addPodcast()
        repo.refresh(id)
        val page = listOf(
            ParsedEpisode("a", "Episode a", null, now - day, null, "https://cdn.example.com/a.mp3", null, null, null, null, null, null, null),
            ParsedEpisode("z", "Episode z", null, now - 900 * day, null, "https://cdn.example.com/z.mp3", null, null, null, null, null, null, null),
        )
        assertThat(repo.mergeBackfill(id, page, EpisodeOrigin.FEED_PAGED)).isEqualTo(1)
        assertThat(repo.mergeBackfill(id, page, EpisodeOrigin.FEED_PAGED)).isEqualTo(0)
        val z = db.episodeDao().getForPodcast(id).first { it.guid == "z" }
        assertThat(z.inCurrentFeed).isFalse()
        assertThat(z.origin).isEqualTo(EpisodeOrigin.FEED_PAGED)
    }
}
