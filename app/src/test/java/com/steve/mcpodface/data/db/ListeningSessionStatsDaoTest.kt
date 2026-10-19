package com.steve.mcpodface.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.steve.mcpodface.ui.stats.timeSavedMs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The Stats queries on [ListeningSessionDao] (PLAN.md §13), against a real in-memory database. */
@RunWith(AndroidJUnit4::class)
class ListeningSessionStatsDaoTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: ListeningSessionDao
    private var alpha = 0L
    private var beta = 0L

    // Episodes 1 and 2 are Alpha's, 3 to 6 are Beta's.
    //   1 Alpha PLAYED playedAt 100      2 Alpha PLAYED playedAt 500
    //   3 Alpha IN_PROGRESS              4 Beta PLAYED with no playedAt
    //   5 Beta PLAYED playedAt 900       6 Beta UNPLAYED
    @Before fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = db.listeningSessionDao()
        alpha = db.podcastDao().insert(PodcastEntity(feedUrl = "https://x/a", title = "Alpha", artworkUrl = "https://x/a.jpg"))
        beta = db.podcastDao().insert(PodcastEntity(feedUrl = "https://x/b", title = "Beta"))
        db.episodeDao().insertAll(
            listOf(
                episode(alpha, 1, PlayState.PLAYED, playedAt = 100L),
                episode(alpha, 2, PlayState.PLAYED, playedAt = 500L),
                episode(alpha, 3, PlayState.IN_PROGRESS),
                episode(beta, 4, PlayState.PLAYED, playedAt = null),
                episode(beta, 5, PlayState.PLAYED, playedAt = 900L),
                episode(beta, 6, PlayState.UNPLAYED),
            ),
        )
    }

    @After fun tearDown() = db.close()

    private fun episode(
        podcastId: Long,
        n: Int,
        playState: PlayState,
        playedAt: Long? = null,
    ) = EpisodeEntity(
        podcastId = podcastId,
        guid = "g$n",
        title = "E$n",
        pubDate = n * 100L,
        enclosureUrl = "https://x/$n.mp3",
        firstSeenAt = 0,
        durationSec = 600,
        playState = playState,
        playedAt = playedAt,
    )

    private suspend fun session(episodeId: Long, startedAt: Long, listenedMs: Long, speed: Float = 1f) {
        dao.insert(ListeningSessionEntity(episodeId = episodeId, startedAt = startedAt, listenedMs = listenedMs, speed = speed))
    }

    @Test fun totalListenedIsZeroWithNoSessions() = runTest {
        assertThat(dao.observeTotalListenedMs().first()).isEqualTo(0L)
    }

    @Test fun totalListenedSumsEverySession() = runTest {
        session(1, startedAt = 1_000, listenedMs = 300_000)
        session(4, startedAt = 2_000, listenedMs = 200_000)
        session(1, startedAt = 3_000, listenedMs = 100_000)

        assertThat(dao.observeTotalListenedMs().first()).isEqualTo(600_000L)
    }

    @Test fun sessionTimesComeBackOldestFirstWithStartAndListened() = runTest {
        session(1, startedAt = 3_000, listenedMs = 30)
        session(2, startedAt = 1_000, listenedMs = 10)

        val rows = dao.observeSessionTimes().first()

        assertThat(rows).containsExactly(SessionTimeRow(1_000, 10), SessionTimeRow(3_000, 30)).inOrder()
    }

    @Test fun topPodcastsSumPerShowWithArtworkAndMostFirst() = runTest {
        session(1, startedAt = 1_000, listenedMs = 400_000) // Alpha
        session(3, startedAt = 2_000, listenedMs = 100_000) // Alpha
        session(5, startedAt = 3_000, listenedMs = 300_000) // Beta
        session(4, startedAt = 4_000, listenedMs = 50_000) // Beta

        val top = dao.observeTopPodcasts().first()

        assertThat(top.map { it.podcastTitle }).containsExactly("Alpha", "Beta").inOrder()
        assertThat(top[0]).isEqualTo(PodcastListeningTotal(alpha, "Alpha", "https://x/a.jpg", 500_000L))
        assertThat(top[1]).isEqualTo(PodcastListeningTotal(beta, "Beta", null, 350_000L))
    }

    @Test fun topPodcastsAreCappedAtTen() = runTest {
        for (i in 1..11) {
            val podcast = db.podcastDao().insert(PodcastEntity(feedUrl = "https://x/p$i", title = "P$i"))
            val episodeId = db.episodeDao().insertAll(listOf(episode(podcast, 100 + i, PlayState.UNPLAYED)))[0]
            session(episodeId, startedAt = i * 1_000L, listenedMs = i * 1_000L)
        }

        val top = dao.observeTopPodcasts().first()

        assertThat(top).hasSize(10)
        assertThat(top.first().podcastTitle).isEqualTo("P11")
        assertThat(top.map { it.podcastTitle }).doesNotContain("P1")
    }

    @Test fun timeSavedMatchesThePureFormulaAndIgnoresSpeedsUpToOne() = runTest {
        session(1, startedAt = 1_000, listenedMs = 60_000, speed = 1.5f) // 30 s saved
        session(4, startedAt = 2_000, listenedMs = 60_000, speed = 1f) // nothing
        session(5, startedAt = 3_000, listenedMs = 120_000, speed = 2f) // 2 min saved
        session(6, startedAt = 4_000, listenedMs = 30_000, speed = 0.8f) // nothing

        val saved = dao.observeTimeSavedMs().first()

        assertThat(saved).isWithin(1e-6).of(150_000.0)
        assertThat(saved).isWithin(1e-6).of(timeSavedMs(listOf(60_000L to 1.5f, 60_000L to 1f, 120_000L to 2f, 30_000L to 0.8f)))
    }

    @Test fun timeSavedIsZeroWithNoFastSessions() = runTest {
        session(1, startedAt = 1_000, listenedMs = 60_000, speed = 1f)

        assertThat(dao.observeTimeSavedMs().first()).isWithin(1e-6).of(0.0)
    }

    @Test fun finishedCountsCountPlayedEpisodesWithAPlayedAt() = runTest {
        // Episodes 1, 2 and 5 are finished. 4 is PLAYED but has no playedAt, so it does not count.
        val counts = dao.observeFinishedCounts(since = 400L).first()

        assertThat(counts).isEqualTo(FinishedCounts(total = 3, last30Days = 2))
    }

    @Test fun finishedCountsWithNothingFinishedInTheWindow() = runTest {
        assertThat(dao.observeFinishedCounts(since = 10_000L).first()).isEqualTo(FinishedCounts(total = 3, last30Days = 0))
    }

    @Test fun finishedCountsAreZeroWithNoFinishedEpisodes() = runTest {
        db.episodeDao().setPlayState(1, PlayState.UNPLAYED, playedAt = null, positionMs = null)
        db.episodeDao().setPlayState(2, PlayState.IN_PROGRESS, playedAt = null, positionMs = null)
        db.episodeDao().setPlayState(5, PlayState.UNPLAYED, playedAt = null, positionMs = null)

        assertThat(dao.observeFinishedCounts(since = 0L).first()).isEqualTo(FinishedCounts(total = 0, last30Days = 0))
    }
}
