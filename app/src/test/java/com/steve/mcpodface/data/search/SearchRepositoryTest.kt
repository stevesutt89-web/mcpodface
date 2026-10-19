package com.steve.mcpodface.data.search

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
import com.steve.mcpodface.data.db.PlayState
import com.steve.mcpodface.data.db.PodcastEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SearchRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: SearchRepository
    private val day = 86_400_000L
    private val now = 20_000 * day
    private var show = 0L
    private var other = 0L

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = SearchRepository(db, Clock { now }, Dispatchers.Unconfined)
        runBlocking {
            show = db.podcastDao().insert(PodcastEntity(feedUrl = "https://a/feed", title = "Space Show", isSubscribed = true))
            other = db.podcastDao().insert(PodcastEntity(feedUrl = "https://b/feed", title = "Other", isSubscribed = true))
        }
    }

    @After fun tearDown() = db.close()

    private suspend fun episode(
        title: String,
        notes: String = "",
        podcast: Long = show,
        daysAgo: Int = 1,
        state: PlayState = PlayState.UNPLAYED,
        starred: Boolean = false,
        durationSec: Int? = 1800,
    ): Long = db.episodeDao().insertAll(
        listOf(
            EpisodeEntity(
                podcastId = podcast, guid = title + podcast, title = title, descriptionText = notes,
                pubDate = now - daysAgo * day, enclosureUrl = "https://cdn/$title.mp3", firstSeenAt = 0,
                playState = state, isStarred = starred, durationSec = durationSec,
            ),
        ),
    ).single()

    private fun search(text: String, filters: SearchFilters = SearchFilters(), sort: SearchSort = SearchSort.RELEVANCE) =
        runBlocking { withTimeout(5_000) { repo.search(text, filters, sort).first() } }

    private fun titles(r: SearchResults) = r.items.map { it.item.episode.title }

    @Test fun titleMatchOutranksShowNotesMatch() = runBlocking<Unit> {
        episode("Weather report", notes = "rockets rockets rockets and more rockets", daysAgo = 1)
        episode("Rockets explained", notes = "a history", daysAgo = 30)
        assertThat(titles(search("rocket"))).containsExactly("Rockets explained", "Weather report").inOrder()
    }

    @Test fun recencyBreaksTies() = runBlocking<Unit> {
        episode("Mars one", daysAgo = 10)
        episode("Mars two", daysAgo = 2)
        assertThat(titles(search("mars"))).containsExactly("Mars two", "Mars one").inOrder()
    }

    @Test fun snippetHighlightsTheMatch() = runBlocking<Unit> {
        episode("Episode 1", notes = "We talk about the James Webb telescope and its mirrors")
        val snippet = search("telescope").items.single().snippet!!
        assertThat(snippet).contains("<b>telescope</b>")
    }

    @Test fun titleOnlyIgnoresShowNotes() = runBlocking<Unit> {
        episode("Moon landing", notes = "nothing")
        episode("Something else", notes = "the moon is mentioned here")
        assertThat(titles(search("moon", SearchFilters(titleOnly = true)))).containsExactly("Moon landing")
    }

    @Test fun phraseExclusionRemovesEpisodes() = runBlocking<Unit> {
        episode("Climate one", notes = "climate and politics in the senate")
        episode("Climate two", notes = "climate science only")
        assertThat(titles(search("climate -\"the senate\""))).containsExactly("Climate two")
        assertThat(titles(search("climate -politics"))).containsExactly("Climate two")
    }

    @Test fun diacriticsAreIgnored() = runBlocking<Unit> {
        episode("Café culture")
        assertThat(titles(search("cafe"))).containsExactly("Café culture")
    }

    @Test fun filtersApply() = runBlocking<Unit> {
        val played = episode("Probe played", state = PlayState.PLAYED, durationSec = 600)
        episode("Probe new", daysAgo = 400, starred = true, durationSec = 6000)
        episode("Probe elsewhere", podcast = other)
        db.downloadDao().upsert(DownloadEntity(played, DownloadState.COMPLETED, DownloadSource.MANUAL, filePath = "/x"))

        assertThat(titles(search("probe", SearchFilters(status = StatusFilter.PLAYED)))).containsExactly("Probe played")
        assertThat(titles(search("probe", SearchFilters(downloadedOnly = true)))).containsExactly("Probe played")
        assertThat(titles(search("probe", SearchFilters(starredOnly = true)))).containsExactly("Probe new")
        assertThat(titles(search("probe", SearchFilters(duration = DurationFilter.UNDER_15)))).containsExactly("Probe played")
        assertThat(titles(search("probe", SearchFilters(duration = DurationFilter.OVER_90)))).containsExactly("Probe new")
        assertThat(titles(search("probe", SearchFilters(publishedFrom = now - 365 * day)))).containsExactly("Probe played", "Probe elsewhere")
        assertThat(titles(search("probe", SearchFilters(podcastIds = setOf(other))))).containsExactly("Probe elsewhere")
    }

    @Test fun unsubscribedPodcastsAreLeftOutUnlessScopedToThem() = runBlocking<Unit> {
        val gone = db.podcastDao().insert(PodcastEntity(feedUrl = "https://c/feed", title = "Gone", isSubscribed = false))
        episode("Comet", podcast = gone)
        assertThat(search("comet").items).isEmpty()
        assertThat(titles(search("comet", SearchFilters(podcastIds = setOf(gone))))).containsExactly("Comet")
    }

    @Test fun blankTextListsFilteredEpisodesButNotEverything() = runBlocking<Unit> {
        episode("A", starred = true, daysAgo = 3)
        episode("B", starred = true, daysAgo = 1)
        episode("C")
        assertThat(search("").items).isEmpty()
        assertThat(titles(search("", SearchFilters(starredOnly = true)))).containsExactly("B", "A").inOrder()
    }

    @Test fun sortByOldest() = runBlocking<Unit> {
        episode("Saturn new", daysAgo = 1)
        episode("Saturn old", daysAgo = 50)
        assertThat(titles(search("saturn", sort = SearchSort.OLDEST))).containsExactly("Saturn old", "Saturn new").inOrder()
    }

    @Test fun hostileInputNeverThrows() = runBlocking<Unit> {
        episode("Normal")
        for (q in listOf("\"", "*", "NEAR/3 x", "title:x", "AND", "(((", "'; DROP TABLE episodes; --", "-\"", "a OR", "🙂")) {
            search(q)
        }
        assertThat(search("normal").items).hasSize(1)
    }

    @Test fun resultsUpdateWhenEpisodesArrive() = runBlocking<Unit> {
        episode("Jupiter one")
        val emissions = mutableListOf<Int>()
        val job = launch(Dispatchers.Default) {
            repo.search("jupiter").take(2).toList().mapTo(emissions) { it.totalCount }
        }
        kotlinx.coroutines.delay(300)
        episode("Jupiter two")
        withTimeout(5_000) { job.join() }
        assertThat(emissions).containsExactly(1, 2).inOrder()
    }

    @Test fun recentSearchesKeepTheLatestTen() = runBlocking<Unit> {
        var t = now
        val repo = SearchRepository(db, Clock { t++ }, Dispatchers.Unconfined)
        repeat(12) { repo.recordSearch("q$it") }
        val recent = repo.recentSearches().first()
        assertThat(recent).hasSize(10)
        assertThat(recent.map { it.query }).doesNotContain("q0")
    }

    @Test fun benchmark100kEpisodes() = runBlocking<Unit> {
        val words = listOf("history", "science", "politics", "music", "comedy", "news", "space", "health", "sport", "food")
        db.runInTransaction {
            runBlocking {
                (0 until 100_000).chunked(5_000).forEach { chunk ->
                    db.episodeDao().insertAll(
                        chunk.map { i ->
                            EpisodeEntity(
                                podcastId = if (i % 2 == 0) show else other, guid = "g$i",
                                title = "Episode $i about ${words[i % 10]}",
                                descriptionText = "A long discussion of ${words[(i / 7) % 10]} and ${words[(i / 3) % 10]} number $i",
                                pubDate = now - i * 60_000L, enclosureUrl = "https://cdn/$i.mp3", firstSeenAt = 0,
                            )
                        },
                    )
                }
            }
        }
        search("space") // warm up
        val started = System.nanoTime()
        val r = search("space")
        val ms = (System.nanoTime() - started) / 1_000_000
        println("Search over 100k episodes: $ms ms, ${r.totalCount} hits")
        assertThat(r.items).isNotEmpty()
        // The 150ms target is for the phone; the Robolectric JVM SQLite is slower, so only guard against regressions here.
        assertThat(ms).isLessThan(3_000)
    }
}
