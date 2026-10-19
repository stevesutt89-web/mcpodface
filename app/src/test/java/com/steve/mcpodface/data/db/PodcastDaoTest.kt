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
class PodcastDaoTest {
    private lateinit var db: AppDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After fun tearDown() = db.close()

    private suspend fun podcast(title: String, subscribed: Boolean = true): Long =
        db.podcastDao().insert(
            PodcastEntity(feedUrl = "https://example.com/$title", title = title, isSubscribed = subscribed),
        )

    private suspend fun episode(podcastId: Long, guid: String, playState: PlayState = PlayState.UNPLAYED, inCurrentFeed: Boolean = true): Long =
        db.episodeDao().insertAll(
            listOf(
                EpisodeEntity(
                    podcastId = podcastId,
                    guid = guid,
                    title = guid,
                    pubDate = 1,
                    enclosureUrl = "https://example.com/$guid.mp3",
                    playState = playState,
                    inCurrentFeed = inCurrentFeed,
                    playedAt = if (playState == PlayState.PLAYED) 1L else null,
                    firstSeenAt = 0,
                ),
            ),
        ).single()

    @Test fun libraryCountsOnlyUnplayedEpisodes() = runTest {
        val show = podcast("Alpha")
        episode(show, "unplayed1")
        episode(show, "unplayed2")
        episode(show, "inProgress", PlayState.IN_PROGRESS)
        episode(show, "played", PlayState.PLAYED)

        val row = db.podcastDao().observeLibrary().first().single()

        assertThat(row.unplayedCount).isEqualTo(2)
    }

    @Test fun libraryUnplayedCountIsZeroWhenNoEpisodes() = runTest {
        podcast("Empty")

        val row = db.podcastDao().observeLibrary().first().single()

        assertThat(row.unplayedCount).isEqualTo(0)
    }

    @Test fun libraryCountIsPerPodcast() = runTest {
        val alpha = podcast("Alpha")
        val beta = podcast("Beta")
        episode(alpha, "a1")
        episode(alpha, "a2")
        episode(beta, "b1")

        val rows = db.podcastDao().observeLibrary().first().associateBy { it.podcast.title }

        assertThat(rows.getValue("Alpha").unplayedCount).isEqualTo(2)
        assertThat(rows.getValue("Beta").unplayedCount).isEqualTo(1)
    }

    @Test fun libraryCountsArchivedUnplayedEpisodes() = runTest {
        val show = podcast("Alpha")
        episode(show, "current")
        episode(show, "archived", inCurrentFeed = false)

        val row = db.podcastDao().observeLibrary().first().single()

        assertThat(row.unplayedCount).isEqualTo(2)
    }

    @Test fun libraryExcludesUnsubscribedPodcasts() = runTest {
        podcast("Subscribed", subscribed = true)
        podcast("Previewed", subscribed = false)

        val rows = db.podcastDao().observeLibrary().first()

        assertThat(rows.map { it.podcast.title }).containsExactly("Subscribed")
    }

    @Test fun libraryOrdersByTitleCaseInsensitive() = runTest {
        podcast("beta")
        podcast("Alpha")
        podcast("Gamma")

        val rows = db.podcastDao().observeLibrary().first()

        assertThat(rows.map { it.podcast.title }).containsExactly("Alpha", "beta", "Gamma").inOrder()
    }

    @Test fun libraryMapsPodcastColumns() = runTest {
        db.podcastDao().insert(
            PodcastEntity(
                feedUrl = "https://example.com/feed",
                title = "Alpha",
                artworkUrl = "https://example.com/art.jpg",
                isSubscribed = true,
                feedType = FeedType.SERIAL,
            ),
        )

        val row = db.podcastDao().observeLibrary().first().single()

        assertThat(row.podcast.feedUrl).isEqualTo("https://example.com/feed")
        assertThat(row.podcast.artworkUrl).isEqualTo("https://example.com/art.jpg")
        assertThat(row.podcast.feedType).isEqualTo(FeedType.SERIAL)
        assertThat(row.podcast.isSubscribed).isTrue()
    }

    @Test fun libraryOnEmptyTableIsEmpty() = runTest {
        assertThat(db.podcastDao().observeLibrary().first()).isEmpty()
    }
}
