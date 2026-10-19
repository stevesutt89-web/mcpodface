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
class DownloadDaoTest {
    private lateinit var db: AppDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After fun tearDown() = db.close()

    private suspend fun podcast(title: String): Long =
        db.podcastDao().insert(PodcastEntity(feedUrl = "https://example.com/$title", title = title, isSubscribed = true))

    private suspend fun episode(podcastId: Long, guid: String): Long =
        db.episodeDao().insertAll(
            listOf(
                EpisodeEntity(
                    podcastId = podcastId,
                    guid = guid,
                    title = guid,
                    pubDate = 1,
                    enclosureUrl = "https://example.com/$guid.mp3",
                    firstSeenAt = 0,
                ),
            ),
        ).single()

    private suspend fun download(
        episodeId: Long,
        state: DownloadState,
        bytesDownloaded: Long = 0,
        totalBytes: Long? = null,
    ) = db.downloadDao().upsert(
        DownloadEntity(
            episodeId = episodeId,
            state = state,
            source = DownloadSource.MANUAL,
            bytesDownloaded = bytesDownloaded,
            totalBytes = totalBytes,
        ),
    )

    // ---- observeSizesByPodcast ----

    @Test fun sizesGroupCompletedDownloadsByPodcast() = runTest {
        val alpha = podcast("Alpha")
        val beta = podcast("Beta")
        val a1 = episode(alpha, "a1")
        val a2 = episode(alpha, "a2")
        val b1 = episode(beta, "b1")
        download(a1, DownloadState.COMPLETED, totalBytes = 100)
        download(a2, DownloadState.COMPLETED, totalBytes = 50)
        download(b1, DownloadState.COMPLETED, totalBytes = 120)

        val sizes = db.downloadDao().observeSizesByPodcast().first()

        assertThat(sizes).hasSize(2)
        val alphaRow = sizes.first { it.podcastId == alpha }
        assertThat(alphaRow.podcastTitle).isEqualTo("Alpha")
        assertThat(alphaRow.episodeCount).isEqualTo(2)
        assertThat(alphaRow.totalBytes).isEqualTo(150L)
        val betaRow = sizes.first { it.podcastId == beta }
        assertThat(betaRow.episodeCount).isEqualTo(1)
        assertThat(betaRow.totalBytes).isEqualTo(120L)
    }

    @Test fun sizesOrderLargestFirst() = runTest {
        val small = podcast("Small")
        val big = podcast("Big")
        val mid = podcast("Mid")
        download(episode(small, "s"), DownloadState.COMPLETED, totalBytes = 10)
        download(episode(big, "b"), DownloadState.COMPLETED, totalBytes = 1000)
        download(episode(mid, "m"), DownloadState.COMPLETED, totalBytes = 500)

        val sizes = db.downloadDao().observeSizesByPodcast().first()

        assertThat(sizes.map { it.podcastTitle }).containsExactly("Big", "Mid", "Small").inOrder()
    }

    @Test fun sizesIgnoreNonCompletedDownloads() = runTest {
        val show = podcast("Alpha")
        download(episode(show, "done"), DownloadState.COMPLETED, totalBytes = 100)
        download(episode(show, "queued"), DownloadState.QUEUED, totalBytes = 999)
        download(episode(show, "downloading"), DownloadState.DOWNLOADING, bytesDownloaded = 999)
        download(episode(show, "failed"), DownloadState.FAILED, totalBytes = 999)
        download(episode(show, "paused"), DownloadState.PAUSED_NETWORK, totalBytes = 999)

        val sizes = db.downloadDao().observeSizesByPodcast().first()

        assertThat(sizes.single().episodeCount).isEqualTo(1)
        assertThat(sizes.single().totalBytes).isEqualTo(100L)
    }

    @Test fun sizesUseBytesDownloadedWhenTotalUnknown() = runTest {
        val show = podcast("Alpha")
        download(episode(show, "withTotal"), DownloadState.COMPLETED, bytesDownloaded = 5, totalBytes = 100)
        download(episode(show, "noTotal"), DownloadState.COMPLETED, bytesDownloaded = 40, totalBytes = null)

        val row = db.downloadDao().observeSizesByPodcast().first().single()

        assertThat(row.totalBytes).isEqualTo(140L)
    }

    @Test fun sizesEmptyWhenNothingCompleted() = runTest {
        val show = podcast("Alpha")
        download(episode(show, "queued"), DownloadState.QUEUED)

        assertThat(db.downloadDao().observeSizesByPodcast().first()).isEmpty()
    }

    // ---- observeTotals ----

    @Test fun totalsAreZeroWhenEmpty() = runTest {
        val totals = db.downloadDao().observeTotals().first()

        assertThat(totals.episodeCount).isEqualTo(0)
        assertThat(totals.totalBytes).isEqualTo(0L)
    }

    @Test fun totalsAreZeroWhenNoneCompleted() = runTest {
        val show = podcast("Alpha")
        download(episode(show, "queued"), DownloadState.QUEUED, totalBytes = 500)

        val totals = db.downloadDao().observeTotals().first()

        assertThat(totals.episodeCount).isEqualTo(0)
        assertThat(totals.totalBytes).isEqualTo(0L)
    }

    @Test fun totalsSumCompletedOnly() = runTest {
        val show = podcast("Alpha")
        download(episode(show, "a"), DownloadState.COMPLETED, totalBytes = 100)
        download(episode(show, "b"), DownloadState.COMPLETED, totalBytes = 200)
        download(episode(show, "c"), DownloadState.FAILED, totalBytes = 999)

        val totals = db.downloadDao().observeTotals().first()

        assertThat(totals.episodeCount).isEqualTo(2)
        assertThat(totals.totalBytes).isEqualTo(300L)
    }

    @Test fun totalsFallBackToBytesDownloaded() = runTest {
        val show = podcast("Alpha")
        download(episode(show, "a"), DownloadState.COMPLETED, bytesDownloaded = 70, totalBytes = null)
        download(episode(show, "b"), DownloadState.COMPLETED, bytesDownloaded = 1, totalBytes = 30)

        val totals = db.downloadDao().observeTotals().first()

        assertThat(totals.totalBytes).isEqualTo(100L)
    }

    // ---- getByState ----

    @Test fun getByStateReturnsOnlyMatchingRows() = runTest {
        val show = podcast("Alpha")
        val queued1 = episode(show, "q1")
        val queued2 = episode(show, "q2")
        val failed = episode(show, "f")
        val done = episode(show, "d")
        download(queued1, DownloadState.QUEUED)
        download(queued2, DownloadState.QUEUED)
        download(failed, DownloadState.FAILED)
        download(done, DownloadState.COMPLETED)

        val queued = db.downloadDao().getByState(DownloadState.QUEUED)

        assertThat(queued.map { it.episodeId }).containsExactly(queued1, queued2)
    }

    @Test fun getByStateReturnsEmptyWhenNoneMatch() = runTest {
        val show = podcast("Alpha")
        download(episode(show, "d"), DownloadState.COMPLETED)

        assertThat(db.downloadDao().getByState(DownloadState.PAUSED_NETWORK)).isEmpty()
    }

    @Test fun getByStateMapsFields() = runTest {
        val show = podcast("Alpha")
        val id = episode(show, "f")
        db.downloadDao().upsert(
            DownloadEntity(
                episodeId = id,
                state = DownloadState.FAILED,
                source = DownloadSource.AUTO,
                error = "boom",
                attempts = 3,
            ),
        )

        val row = db.downloadDao().getByState(DownloadState.FAILED).single()

        assertThat(row.source).isEqualTo(DownloadSource.AUTO)
        assertThat(row.error).isEqualTo("boom")
        assertThat(row.attempts).isEqualTo(3)
    }
}
