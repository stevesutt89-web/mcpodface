package com.steve.mcpodface.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ListeningSessionDaoTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: ListeningSessionDao
    private var podcastId = 0L

    @Before fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = db.listeningSessionDao()
        podcastId = db.podcastDao().insert(PodcastEntity(feedUrl = "https://x/feed", title = "Show", artworkUrl = "https://x/art.jpg"))
        // Episode ids 1..3, titled E1..E3. Episode 3 is never listened to.
        db.episodeDao().insertAll(
            (1..3).map {
                EpisodeEntity(
                    podcastId = podcastId, guid = "g$it", title = "E$it", pubDate = it * 100L,
                    enclosureUrl = "https://x/$it.mp3", firstSeenAt = 0, durationSec = 600,
                )
            },
        )
    }

    @After fun tearDown() = db.close()

    private suspend fun session(episodeId: Long, startedAt: Long, listenedMs: Long) {
        dao.insert(ListeningSessionEntity(episodeId = episodeId, startedAt = startedAt, listenedMs = listenedMs, speed = 1f))
    }

    @Test fun recentlyPlayedOrdersByLatestSessionEndNotLatestStart() = runTest {
        // E1: a long session starting early (ends 6000) and a short one starting later (ends 2100).
        session(1, startedAt = 1_000, listenedMs = 5_000)
        session(1, startedAt = 2_000, listenedMs = 100)
        // E2: one session ending at 3500.
        session(2, startedAt = 3_000, listenedMs = 500)

        val items = dao.observeRecentlyPlayed().first()

        assertThat(items.map { it.episode.id }).containsExactly(1L, 2L).inOrder()
        assertThat(items[0].lastListenedAt).isEqualTo(6_000L)
        assertThat(items[1].lastListenedAt).isEqualTo(3_500L)
    }

    @Test fun recentlyPlayedHasOneRowPerEpisodeWithTotalListenedMs() = runTest {
        session(1, startedAt = 1_000, listenedMs = 500)
        session(1, startedAt = 3_000, listenedMs = 200)
        session(2, startedAt = 2_000, listenedMs = 1_000)

        val items = dao.observeRecentlyPlayed().first()

        assertThat(items).hasSize(2)
        val first = items.single { it.episode.id == 1L }
        assertThat(first.totalListenedMs).isEqualTo(700L)
        assertThat(first.lastListenedAt).isEqualTo(3_200L)
        val second = items.single { it.episode.id == 2L }
        assertThat(second.totalListenedMs).isEqualTo(1_000L)
        assertThat(second.lastListenedAt).isEqualTo(3_000L)
    }

    @Test fun recentlyPlayedCarriesPodcastAndEpisodeColumns() = runTest {
        session(2, startedAt = 1_000, listenedMs = 500)
        db.queueDao().replaceAll(listOf(2L))

        val item = dao.observeRecentlyPlayed().first().single()

        assertThat(item.episode.title).isEqualTo("E2")
        assertThat(item.podcastTitle).isEqualTo("Show")
        assertThat(item.podcastArtworkUrl).isEqualTo("https://x/art.jpg")
        assertThat(item.downloadState).isNull()
        assertThat(item.inQueue).isTrue()
    }

    @Test fun clearHistoryRemovesAllSessions() = runTest {
        session(1, startedAt = 1_000, listenedMs = 500)
        session(2, startedAt = 2_000, listenedMs = 500)

        dao.clearHistory()

        assertThat(dao.observeRecentlyPlayed().first()).isEmpty()
        assertThat(dao.getAll()).isEmpty()
    }
}
