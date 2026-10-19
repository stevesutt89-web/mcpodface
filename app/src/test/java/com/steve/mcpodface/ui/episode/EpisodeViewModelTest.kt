package com.steve.mcpodface.ui.episode

import androidx.lifecycle.SavedStateHandle
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
import com.steve.mcpodface.playback.NoOpPlayerController
import com.steve.mcpodface.playback.NowPlaying
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EpisodeViewModelTest {
    private lateinit var db: AppDatabase
    private lateinit var observerScope: CoroutineScope
    private val player = RecordingPlayer()
    private val downloads: DownloadManager = mockk(relaxed = true)
    private val clock = Clock { NOW }

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        observerScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After fun tearDown() {
        observerScope.cancel()
        db.close()
        Dispatchers.resetMain()
    }

    private fun viewModel(episodeId: Long): Observed {
        val vm = EpisodeViewModel(
            savedStateHandle = SavedStateHandle(mapOf("episodeId" to episodeId)),
            episodeRepository = EpisodeRepository(db.episodeDao(), db.podcastDao(), clock),
            queueRepository = QueueRepository(db),
            downloadDao = db.downloadDao(),
            downloadManager = downloads,
            playerController = player,
            clock = clock,
        )
        return Observed(vm, vm.uiState)
    }

    private suspend fun insertEpisode(
        playState: PlayState = PlayState.UNPLAYED,
        positionMs: Long = 0L,
        starred: Boolean = false,
    ): Long {
        val podcastId = db.podcastDao().insert(
            PodcastEntity(feedUrl = "https://example.com/feed", title = "Show", isSubscribed = true),
        )
        return db.episodeDao().insertAll(
            listOf(
                EpisodeEntity(
                    podcastId = podcastId,
                    guid = "ep",
                    title = "Episode",
                    pubDate = NOW - 1_000L,
                    durationSec = 3_000,
                    enclosureUrl = "https://example.com/ep.mp3",
                    firstSeenAt = 0,
                    playState = playState,
                    positionMs = positionMs,
                    isStarred = starred,
                ),
            ),
        ).single()
    }

    @Test fun queueToggleAddsThenRemoves() = runBlocking<Unit> {
        val episodeId = insertEpisode()
        val vm = viewModel(episodeId)
        vm.await { it.loaded }

        vm.vm.toggleQueue()
        vm.await { it.inQueue }
        assertThat(db.queueDao().getAll().map { it.episodeId }).containsExactly(episodeId)

        vm.vm.toggleQueue()
        vm.await { !it.inQueue }
        assertThat(db.queueDao().getAll()).isEmpty()
    }

    @Test fun starToggleFlipsStoredFlag() = runBlocking<Unit> {
        val episodeId = insertEpisode(starred = false)
        val vm = viewModel(episodeId)
        vm.await { it.loaded }

        vm.vm.toggleStarred()
        vm.await { it.isStarred }
        assertThat(db.episodeDao().getById(episodeId)?.isStarred).isTrue()

        vm.vm.toggleStarred()
        vm.await { !it.isStarred }
        assertThat(db.episodeDao().getById(episodeId)?.isStarred).isFalse()
    }

    @Test fun playedToggleMarksPlayedAndBack() = runBlocking<Unit> {
        val episodeId = insertEpisode()
        val vm = viewModel(episodeId)
        vm.await { it.loaded }

        vm.vm.togglePlayed()
        vm.await { it.isPlayed }
        assertThat(db.episodeDao().getById(episodeId)?.playState).isEqualTo(PlayState.PLAYED)

        vm.vm.togglePlayed()
        vm.await { !it.isPlayed }
        assertThat(db.episodeDao().getById(episodeId)?.playState).isEqualTo(PlayState.UNPLAYED)
    }

    @Test fun timestampStartsPlaybackWhenEpisodeIsNotLoaded() = runBlocking<Unit> {
        val episodeId = insertEpisode()
        val vm = viewModel(episodeId)
        vm.await { it.loaded }

        vm.vm.onTimestampClick(754_000L)

        assertThat(player.calls).containsExactly("play $episodeId 754000")
    }

    @Test fun timestampSeeksWhenEpisodeIsPlaying() = runBlocking<Unit> {
        val episodeId = insertEpisode()
        player.nowPlaying.value = NowPlaying(episodeId, isPlaying = true, positionMs = 0, durationMs = 3_000_000)
        val vm = viewModel(episodeId)
        vm.await { it.isPlayingThis }

        vm.vm.onTimestampClick(754_000L)

        assertThat(player.calls).containsExactly("seek 754000")
    }

    @Test fun timestampSeeksAndResumesWhenEpisodeIsPaused() = runBlocking<Unit> {
        val episodeId = insertEpisode()
        player.nowPlaying.value = NowPlaying(episodeId, isPlaying = false, positionMs = 0, durationMs = 3_000_000)
        val vm = viewModel(episodeId)
        vm.await { it.isCurrent }

        vm.vm.onTimestampClick(754_000L)

        assertThat(player.calls).containsExactly("seek 754000", "toggle $episodeId").inOrder()
    }

    @Test fun timestampForADifferentEpisodePlaysThisOne() = runBlocking<Unit> {
        val episodeId = insertEpisode()
        player.nowPlaying.value = NowPlaying(episodeId + 100, isPlaying = true, positionMs = 0, durationMs = 1)
        val vm = viewModel(episodeId)
        vm.await { it.loaded }

        vm.vm.onTimestampClick(5_000L)

        assertThat(player.calls).containsExactly("play $episodeId 5000")
    }

    @Test fun playButtonLabelFollowsPlaybackState() = runBlocking<Unit> {
        val episodeId = insertEpisode(playState = PlayState.IN_PROGRESS, positionMs = 60_000)
        val vm = viewModel(episodeId)
        assertThat(vm.await { it.loaded }.playLabel).isEqualTo("Resume")

        player.nowPlaying.value = NowPlaying(episodeId, isPlaying = true, positionMs = 60_000, durationMs = 3_000_000)
        assertThat(vm.await { it.isPlayingThis }.playLabel).isEqualTo("Pause")
    }

    @Test fun missingEpisodeIsNotFound() = runBlocking<Unit> {
        val state = viewModel(episodeId = 999L).await { it.loaded }
        assertThat(state.episode).isNull()
        assertThat(state.notFound).isTrue()
        assertThat(EpisodeUiState().isLoading).isTrue()
    }

    @Test fun shareTextIsTitleDashPodcastThenUrl() {
        assertThat(buildEpisodeShareText("Bridges", "Sample Show", "https://example.com/a.mp3", positionMs = null))
            .isEqualTo("Bridges — Sample Show\nhttps://example.com/a.mp3")
    }

    @Test fun requestDownloadEnqueuesManualWhenNoPromptIsNeeded() = runBlocking<Unit> {
        val episodeId = insertEpisode()
        coEvery { downloads.needsCellularPrompt(episodeId) } returns false
        val vm = viewModel(episodeId)
        vm.await { it.loaded }

        vm.vm.requestDownload()

        coVerify(exactly = 1) { downloads.enqueue(episodeId, DownloadSource.MANUAL, null) }
        assertThat(vm.await { it.loaded }.showCellularPrompt).isFalse()
    }

    @Test fun requestDownloadShowsPromptInsteadWhenCellularIsNeeded() = runBlocking<Unit> {
        val episodeId = insertEpisode()
        coEvery { downloads.needsCellularPrompt(episodeId) } returns true
        val vm = viewModel(episodeId)
        vm.await { it.loaded }

        vm.vm.requestDownload()

        assertThat(vm.await { it.showCellularPrompt }.showCellularPrompt).isTrue()
        coVerify(exactly = 0) { downloads.enqueue(any(), any(), any()) }
    }

    @Test fun promptDownloadNowEnqueuesWithCellularAllowed() = runBlocking<Unit> {
        val episodeId = insertEpisode()
        coEvery { downloads.needsCellularPrompt(episodeId) } returns true
        val vm = viewModel(episodeId)
        vm.await { it.loaded }
        vm.vm.requestDownload()
        vm.await { it.showCellularPrompt }

        vm.vm.confirmCellularDownload(allowCellular = true)

        coVerify(exactly = 1) { downloads.enqueue(episodeId, DownloadSource.MANUAL, true) }
        assertThat(vm.await { !it.showCellularPrompt }.showCellularPrompt).isFalse()
    }

    @Test fun promptWaitForWifiEnqueuesWithCellularNotAllowed() = runBlocking<Unit> {
        val episodeId = insertEpisode()
        coEvery { downloads.needsCellularPrompt(episodeId) } returns true
        val vm = viewModel(episodeId)
        vm.await { it.loaded }
        vm.vm.requestDownload()
        vm.await { it.showCellularPrompt }

        vm.vm.confirmCellularDownload(allowCellular = false)

        coVerify(exactly = 1) { downloads.enqueue(episodeId, DownloadSource.MANUAL, false) }
        assertThat(vm.await { !it.showCellularPrompt }.showCellularPrompt).isFalse()
    }

    @Test fun dismissingThePromptDownloadsNothing() = runBlocking<Unit> {
        val episodeId = insertEpisode()
        coEvery { downloads.needsCellularPrompt(episodeId) } returns true
        val vm = viewModel(episodeId)
        vm.await { it.loaded }
        vm.vm.requestDownload()
        vm.await { it.showCellularPrompt }

        vm.vm.dismissCellularPrompt()

        assertThat(vm.await { !it.showCellularPrompt }.showCellularPrompt).isFalse()
        coVerify(exactly = 0) { downloads.enqueue(any(), any(), any()) }
    }

    @Test fun removeDownloadCallsDelete() = runBlocking<Unit> {
        val episodeId = insertEpisode()
        val vm = viewModel(episodeId)
        vm.await { it.loaded }

        vm.vm.deleteDownload()

        coVerify(exactly = 1) { downloads.delete(episodeId) }
    }

    @Test fun cancelAndRetryDelegateToDownloadManager() = runBlocking<Unit> {
        val episodeId = insertEpisode()
        val vm = viewModel(episodeId)
        vm.await { it.loaded }

        vm.vm.cancelDownload()
        vm.vm.retryDownload()

        coVerify(exactly = 1) { downloads.cancel(episodeId) }
        coVerify(exactly = 1) { downloads.retry(episodeId) }
    }

    @Test fun downloadRowIsExposedInUiState() = runBlocking<Unit> {
        val episodeId = insertEpisode()
        db.downloadDao().upsert(DownloadEntity(episodeId = episodeId, state = DownloadState.QUEUED, source = DownloadSource.MANUAL))
        val vm = viewModel(episodeId)

        val state = vm.await { it.downloadState == DownloadState.QUEUED }

        assertThat(state.download?.episodeId).isEqualTo(episodeId)
        assertThat(state.isDownloaded).isFalse()
    }

    /** Keeps one collector alive on the ViewModel's state and lets tests wait for a condition on it. */
    private inner class Observed(val vm: EpisodeViewModel, flow: StateFlow<EpisodeUiState>) {
        @Volatile private var latest = EpisodeUiState()

        init {
            flow.onEach { latest = it }.launchIn(observerScope)
        }

        suspend fun await(predicate: (EpisodeUiState) -> Boolean): EpisodeUiState = withTimeout(5_000) {
            while (!predicate(latest)) delay(10)
            latest
        }
    }

    /** Records the playback calls the ViewModel makes; everything else is a no-op. */
    private class RecordingPlayer : NoOpPlayerController() {
        override val nowPlaying = MutableStateFlow<NowPlaying?>(null)
        val calls = mutableListOf<String>()

        override fun play(episodeId: Long, startPositionMs: Long?) {
            calls += "play $episodeId $startPositionMs"
        }

        override fun togglePlayPause(episodeId: Long) {
            calls += "toggle $episodeId"
        }

        override fun seekTo(positionMs: Long) {
            calls += "seek $positionMs"
        }
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
    }
}
