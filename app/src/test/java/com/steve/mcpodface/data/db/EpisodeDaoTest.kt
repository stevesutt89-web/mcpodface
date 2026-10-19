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
class EpisodeDaoTest {
    private lateinit var db: AppDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After fun tearDown() = db.close()

    private suspend fun podcast(title: String, subscribed: Boolean = true, artwork: String? = null): Long =
        db.podcastDao().insert(
            PodcastEntity(
                feedUrl = "https://example.com/$title",
                title = title,
                isSubscribed = subscribed,
                artworkUrl = artwork,
            ),
        )

    private fun ep(
        podcastId: Long,
        guid: String,
        pubDate: Long,
        playState: PlayState = PlayState.UNPLAYED,
        inCurrentFeed: Boolean = true,
        playedAt: Long? = null,
    ) = EpisodeEntity(
        podcastId = podcastId,
        guid = guid,
        title = guid,
        pubDate = pubDate,
        enclosureUrl = "https://example.com/$guid.mp3",
        playState = playState,
        inCurrentFeed = inCurrentFeed,
        playedAt = playedAt,
        firstSeenAt = 0,
    )

    private suspend fun insert(vararg episodes: EpisodeEntity): List<Long> =
        db.episodeDao().insertAll(episodes.toList())

    private suspend fun download(episodeId: Long, state: DownloadState, bytesDownloaded: Long = 0, totalBytes: Long? = null) =
        db.downloadDao().upsert(
            DownloadEntity(
                episodeId = episodeId,
                state = state,
                source = DownloadSource.AUTO,
                bytesDownloaded = bytesDownloaded,
                totalBytes = totalBytes,
            ),
        )

    // ---- observeLatest ----

    @Test fun latestReturnsOnlyEpisodesAtOrAfterSince() = runTest {
        val show = podcast("Alpha")
        val (old, edge, fresh) = insert(
            ep(show, "old", 99), ep(show, "edge", 100), ep(show, "fresh", 500),
        ).let { Triple(it[0], it[1], it[2]) }

        val result = db.episodeDao().observeLatest(100, 50, unplayedOnly = false, downloadedOnly = false).first()

        assertThat(result.map { it.episode.id }).containsExactly(fresh, edge).inOrder()
        assertThat(result.map { it.episode.id }).doesNotContain(old)
    }

    @Test fun latestExcludesUnsubscribedPodcasts() = runTest {
        val subscribed = podcast("Alpha", subscribed = true)
        val previewOnly = podcast("Beta", subscribed = false)
        val (mine, _) = insert(ep(subscribed, "a", 10), ep(previewOnly, "b", 20)).let { it[0] to it[1] }

        val result = db.episodeDao().observeLatest(0, 50, false, false).first()

        assertThat(result.map { it.episode.id }).containsExactly(mine)
    }

    @Test fun latestOrdersNewestFirstWithIdDescTieBreak() = runTest {
        val show = podcast("Alpha")
        val ids = insert(
            ep(show, "a", 10),
            ep(show, "b", 30),
            ep(show, "c", 20),
            ep(show, "d", 30),
        )

        val result = db.episodeDao().observeLatest(0, 50, false, false).first()

        // pubDate 30 twice: the later-inserted id (d) comes first.
        assertThat(result.map { it.episode.id }).containsExactly(ids[3], ids[1], ids[2], ids[0]).inOrder()
    }

    @Test fun latestRespectsLimit() = runTest {
        val show = podcast("Alpha")
        val ids = insert(ep(show, "a", 1), ep(show, "b", 2), ep(show, "c", 3), ep(show, "d", 4))

        val result = db.episodeDao().observeLatest(0, 2, false, false).first()

        assertThat(result.map { it.episode.id }).containsExactly(ids[3], ids[2]).inOrder()
    }

    @Test fun latestUnplayedOnlyDropsPlayedButKeepsInProgress() = runTest {
        val show = podcast("Alpha")
        val ids = insert(
            ep(show, "unplayed", 1),
            ep(show, "inProgress", 2, playState = PlayState.IN_PROGRESS),
            ep(show, "played", 3, playState = PlayState.PLAYED, playedAt = 50),
        )

        val result = db.episodeDao().observeLatest(0, 50, unplayedOnly = true, downloadedOnly = false).first()

        assertThat(result.map { it.episode.id }).containsExactly(ids[1], ids[0]).inOrder()
    }

    @Test fun latestDownloadedOnlyKeepsCompletedDownloadsOnly() = runTest {
        val show = podcast("Alpha")
        val ids = insert(
            ep(show, "completed", 1),
            ep(show, "queued", 2),
            ep(show, "failed", 3),
            ep(show, "notDownloaded", 4),
        )
        download(ids[0], DownloadState.COMPLETED, bytesDownloaded = 10)
        download(ids[1], DownloadState.QUEUED)
        download(ids[2], DownloadState.FAILED)

        val result = db.episodeDao().observeLatest(0, 50, unplayedOnly = false, downloadedOnly = true).first()

        assertThat(result.map { it.episode.id }).containsExactly(ids[0])
    }

    @Test fun latestCombinesUnplayedAndDownloadedFilters() = runTest {
        val show = podcast("Alpha")
        val ids = insert(
            ep(show, "downloadedUnplayed", 1),
            ep(show, "downloadedPlayed", 2, playState = PlayState.PLAYED, playedAt = 5),
            ep(show, "unplayedNotDownloaded", 3),
        )
        download(ids[0], DownloadState.COMPLETED)
        download(ids[1], DownloadState.COMPLETED)

        val result = db.episodeDao().observeLatest(0, 50, unplayedOnly = true, downloadedOnly = true).first()

        assertThat(result.map { it.episode.id }).containsExactly(ids[0])
    }

    @Test fun latestRowsWithNoDownloadHaveNullDownloadFields() = runTest {
        val show = podcast("Alpha", artwork = "https://example.com/show.jpg")
        val id = insert(ep(show, "a", 1)).single()

        val item = db.episodeDao().observeLatest(0, 50, false, false).first().single()

        assertThat(item.episode.id).isEqualTo(id)
        assertThat(item.downloadState).isNull()
        assertThat(item.downloadBytes).isNull()
    }

    @Test fun latestDownloadBytesPrefersTotalThenBytesDownloaded() = runTest {
        val show = podcast("Alpha")
        val ids = insert(ep(show, "withTotal", 2), ep(show, "partialOnly", 1))
        download(ids[0], DownloadState.COMPLETED, bytesDownloaded = 400, totalBytes = 1000)
        download(ids[1], DownloadState.COMPLETED, bytesDownloaded = 700, totalBytes = null)

        val result = db.episodeDao().observeLatest(0, 50, false, false).first()

        assertThat(result.map { it.episode.id }).containsExactly(ids[0], ids[1]).inOrder()
        assertThat(result[0].downloadBytes).isEqualTo(1000L)
        assertThat(result[0].downloadState).isEqualTo(DownloadState.COMPLETED)
        assertThat(result[1].downloadBytes).isEqualTo(700L)
    }

    @Test fun latestInQueueReflectsQueueItems() = runTest {
        val show = podcast("Alpha")
        val ids = insert(ep(show, "queued", 2), ep(show, "notQueued", 1))
        db.queueDao().upsertAll(listOf(QueueItemEntity(episodeId = ids[0], position = 0)))

        val result = db.episodeDao().observeLatest(0, 50, false, false).first()

        assertThat(result.first { it.episode.id == ids[0] }.inQueue).isTrue()
        assertThat(result.first { it.episode.id == ids[1] }.inQueue).isFalse()
    }

    @Test fun latestCarriesPodcastTitleAndArtwork() = runTest {
        val show = podcast("Alpha", artwork = "https://example.com/show.jpg")
        insert(ep(show, "a", 1).copy(artworkUrl = "https://example.com/episode.jpg"))

        val item = db.episodeDao().observeLatest(0, 50, false, false).first().single()

        assertThat(item.podcastTitle).isEqualTo("Alpha")
        assertThat(item.podcastArtworkUrl).isEqualTo("https://example.com/show.jpg")
        assertThat(item.episode.artworkUrl).isEqualTo("https://example.com/episode.jpg")
    }

    @Test fun latestOnEmptyTableIsEmpty() = runTest {
        assertThat(db.episodeDao().observeLatest(0, 50, false, false).first()).isEmpty()
    }

    // ---- observeForPodcast ----

    @Test fun podcastEpisodesNewestFirst() = runTest {
        val show = podcast("Alpha")
        val ids = insert(ep(show, "a", 10), ep(show, "b", 30), ep(show, "c", 20), ep(show, "d", 30))

        val result = db.episodeDao().observeForPodcast(show, newestFirst = true, hidePlayed = false).first()

        assertThat(result.map { it.episode.id }).containsExactly(ids[3], ids[1], ids[2], ids[0]).inOrder()
    }

    @Test fun podcastEpisodesOldestFirstWithIdAscTieBreak() = runTest {
        val show = podcast("Alpha")
        val ids = insert(ep(show, "a", 10), ep(show, "b", 30), ep(show, "c", 20), ep(show, "d", 30))

        val result = db.episodeDao().observeForPodcast(show, newestFirst = false, hidePlayed = false).first()

        // pubDate 30 twice: the earlier-inserted id (b) comes first.
        assertThat(result.map { it.episode.id }).containsExactly(ids[0], ids[2], ids[1], ids[3]).inOrder()
    }

    @Test fun podcastEpisodesIncludeArchivedOnes() = runTest {
        val show = podcast("Alpha")
        val ids = insert(
            ep(show, "current", 2),
            ep(show, "archived", 1, inCurrentFeed = false),
        )

        val result = db.episodeDao().observeForPodcast(show, newestFirst = true, hidePlayed = false).first()

        assertThat(result.map { it.episode.id }).containsExactly(ids[0], ids[1]).inOrder()
        assertThat(result.last().episode.inCurrentFeed).isFalse()
    }

    @Test fun podcastEpisodesHidePlayedDropsOnlyPlayed() = runTest {
        val show = podcast("Alpha")
        val ids = insert(
            ep(show, "unplayed", 1),
            ep(show, "inProgress", 2, playState = PlayState.IN_PROGRESS),
            ep(show, "played", 3, playState = PlayState.PLAYED, playedAt = 9),
        )

        val result = db.episodeDao().observeForPodcast(show, newestFirst = false, hidePlayed = true).first()

        assertThat(result.map { it.episode.id }).containsExactly(ids[0], ids[1]).inOrder()
    }

    @Test fun podcastEpisodesOnlyFromThatPodcast() = runTest {
        val alpha = podcast("Alpha")
        val beta = podcast("Beta")
        val ids = insert(ep(alpha, "a", 1), ep(beta, "b", 2))

        val result = db.episodeDao().observeForPodcast(alpha, newestFirst = true, hidePlayed = false).first()

        assertThat(result.map { it.episode.id }).containsExactly(ids[0])
        assertThat(result.single().podcastTitle).isEqualTo("Alpha")
    }

    @Test fun podcastEpisodesIncludeUnsubscribedPodcast() = runTest {
        // Podcast detail is reachable for previewed (not subscribed) podcasts too.
        val preview = podcast("Preview", subscribed = false)
        val id = insert(ep(preview, "a", 1)).single()

        val result = db.episodeDao().observeForPodcast(preview, newestFirst = true, hidePlayed = false).first()

        assertThat(result.map { it.episode.id }).containsExactly(id)
    }

    @Test fun podcastEpisodesHaveNullDownloadFieldsWithoutRow() = runTest {
        val show = podcast("Alpha")
        val ids = insert(ep(show, "plain", 1), ep(show, "downloaded", 2))
        download(ids[1], DownloadState.DOWNLOADING, bytesDownloaded = 42)

        val result = db.episodeDao().observeForPodcast(show, newestFirst = true, hidePlayed = false).first()
        val plain = result.first { it.episode.id == ids[0] }
        val downloaded = result.first { it.episode.id == ids[1] }

        assertThat(plain.downloadState).isNull()
        assertThat(plain.downloadBytes).isNull()
        assertThat(downloaded.downloadState).isEqualTo(DownloadState.DOWNLOADING)
        assertThat(downloaded.downloadBytes).isEqualTo(42L)
    }

    @Test fun podcastEpisodesInQueueFlag() = runTest {
        val show = podcast("Alpha")
        val ids = insert(ep(show, "queued", 2), ep(show, "notQueued", 1))
        db.queueDao().upsertAll(listOf(QueueItemEntity(episodeId = ids[0], position = 0)))

        val result = db.episodeDao().observeForPodcast(show, newestFirst = true, hidePlayed = false).first()

        assertThat(result.first { it.episode.id == ids[0] }.inQueue).isTrue()
        assertThat(result.first { it.episode.id == ids[1] }.inQueue).isFalse()
    }

    // ---- markAllPlayed ----

    @Test fun markAllPlayedSetsStateAndTimestampOnNotPlayedRows() = runTest {
        val show = podcast("Alpha")
        val ids = insert(
            ep(show, "unplayed", 1),
            ep(show, "inProgress", 2, playState = PlayState.IN_PROGRESS),
        )

        db.episodeDao().markAllPlayed(show, at = 1234)

        val unplayed = db.episodeDao().getById(ids[0])!!
        val inProgress = db.episodeDao().getById(ids[1])!!
        assertThat(unplayed.playState).isEqualTo(PlayState.PLAYED)
        assertThat(unplayed.playedAt).isEqualTo(1234L)
        assertThat(inProgress.playState).isEqualTo(PlayState.PLAYED)
        assertThat(inProgress.playedAt).isEqualTo(1234L)
    }

    @Test fun markAllPlayedLeavesAlreadyPlayedRowsUntouched() = runTest {
        val show = podcast("Alpha")
        val ids = insert(
            ep(show, "alreadyPlayed", 1, playState = PlayState.PLAYED, playedAt = 111),
            ep(show, "unplayed", 2),
        )

        db.episodeDao().markAllPlayed(show, at = 999)

        val alreadyPlayed = db.episodeDao().getById(ids[0])!!
        assertThat(alreadyPlayed.playState).isEqualTo(PlayState.PLAYED)
        assertThat(alreadyPlayed.playedAt).isEqualTo(111L)
        assertThat(db.episodeDao().getById(ids[1])!!.playedAt).isEqualTo(999L)
    }

    @Test fun markAllPlayedOnlyAffectsThatPodcast() = runTest {
        val alpha = podcast("Alpha")
        val beta = podcast("Beta")
        val ids = insert(ep(alpha, "a", 1), ep(beta, "b", 1))

        db.episodeDao().markAllPlayed(alpha, at = 5)

        assertThat(db.episodeDao().getById(ids[0])!!.playState).isEqualTo(PlayState.PLAYED)
        assertThat(db.episodeDao().getById(ids[1])!!.playState).isEqualTo(PlayState.UNPLAYED)
        assertThat(db.episodeDao().getById(ids[1])!!.playedAt).isNull()
    }

    @Test fun markAllPlayedAlsoCoversArchivedEpisodes() = runTest {
        val show = podcast("Alpha")
        val ids = insert(ep(show, "archived", 1, inCurrentFeed = false))

        db.episodeDao().markAllPlayed(show, at = 7)

        assertThat(db.episodeDao().getById(ids[0])!!.playState).isEqualTo(PlayState.PLAYED)
    }
}
