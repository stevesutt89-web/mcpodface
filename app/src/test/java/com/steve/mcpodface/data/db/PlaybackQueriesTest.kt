package com.steve.mcpodface.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaybackQueriesTest {
    private lateinit var db: AppDatabase
    private var podcastId = 0L

    @Before fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        podcastId = db.podcastDao().insert(PodcastEntity(feedUrl = "https://x/feed", title = "X"))
        // ids 1..5, pubDate 100..500; ep 3 is played.
        db.episodeDao().insertAll(
            (1..5).map {
                EpisodeEntity(
                    podcastId = podcastId, guid = "g$it", title = "E$it", pubDate = it * 100L,
                    enclosureUrl = "https://x/$it.mp3", firstSeenAt = 0,
                    playState = if (it == 3) PlayState.PLAYED else PlayState.UNPLAYED,
                    lastPlayedAt = if (it == 2) 999L else null,
                )
            },
        )
    }

    @After fun tearDown() = db.close()

    private suspend fun next(afterId: Long, newestFirst: Boolean) =
        db.episodeDao().getNextUnplayedInPodcast(podcastId, afterId * 100, afterId, newestFirst)?.id

    @Test fun oldestFirstGoesToNewerSkippingPlayed() = runTest {
        assertThat(next(2, newestFirst = false)).isEqualTo(4)
    }

    @Test fun newestFirstGoesToOlderSkippingPlayed() = runTest {
        assertThat(next(4, newestFirst = true)).isEqualTo(2)
    }

    @Test fun endOfListReturnsNull() = runTest {
        assertThat(next(5, newestFirst = false)).isNull()
        assertThat(next(1, newestFirst = true)).isNull()
    }

    @Test fun lastPlayedIsMostRecent() = runTest {
        assertThat(db.episodeDao().getLastPlayed()?.id).isEqualTo(2)
    }
}
