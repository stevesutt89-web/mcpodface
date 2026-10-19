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
class QueueDaoTest {
    private lateinit var db: AppDatabase
    private var podcastId = 0L

    @Before fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        podcastId = db.podcastDao().insert(PodcastEntity(feedUrl = "https://x/feed", title = "Show", artworkUrl = "https://x/art.jpg"))
        // Episode ids 1..4, titled E1..E4.
        db.episodeDao().insertAll(
            (1..4).map {
                EpisodeEntity(
                    podcastId = podcastId, guid = "g$it", title = "E$it", pubDate = it * 100L,
                    enclosureUrl = "https://x/$it.mp3", firstSeenAt = 0, durationSec = 600,
                )
            },
        )
    }

    @After fun tearDown() = db.close()

    @Test fun observeQueueItemsFollowsQueuePositionNotInsertionOrder() = runTest {
        db.queueDao().replaceAll(listOf(3L, 1L, 4L))

        val ids = db.queueDao().observeQueueItems().first().map { it.episode.id }

        assertThat(ids).containsExactly(3L, 1L, 4L).inOrder()
    }

    @Test fun observeQueueItemsExcludesEpisodesNotInQueue() = runTest {
        db.queueDao().replaceAll(listOf(1L, 3L))

        val ids = db.queueDao().observeQueueItems().first().map { it.episode.id }

        assertThat(ids).containsExactly(1L, 3L).inOrder()
    }

    @Test fun observeQueueItemsCarriesRowColumns() = runTest {
        db.queueDao().replaceAll(listOf(1L, 2L))
        db.downloadDao().upsert(
            DownloadEntity(episodeId = 1, state = DownloadState.COMPLETED, source = DownloadSource.MANUAL, totalBytes = 5_000L),
        )

        val items = db.queueDao().observeQueueItems().first()

        val downloaded = items[0]
        assertThat(downloaded.podcastTitle).isEqualTo("Show")
        assertThat(downloaded.podcastArtworkUrl).isEqualTo("https://x/art.jpg")
        assertThat(downloaded.downloadState).isEqualTo(DownloadState.COMPLETED)
        assertThat(downloaded.downloadBytes).isEqualTo(5_000L)
        assertThat(downloaded.inQueue).isTrue()
        assertThat(downloaded.episode.title).isEqualTo("E1")

        val notDownloaded = items[1]
        assertThat(notDownloaded.downloadState).isNull()
        assertThat(notDownloaded.downloadBytes).isNull()
        assertThat(notDownloaded.inQueue).isTrue()
    }

    @Test fun observeQueueItemsReemitsAfterReplaceAll() = runTest {
        db.queueDao().replaceAll(listOf(1L, 2L, 3L))
        db.queueDao().replaceAll(listOf(4L, 2L))

        val ids = db.queueDao().observeQueueItems().first().map { it.episode.id }

        assertThat(ids).containsExactly(4L, 2L).inOrder()
    }
}
