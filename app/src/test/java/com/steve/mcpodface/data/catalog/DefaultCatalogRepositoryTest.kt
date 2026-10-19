package com.steve.mcpodface.data.catalog

import com.google.common.truth.Truth.assertThat
import com.steve.mcpodface.data.network.ApiPurpose
import com.steve.mcpodface.data.network.ListenNotesKey
import com.steve.mcpodface.data.network.PodcastIndexCredentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.Instant

private val CREDS = PodcastIndexCredentials("key", "secret")
private val NOW = Instant.parse("2026-10-19T12:00:00Z").toEpochMilli()
private const val DAY_MS = 24L * 60 * 60 * 1000

private const val INDEX_SEARCH_BODY = """{
  "status": "true",
  "feeds": [
    {"id": 75075, "title": "Radiolab", "url": "https://feeds.example.com/radiolab.xml",
     "author": "WNYC", "image": "https://img.example.com/image.jpg",
     "artwork": "https://img.example.com/artwork.jpg", "description": "Science show",
     "itunesId": 152249110, "episodeCount": 300,
     "categories": {"104": "Tech", "105": "Science"}},
    {"id": 2, "title": "Image Only", "url": "https://feeds.example.com/image-only.xml",
     "image": "https://img.example.com/only.jpg", "categories": []},
    {"id": 3, "title": "No Feed URL", "url": "", "categories": {}}
  ]
}"""

private const val ITUNES_BODY = """{
  "resultCount": 2,
  "results": [
    {"wrapperType": "track", "kind": "podcast", "collectionId": 1200361736,
     "collectionName": "Radiolab", "artistName": "WNYC Studios",
     "feedUrl": "https://feeds.example.com/itunes-radiolab.xml",
     "artworkUrl600": "https://is.example.com/600.jpg", "artworkUrl100": "https://is.example.com/100.jpg",
     "genres": ["News", "Science"]},
    {"collectionId": 99, "collectionName": "No Feed"}
  ]
}"""

private const val EMPTY_INDEX = """{"status": "true", "feeds": []}"""

class DefaultCatalogRepositoryTest {
    private val index = MockWebServer()
    private val itunes = MockWebServer()
    private val listen = MockWebServer()
    private val limiter = FakeRateLimiter()
    private val dao = FakeCatalogCacheDao()
    private val clock = FakeClock(NOW)

    @Before fun startServers() {
        index.start()
        itunes.start()
        listen.start()
    }

    @After fun stopServers() {
        index.shutdown()
        itunes.shutdown()
        listen.shutdown()
    }

    private fun repo(
        credentials: PodcastIndexCredentials = CREDS,
        listenNotesKey: ListenNotesKey = ListenNotesKey(""),
    ) = DefaultCatalogRepository(
        podcastIndexApi = testPodcastIndexApi(index, credentials, clock),
        iTunesApi = testITunesApi(itunes),
        listenNotesApi = testListenNotesApi(listen, listenNotesKey),
        listenNotesKey = listenNotesKey,
        credentials = credentials,
        rateLimiter = limiter,
        cacheDao = dao,
        clock = clock,
        ioDispatcher = Dispatchers.Unconfined,
    )

    // ---- search: Index parsing ----

    @Test fun searchParsesIndexResultsAndSendsQueryAndAuth() = runTest {
        index.respond(INDEX_SEARCH_BODY)

        val results = repo().search("  Radiolab ")

        val radiolab = results.first { it.feedUrl == "https://feeds.example.com/radiolab.xml" }
        assertThat(radiolab.title).isEqualTo("Radiolab")
        assertThat(radiolab.author).isEqualTo("WNYC")
        assertThat(radiolab.artworkUrl).isEqualTo("https://img.example.com/artwork.jpg")
        assertThat(radiolab.description).isEqualTo("Science show")
        assertThat(radiolab.podcastIndexId).isEqualTo(75075L)
        assertThat(radiolab.itunesId).isEqualTo(152249110L)
        assertThat(radiolab.episodeCount).isEqualTo(300)
        assertThat(radiolab.categories).containsExactly("Tech", "Science").inOrder()

        val request = index.takeRequest()
        assertThat(request.path).startsWith("/api/1.0/search/byterm")
        assertThat(request.requestUrl!!.queryParameter("q")).isEqualTo("Radiolab")
        assertThat(request.requestUrl!!.queryParameter("max")).isEqualTo("40")
        assertThat(request.headers["X-Auth-Key"]).isEqualTo("key")
        assertThat(itunes.requestCount).isEqualTo(0)
    }

    @Test fun artworkFallsBackToImageAndEmptyCategoryArrayIsTolerated() = runTest {
        index.respond(INDEX_SEARCH_BODY)

        val imageOnly = repo().search("radiolab").first { it.title == "Image Only" }

        assertThat(imageOnly.artworkUrl).isEqualTo("https://img.example.com/only.jpg")
        assertThat(imageOnly.categories).isEmpty()
        assertThat(imageOnly.podcastIndexId).isEqualTo(2L)
    }

    @Test fun indexResultsWithoutFeedUrlAreSkipped() = runTest {
        index.respond(INDEX_SEARCH_BODY)

        val results = repo().search("radiolab")

        assertThat(results.map { it.title }).containsExactly("Radiolab", "Image Only").inOrder()
    }

    // ---- search: fallback ----

    @Test fun emptyIndexFallsBackToItunes() = runTest {
        index.respond(EMPTY_INDEX)
        itunes.respond(ITUNES_BODY)

        val results = repo().search("radiolab")

        assertThat(results).containsExactly(
            CatalogPodcast(
                feedUrl = "https://feeds.example.com/itunes-radiolab.xml",
                title = "Radiolab",
                author = "WNYC Studios",
                artworkUrl = "https://is.example.com/600.jpg",
                itunesId = 1200361736L,
                categories = listOf("News", "Science"),
            ),
        )
        assertThat(index.requestCount).isEqualTo(1)
        assertThat(itunes.requestCount).isEqualTo(1)
    }

    @Test fun iTunesRequestCarriesTermAndSkipsEntriesWithoutFeedUrl() = runTest {
        index.respond(EMPTY_INDEX)
        itunes.respond(ITUNES_BODY)

        repo().search("radiolab")

        val request = itunes.takeRequest()
        assertThat(request.path).startsWith("/search?media=podcast&entity=podcast")
        assertThat(request.requestUrl!!.queryParameter("term")).isEqualTo("radiolab")
    }

    @Test fun indexServerErrorFallsBackToItunes() = runTest {
        index.respond("oops", 500)
        itunes.respond(ITUNES_BODY)

        val results = repo().search("radiolab")

        assertThat(results.map { it.itunesId }).containsExactly(1200361736L)
        assertThat(limiter.rateLimitedCount).isEqualTo(0)
    }

    @Test fun indexRateLimitReportsToLimiterAndFallsBackToItunes() = runTest {
        index.respond("slow down", 429)
        itunes.respond(ITUNES_BODY)

        val results = repo().search("radiolab")

        assertThat(limiter.rateLimitedCount).isEqualTo(1)
        assertThat(results.map { it.itunesId }).containsExactly(1200361736L)
    }

    @Test fun blankCredentialsSkipIndexEntirely() = runTest {
        itunes.respond(ITUNES_BODY)

        val results = repo(PodcastIndexCredentials("", "")).search("radiolab")

        assertThat(results).hasSize(1)
        assertThat(index.requestCount).isEqualTo(0)
        assertThat(limiter.purposes).isEmpty()
    }

    @Test fun limiterDenialSkipsIndexAndFallsBackToItunes() = runTest {
        limiter.allow = false
        itunes.respond(ITUNES_BODY)

        val results = repo().search("radiolab")

        assertThat(results).hasSize(1)
        assertThat(index.requestCount).isEqualTo(0)
        assertThat(limiter.purposes).containsExactly(ApiPurpose.DISCOVERY)
    }

    @Test fun bothSourcesFailingThrowsCatalogException() = runTest {
        index.respond("oops", 500)
        itunes.respond("oops", 500)

        val error = runCatching { repo().search("radiolab") }.exceptionOrNull()

        assertThat(error).isInstanceOf(CatalogException::class.java)
    }

    @Test fun indexEmptyAndItunesFailingIsEmptyNotAnError() = runTest {
        index.respond(EMPTY_INDEX)
        itunes.respond("oops", 500)

        val results = repo().search("radiolab")

        assertThat(results).isEmpty()
    }

    @Test fun blankSearchTermReturnsEmptyWithoutRequests() = runTest {
        val results = repo().search("   ")

        assertThat(results).isEmpty()
        assertThat(index.requestCount + itunes.requestCount).isEqualTo(0)
    }

    @Test fun iTunesEntriesWithoutFeedUrlAreSkipped() = runTest {
        index.respond(EMPTY_INDEX)
        itunes.respond(ITUNES_BODY)

        val results = repo().search("radiolab")

        assertThat(results.map { it.title }).containsExactly("Radiolab")
    }

    // ---- search: cache ----

    @Test fun cacheHitWithin24hMakesNoNetworkRequest() = runTest {
        index.respond(INDEX_SEARCH_BODY)
        val repo = repo()
        val first = repo.search("radiolab")

        clock.millis += DAY_MS - 1
        val second = repo.search("radiolab")

        assertThat(second).isEqualTo(first)
        assertThat(index.requestCount).isEqualTo(1)
        assertThat(itunes.requestCount).isEqualTo(0)
    }

    @Test fun cacheKeyIsTrimmedAndCaseInsensitive() = runTest {
        index.respond(INDEX_SEARCH_BODY)
        val repo = repo()
        repo.search("  Radiolab ")

        repo.search("radiolab")

        assertThat(index.requestCount).isEqualTo(1)
    }

    @Test fun cacheOlderThan24hRefetches() = runTest {
        index.respond(INDEX_SEARCH_BODY)
        index.respond(INDEX_SEARCH_BODY)
        val repo = repo()
        repo.search("radiolab")

        clock.millis += DAY_MS
        repo.search("radiolab")

        assertThat(index.requestCount).isEqualTo(2)
    }

    @Test fun emptyResultsAreNotCached() = runTest {
        index.respond(EMPTY_INDEX)
        index.respond(EMPTY_INDEX)
        itunes.respond("""{"results": []}""")
        itunes.respond("""{"results": []}""")
        val repo = repo()

        repo.search("nothing")
        repo.search("nothing")

        assertThat(index.requestCount).isEqualTo(2)
        assertThat(itunes.requestCount).isEqualTo(2)
    }

    // ---- trending ----

    @Test fun trendingParsesIndexFeedsWithCategoryAndLanguage() = runTest {
        index.respond(
            """{"feeds": [
              {"id": 1, "title": "Tech Show", "url": "https://t.example/feed.xml", "image": "https://t.example/i.jpg"},
              {"id": 2, "title": "No URL"}
            ]}""",
        )

        val results = repo().trending("Technology")

        assertThat(results.map { it.feedUrl }).containsExactly("https://t.example/feed.xml")
        assertThat(results.single().artworkUrl).isEqualTo("https://t.example/i.jpg")
        val request = index.takeRequest()
        assertThat(request.path).startsWith("/api/1.0/podcasts/trending")
        assertThat(request.requestUrl!!.queryParameter("cat")).isEqualTo("Technology")
        assertThat(request.requestUrl!!.queryParameter("lang")).isEqualTo("en")
        assertThat(request.requestUrl!!.queryParameter("max")).isEqualTo("40")
    }

    @Test fun trendingFailureReturnsEmptyAndIsNotCached() = runTest {
        index.respond("oops", 500)
        index.respond("""{"feeds": [{"id": 1, "title": "Show", "url": "https://s.example/feed.xml"}]}""")
        val repo = repo()

        assertThat(repo.trending()).isEmpty()
        assertThat(repo.trending().map { it.feedUrl }).containsExactly("https://s.example/feed.xml")
    }

    @Test fun trendingWithoutCredentialsOrLimiterReturnsEmpty() = runTest {
        limiter.allow = false

        assertThat(repo().trending()).isEmpty()
        assertThat(index.requestCount).isEqualTo(0)
    }

    // ---- categories ----

    @Test fun categoriesParsesSkipsBlankAndIsCached() = runTest {
        index.respond("""{"feeds": [{"id": 1, "name": "Arts"}, {"id": 2, "name": " "}, {"id": 3, "name": "Tech"}]}""")
        val repo = repo()

        val first = repo.categories()
        val second = repo.categories()

        assertThat(first).containsExactly(CatalogCategory(1, "Arts"), CatalogCategory(3, "Tech")).inOrder()
        assertThat(second).isEqualTo(first)
        assertThat(index.requestCount).isEqualTo(1)
    }

    @Test fun categoriesFailureReturnsEmpty() = runTest {
        index.respond("oops", 500)

        assertThat(repo().categories()).isEmpty()
    }

    // ---- lookupByFeedUrl ----

    @Test fun lookupFindsFeedAndUsesSubscribePurpose() = runTest {
        index.respond(
            """{"status": "true", "feed": {"id": 75075, "title": "Radiolab",
               "url": "https://feeds.example.com/radiolab.xml", "episodeCount": 300,
               "itunesId": 152249110, "categories": {"104": "Tech"}}}""",
        )

        val found = repo().lookupByFeedUrl("https://feeds.example.com/radiolab.xml")

        assertThat(found).isEqualTo(
            CatalogPodcast(
                feedUrl = "https://feeds.example.com/radiolab.xml",
                title = "Radiolab",
                podcastIndexId = 75075L,
                itunesId = 152249110L,
                episodeCount = 300,
                categories = listOf("Tech"),
            ),
        )
        assertThat(limiter.purposes).containsExactly(ApiPurpose.SUBSCRIBE)
        val request = index.takeRequest()
        assertThat(request.path).startsWith("/api/1.0/podcasts/byfeedurl")
        assertThat(request.requestUrl!!.queryParameter("url")).isEqualTo("https://feeds.example.com/radiolab.xml")
    }

    @Test fun lookupWithEmptyOrMissingFeedReturnsNull() = runTest {
        index.respond("""{"status": "true", "feed": []}""")
        index.respond("""{"status": "false", "description": "not found"}""")
        val repo = repo()

        assertThat(repo.lookupByFeedUrl("https://nope.example/feed.xml")).isNull()
        assertThat(repo.lookupByFeedUrl("https://nope.example/feed.xml")).isNull()
    }

    @Test fun lookupFailureReturnsNullAndRateLimitIsReported() = runTest {
        index.respond("slow down", 429)
        index.respond("oops", 500)
        val repo = repo()

        assertThat(repo.lookupByFeedUrl("https://a.example/feed.xml")).isNull()
        assertThat(limiter.rateLimitedCount).isEqualTo(1)
        assertThat(repo.lookupByFeedUrl("https://a.example/feed.xml")).isNull()
        assertThat(limiter.rateLimitedCount).isEqualTo(1)
    }

    @Test fun lookupIsNotCached() = runTest {
        index.respond("""{"status": "true", "feed": []}""")
        index.respond("""{"status": "true", "feed": []}""")
        val repo = repo()

        repo.lookupByFeedUrl("https://a.example/feed.xml")
        repo.lookupByFeedUrl("https://a.example/feed.xml")

        assertThat(index.requestCount).isEqualTo(2)
    }

    // ---- Listen Notes ----

    private val lnKey = ListenNotesKey("ln-key")
    private val noIndex = PodcastIndexCredentials("", "")

    private val lnSearchBody = """{"results": [
      {"id": "a", "rss": "https://feeds.example.com/ln-radiolab.xml", "title_original": "Radiolab",
       "publisher_original": "WNYC", "image": "https://img/ln.jpg", "itunes_id": 152249110, "total_episodes": 300},
      {"id": "b", "rss": "Please upgrade to see this field", "title_original": "Hidden Feed", "itunes_id": 42},
      {"id": "c", "title_original": "No Feed Anywhere"}
    ]}"""

    @Test fun listenNotesUsedWhenIndexUnavailableAndSendsKey() = runTest {
        listen.respond(lnSearchBody)
        itunes.respond("""{"results": [{"collectionId": 42, "feedUrl": "https://feeds.example.com/hidden.xml"}]}""")

        val results = repo(credentials = noIndex, listenNotesKey = lnKey).search("radiolab")

        assertThat(results.map { it.feedUrl }).containsExactly(
            "https://feeds.example.com/ln-radiolab.xml",
            "https://feeds.example.com/hidden.xml",
        ).inOrder()
        assertThat(results[0].author).isEqualTo("WNYC")
        assertThat(results[0].episodeCount).isEqualTo(300)
        val req = listen.takeRequest()
        assertThat(req.getHeader("X-ListenAPI-Key")).isEqualTo("ln-key")
        assertThat(req.requestUrl!!.queryParameter("q")).isEqualTo("radiolab")
        assertThat(itunes.takeRequest().requestUrl!!.queryParameter("id")).isEqualTo("42")
    }

    @Test fun indexResultsWinOverListenNotes() = runTest {
        index.respond(INDEX_SEARCH_BODY)
        repo(listenNotesKey = lnKey).search("radiolab")
        assertThat(listen.requestCount).isEqualTo(0)
    }

    @Test fun listenNotesFailureFallsBackToITunes() = runTest {
        listen.respond("""{"error": "quota"}""", code = 429)
        itunes.respond(ITUNES_BODY)
        val results = repo(credentials = noIndex, listenNotesKey = lnKey).search("radiolab")
        assertThat(results.map { it.feedUrl }).containsExactly("https://feeds.example.com/itunes-radiolab.xml")
    }

    @Test fun blankListenNotesKeyMakesNoRequest() = runTest {
        itunes.respond(ITUNES_BODY)
        repo(credentials = noIndex).search("radiolab")
        assertThat(listen.requestCount).isEqualTo(0)
    }

    @Test fun listenNotesResultsAreCached() = runTest {
        listen.respond(lnSearchBody)
        itunes.respond("""{"results": []}""")
        val r = repo(credentials = noIndex, listenNotesKey = lnKey)
        r.search("radiolab")
        r.search("radiolab")
        assertThat(listen.requestCount).isEqualTo(1)
    }

    @Test fun trendingAndCategoriesFallBackToListenNotes() = runTest {
        listen.respond("""{"genres": [{"id": 68, "name": "TV & Film", "parent_id": 67}, {"id": 99, "name": "Arts", "parent_id": 67}]}""")
        val cats = repo(credentials = noIndex, listenNotesKey = lnKey).categories()
        assertThat(cats.map { it.name }).containsExactly("Arts", "TV & Film").inOrder()

        listen.respond("""{"podcasts": [{"id": "x", "rss": "https://feeds.example.com/best.xml", "title": "Best", "publisher": "Pub"}]}""")
        val trending = repo(credentials = noIndex, listenNotesKey = lnKey).trending(null)
        assertThat(trending.single().title).isEqualTo("Best")
        assertThat(trending.single().author).isEqualTo("Pub")
    }

    @Test fun listenNotesTrendingByCategoryUsesGenreId() = runTest {
        listen.respond("""{"genres": [{"id": 68, "name": "TV & Film"}]}""")
        listen.respond("""{"podcasts": [{"rss": "https://feeds.example.com/tv.xml", "title": "TV Show"}]}""")
        val r = repo(credentials = noIndex, listenNotesKey = lnKey).trending("TV & Film")
        assertThat(r.single().feedUrl).isEqualTo("https://feeds.example.com/tv.xml")
        listen.takeRequest()
        assertThat(listen.takeRequest().requestUrl!!.queryParameter("genre_id")).isEqualTo("68")
    }
}
