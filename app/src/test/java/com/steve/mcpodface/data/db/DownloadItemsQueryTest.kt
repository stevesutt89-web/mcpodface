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
class DownloadItemsQueryTest {
    private lateinit var db: AppDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After fun tearDown() = db.close()

    private suspend fun podcast(title: String, artwork: String? = null, feedType: FeedType = FeedType.EPISODIC): Long =
        db.podcastDao().insert(
            PodcastEntity(
                feedUrl = "https://example.com/$title",
                title = title,
                artworkUrl = artwork,
                feedType = feedType,
                isSubscribed = true,
            ),
        )

    private suspend fun episode(
        podcastId: Long,
        guid: String,
        artwork: String? = null,
        playState: PlayState = PlayState.UNPLAYED,
        playedAt: Long? = null,
        starred: Boolean = false,
    ): Long = db.episodeDao().insertAll(
        listOf(
            EpisodeEntity(
                podcastId = podcastId,
                guid = guid,
                title = "Title $guid",
                pubDate = 1_000L,
                durationSec = 1800,
                enclosureUrl = "https://example.com/$guid.mp3",
                artworkUrl = artwork,
                playState = playState,
                playedAt = playedAt,
                isStarred = starred,
                firstSeenAt = 0,
            ),
        ),
    ).single()

    private suspend fun download(episodeId: Long, state: DownloadState, totalBytes: Long? = 100) =
        db.downloadDao().upsert(
            DownloadEntity(
                episodeId = episodeId,
                state = state,
                source = DownloadSource.AUTO,
                filePath = "/files/$episodeId.mp3",
                totalBytes = totalBytes,
                downloadedAt = 5_000L,
            ),
        )

    @Test fun returnsEveryDownloadRowWithItsEpisodeAndPodcast() = runTest {
        val show = podcast("Show", artwork = "podcast.jpg", feedType = FeedType.SERIAL)
        val done = episode(show, "done", playState = PlayState.PLAYED, playedAt = 9_000L, starred = true)
        val queued = episode(show, "queued")
        download(done, DownloadState.COMPLETED)
        download(queued, DownloadState.QUEUED, totalBytes = null)

        val items = db.downloadDao().observeDownloadItems().first()

        assertThat(items).hasSize(2)
        val first = items.first { it.download.episodeId == done }
        assertThat(first.download.state).isEqualTo(DownloadState.COMPLETED)
        assertThat(first.download.filePath).isEqualTo("/files/$done.mp3")
        assertThat(first.download.totalBytes).isEqualTo(100L)
        assertThat(first.episodeTitle).isEqualTo("Title done")
        assertThat(first.podcastId).isEqualTo(show)
        assertThat(first.podcastTitle).isEqualTo("Show")
        assertThat(first.feedType).isEqualTo(FeedType.SERIAL)
        assertThat(first.pubDate).isEqualTo(1_000L)
        assertThat(first.durationSec).isEqualTo(1800)
        assertThat(first.playState).isEqualTo(PlayState.PLAYED)
        assertThat(first.playedAt).isEqualTo(9_000L)
        assertThat(first.isStarred).isTrue()
    }

    @Test fun artworkFallsBackToPodcastWhenEpisodeHasNone() = runTest {
        val show = podcast("Show", artwork = "podcast.jpg")
        val plain = episode(show, "plain")
        val own = episode(show, "own", artwork = "episode.jpg")
        download(plain, DownloadState.COMPLETED)
        download(own, DownloadState.COMPLETED)

        val items = db.downloadDao().observeDownloadItems().first()

        assertThat(items.first { it.download.episodeId == plain }.artworkUrl).isEqualTo("podcast.jpg")
        assertThat(items.first { it.download.episodeId == own }.artworkUrl).isEqualTo("episode.jpg")
    }

    @Test fun artworkIsNullWhenNeitherHasOne() = runTest {
        val show = podcast("Show")
        val ep = episode(show, "bare")
        download(ep, DownloadState.COMPLETED)

        assertThat(db.downloadDao().observeDownloadItems().first().single().artworkUrl).isNull()
    }

    @Test fun excludesEpisodesWithoutDownloadRows() = runTest {
        val show = podcast("Show")
        val downloaded = episode(show, "yes")
        episode(show, "no")
        download(downloaded, DownloadState.COMPLETED)

        val items = db.downloadDao().observeDownloadItems().first()

        assertThat(items.map { it.download.episodeId }).containsExactly(downloaded)
    }

    @Test fun podcastSettingsQueryReturnsOverrides() = runTest {
        val show = podcast("Show")
        db.podcastSettingsDao().upsert(PodcastSettingsEntity(podcastId = show, maxAgeDays = 7))

        val rows = db.downloadDao().observePodcastSettings().first()

        assertThat(rows.single().podcastId).isEqualTo(show)
        assertThat(rows.single().maxAgeDays).isEqualTo(7)
    }
}
