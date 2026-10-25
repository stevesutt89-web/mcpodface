package com.steve.mcpodface.playback.auto

import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
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
import com.steve.mcpodface.data.episodes.EpisodeRepository
import com.steve.mcpodface.data.queue.QueueRepository
import com.steve.mcpodface.download.DownloadManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BrowseActionsTest {
    private lateinit var db: AppDatabase
    private lateinit var queue: QueueRepository
    private lateinit var downloads: DownloadManager
    private lateinit var actions: BrowseActions
    private val now = 20_000_000L
    private var episodeId = 0L

    @Before fun setUp() = runBlocking<Unit> {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        queue = QueueRepository(db)
        downloads = mockk(relaxed = true)
        val episodes = EpisodeRepository(db.episodeDao(), db.podcastDao(), Clock { now })
        actions = BrowseActions(db, queue, downloads, episodes)

        val podcastId = db.podcastDao().insert(PodcastEntity(feedUrl = "https://n/feed", title = "News", isSubscribed = true))
        episodeId = db.episodeDao().insertAll(
            listOf(EpisodeEntity(podcastId = podcastId, guid = "e1", title = "Ep 1", pubDate = now, enclosureUrl = "https://cdn/e1.mp3", firstSeenAt = 0)),
        ).single()
    }

    @After fun tearDown() = db.close()

    private fun act(action: String, id: Long = episodeId) = runBlocking { actions.run(action, id) }

    private fun episode() = runBlocking { db.episodeDao().getById(episodeId)!! }

    private fun queued() = runBlocking { db.queueDao().getAll().map { it.episodeId } }

    private fun setDownloadState(state: DownloadState) = runBlocking {
        db.downloadDao().upsert(DownloadEntity(episodeId, state, DownloadSource.MANUAL))
    }

    @Test fun unknownActionIsNotSupported() {
        val result = act("com.example.NOPE")
        assertThat(result.resultCode).isEqualTo(SessionError.ERROR_NOT_SUPPORTED)
    }

    @Test fun missingEpisodeIsBadValueForEveryAction() {
        for (action in AutoCommands.ROW_ACTIONS) {
            assertThat(act(action, id = 999_999L).resultCode).isEqualTo(SessionError.ERROR_BAD_VALUE)
        }
    }

    @Test fun upNextAddsThenRemoves() {
        assertThat(act(AutoCommands.ROW_UP_NEXT).resultCode).isEqualTo(SessionResult.RESULT_SUCCESS)
        assertThat(queued()).containsExactly(episodeId)

        assertThat(act(AutoCommands.ROW_UP_NEXT).resultCode).isEqualTo(SessionResult.RESULT_SUCCESS)
        assertThat(queued()).isEmpty()
    }

    @Test fun starTogglesIsStarred() {
        assertThat(act(AutoCommands.ROW_STAR).resultCode).isEqualTo(SessionResult.RESULT_SUCCESS)
        assertThat(episode().isStarred).isTrue()

        assertThat(act(AutoCommands.ROW_STAR).resultCode).isEqualTo(SessionResult.RESULT_SUCCESS)
        assertThat(episode().isStarred).isFalse()
    }

    @Test fun markPlayedThenUnplayed() {
        assertThat(act(AutoCommands.ROW_MARK_PLAYED).resultCode).isEqualTo(SessionResult.RESULT_SUCCESS)
        assertThat(episode().playState).isEqualTo(PlayState.PLAYED)
        assertThat(episode().playedAt).isEqualTo(now)

        assertThat(act(AutoCommands.ROW_MARK_PLAYED).resultCode).isEqualTo(SessionResult.RESULT_SUCCESS)
        assertThat(episode().playState).isEqualTo(PlayState.UNPLAYED)
        assertThat(episode().playedAt).isNull()
    }

    @Test fun downloadStartsManualDownloadWhenNoRow() {
        assertThat(act(AutoCommands.ROW_DOWNLOAD).resultCode).isEqualTo(SessionResult.RESULT_SUCCESS)
        coVerify(exactly = 1) { downloads.enqueue(episodeId, DownloadSource.MANUAL, null) }
        coVerify(exactly = 0) { downloads.cancel(any()) }
        coVerify(exactly = 0) { downloads.delete(any(), any()) }
    }

    @Test fun downloadOnMobileDataQueuesForWifiInsteadOfPrompting() {
        coEvery { downloads.needsCellularPrompt(episodeId) } returns true
        assertThat(act(AutoCommands.ROW_DOWNLOAD).resultCode).isEqualTo(SessionResult.RESULT_SUCCESS)
        coVerify(exactly = 1) { downloads.enqueue(episodeId, DownloadSource.MANUAL, false) }
    }

    @Test fun downloadedEpisodeIsRemoved() {
        setDownloadState(DownloadState.COMPLETED)
        assertThat(act(AutoCommands.ROW_DOWNLOAD).resultCode).isEqualTo(SessionResult.RESULT_SUCCESS)
        coVerify(exactly = 1) { downloads.delete(episodeId) }
        coVerify(exactly = 0) { downloads.enqueue(any(), any(), any()) }
    }

    @Test fun downloadingEpisodeIsCancelled() {
        setDownloadState(DownloadState.DOWNLOADING)
        assertThat(act(AutoCommands.ROW_DOWNLOAD).resultCode).isEqualTo(SessionResult.RESULT_SUCCESS)
        coVerify(exactly = 1) { downloads.cancel(episodeId) }
        coVerify(exactly = 0) { downloads.delete(any(), any()) }
    }

    @Test fun failedDownloadIsRequeued() {
        setDownloadState(DownloadState.FAILED)
        assertThat(act(AutoCommands.ROW_DOWNLOAD).resultCode).isEqualTo(SessionResult.RESULT_SUCCESS)
        coVerify(exactly = 1) { downloads.enqueue(episodeId, DownloadSource.MANUAL, null) }
    }
}
