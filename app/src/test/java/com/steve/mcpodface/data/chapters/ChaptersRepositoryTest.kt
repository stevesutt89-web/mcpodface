package com.steve.mcpodface.data.chapters

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

class ChaptersRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()
    private val server = MockWebServer()

    @Before fun start() {
        server.start()
    }

    @After fun stop() {
        server.shutdown()
    }

    private val chaptersJson = """{"chapters":[{"startTime":0,"title":"Intro"},{"startTime":30,"title":"Main"}]}"""

    private fun repository(cache: String = "chapters") = ChaptersRepository(
        client = OkHttpClient(),
        io = Dispatchers.Unconfined,
        cacheDirectory = tmp.root.resolve(cache),
    )

    private fun urlFor(path: String) = server.url(path).toString()

    @Test fun fetchesOnceThenServesFromMemory() = runBlocking {
        server.enqueue(MockResponse().setBody(chaptersJson))
        val repo = repository()
        val url = urlFor("/c.json")

        assertThat(repo.load(url).map { it.title }).containsExactly("Intro", "Main").inOrder()
        assertThat(repo.load(url).map { it.title }).containsExactly("Intro", "Main").inOrder()

        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test fun diskCacheServesAfterRestartWithoutNetwork() = runBlocking {
        server.enqueue(MockResponse().setBody(chaptersJson))
        val url = urlFor("/c.json")
        repository().load(url)

        // A new repository (as after the app restarts) with the server now down reads the disk copy.
        server.shutdown()
        val chapters = repository().load(url)

        assertThat(chapters.map { it.startMs }).containsExactly(0L, 30_000L).inOrder()
    }

    @Test fun serverErrorGivesEmptyAndIsNotCached() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setBody(chaptersJson))
        val repo = repository()
        val url = urlFor("/flaky.json")

        assertThat(repo.load(url)).isEmpty()
        assertThat(repo.load(url).map { it.title }).containsExactly("Intro", "Main").inOrder()
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test fun pageThatIsNotAChaptersDocumentIsNotCached() = runBlocking {
        server.enqueue(MockResponse().setBody("<html>Maintenance</html>"))
        server.enqueue(MockResponse().setBody(chaptersJson))
        val repo = repository()
        val url = urlFor("/page.json")

        assertThat(repo.load(url)).isEmpty()
        assertThat(repo.load(url)).hasSize(2)
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test fun badUrlGivesEmptyList() = runBlocking {
        assertThat(repository().load("not a url")).isEmpty()
    }
}
