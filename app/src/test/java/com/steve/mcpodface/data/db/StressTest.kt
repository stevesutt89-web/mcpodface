package com.steve.mcpodface.data.db

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.steve.mcpodface.core.Clock
import com.steve.mcpodface.data.search.SearchRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Task 9.8: 500 podcasts and 200,000 episodes (PLAN.md §14). Times the queries behind the main
 * screens. The phone targets are tighter; on the Robolectric JVM these only guard against a query
 * turning into a full scan per row (a missing index), which would be seconds, not milliseconds.
 */
@RunWith(AndroidJUnit4::class)
class StressTest {
    private lateinit var db: AppDatabase
    private val day = 86_400_000L
    private val now = 20_000 * day
    private val timings = linkedMapOf<String, Long>()

    @Before fun setUp() = runBlocking<Unit> {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        val words = listOf("history", "science", "politics", "music", "comedy", "news", "space", "health", "sport", "food")
        db.withTransaction {
            for (p in 0 until PODCASTS) {
                val podcastId = db.podcastDao().insert(PodcastEntity(feedUrl = "https://p$p/feed", title = "Podcast $p", isSubscribed = true))
                db.episodeDao().insertAll(
                    (0 until EPISODES_PER_PODCAST).map { i ->
                        EpisodeEntity(
                            podcastId = podcastId, guid = "g$i", title = "Episode $i about ${words[(p + i) % 10]}",
                            descriptionText = "Notes on ${words[i % 10]} and ${words[(i / 3) % 10]}",
                            pubDate = now - (i * PODCASTS + p) * 60_000L, enclosureUrl = "https://p$p/$i.mp3", firstSeenAt = 0,
                            playState = if (i % 3 == 0) PlayState.PLAYED else PlayState.UNPLAYED,
                        )
                    },
                )
                if (p % 10 == 0) {
                    val first = db.episodeDao().getForPodcast(podcastId).take(3)
                    first.forEach { db.downloadDao().upsert(DownloadEntity(it.id, DownloadState.COMPLETED, DownloadSource.AUTO, filePath = "/x", totalBytes = 1)) }
                }
            }
        }
    }

    @After fun tearDown() {
        println("Stress (${PODCASTS} podcasts, ${PODCASTS * EPISODES_PER_PODCAST} episodes): " + timings.entries.joinToString { "${it.key} ${it.value} ms" })
        db.close()
    }

    private suspend fun <T> timed(name: String, block: suspend () -> T): T {
        block() // warm up
        val start = System.nanoTime()
        val result = block()
        timings[name] = (System.nanoTime() - start) / 1_000_000
        return result
    }

    @Test fun mainScreenQueriesStayFast() = runBlocking<Unit> {
        val latest = timed("latest") { db.episodeDao().observeLatest(now - 30 * day, 200, unplayedOnly = true, downloadedOnly = false).first() }
        assertThat(latest).hasSize(200)
        val library = timed("library") { db.podcastDao().observeLibrary().first() }
        assertThat(library).hasSize(PODCASTS)
        val podcastId = library.first().podcast.id
        val podcast = timed("podcast page") { db.episodeDao().observeForPodcast(podcastId, newestFirst = true, hidePlayed = false).first() }
        assertThat(podcast).hasSize(EPISODES_PER_PODCAST)
        val downloads = timed("downloads") { db.downloadDao().observeDownloadItems().first() }
        assertThat(downloads).hasSize(PODCASTS / 10 * 3)
        val search = SearchRepository(db, Clock { now }, Dispatchers.Unconfined)
        val hits = timed("search") { search.search("space").first() }
        assertThat(hits.items).isNotEmpty()
        val filtered = timed("search+filters") {
            search.search("science", com.steve.mcpodface.data.search.SearchFilters(status = com.steve.mcpodface.data.search.StatusFilter.UNPLAYED)).first()
        }
        assertThat(filtered.items).isNotEmpty()

        for ((name, ms) in timings) com.google.common.truth.Truth.assertWithMessage(name).that(ms).isLessThan(LIMIT_MS)
    }

    private companion object {
        const val PODCASTS = 500
        const val EPISODES_PER_PODCAST = 400
        const val LIMIT_MS = 1_500L
    }
}
