package com.steve.mcpodface.refresh

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
import com.steve.mcpodface.data.prefs.GlobalSettings
import com.steve.mcpodface.data.prefs.SettingsRepository
import com.steve.mcpodface.download.DownloadManager
import com.steve.mcpodface.playback.NoOpPlayerController
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PolicyRunnerTest {
    private lateinit var db: AppDatabase
    private val downloads = mockk<DownloadManager>(relaxed = true)
    private val day = 86_400_000L
    private val now = 20_000 * day
    private var podcastId = 0L

    private val settings = object : SettingsRepository {
        val state = MutableStateFlow(GlobalSettings())
        override val settings = state
        override suspend fun update(transform: (GlobalSettings) -> GlobalSettings) =
            transform(state.value).also { state.value = it }
    }

    private fun runner() = PolicyRunner(db, settings, downloads, NoOpPlayerController(), Clock { now })

    @Before fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        podcastId = db.podcastDao().insert(PodcastEntity(feedUrl = "https://x/feed", title = "X", isSubscribed = true))
        // Ten weekly episodes: g1 oldest (63 days), g10 newest (today).
        db.episodeDao().insertAll(
            (1..10).map {
                EpisodeEntity(
                    podcastId = podcastId, guid = "g$it", title = "E$it", pubDate = now - (10 - it) * 7 * day,
                    enclosureUrl = "https://x/$it.mp3", firstSeenAt = 0,
                )
            },
        )
    }

    @After fun tearDown() = db.close()

    private suspend fun idOf(n: Int) = db.episodeDao().getForPodcast(podcastId).first { it.guid == "g$n" }.id

    @Test fun downloadPolicyEnqueuesTheRecentWindowAsAuto() = runTest {
        assertThat(runner().runDownloadPolicy(podcastId)).isEqualTo(5)
        for (n in 6..10) {
            val id = idOf(n)
            coVerify { downloads.enqueue(id, DownloadSource.AUTO) }
        }
        val old = idOf(5)
        coVerify(exactly = 0) { downloads.enqueue(old, any()) }
    }

    @Test fun unsubscribedPodcastDownloadsNothing() = runTest {
        db.podcastDao().update(db.podcastDao().getById(podcastId)!!.copy(isSubscribed = false))
        assertThat(runner().runDownloadPolicy(podcastId)).isEqualTo(0)
    }

    @Test fun cleanupDeletesEpisodePlayedMoreThanADayAgo() = runTest {
        val id = idOf(10)
        db.episodeDao().setPlayState(id, PlayState.PLAYED, playedAt = now - 25 * 3_600_000L, positionMs = null)
        db.downloadDao().upsert(DownloadEntity(id, DownloadState.COMPLETED, DownloadSource.AUTO, filePath = "/x", downloadedAt = now - day))
        val deletions = runner().runCleanup()
        assertThat(deletions.map { it.episodeId }).containsExactly(id)
        coVerify { downloads.delete(id) }
    }

    @Test fun previewCountsWhatLoweringKeepWouldRemoveWithoutDeleting() = runTest {
        for (n in 6..10) {
            db.downloadDao().upsert(
                DownloadEntity(idOf(n), DownloadState.COMPLETED, DownloadSource.AUTO, filePath = "/x", totalBytes = 1_000, downloadedAt = now),
            )
        }
        val (count, bytes) = runner().previewCleanup { it.copy(keepLatestCount = 2) }
        assertThat(count).isEqualTo(3)
        assertThat(bytes).isEqualTo(3_000)
        coVerify(exactly = 0) { downloads.delete(any()) }
    }
}
