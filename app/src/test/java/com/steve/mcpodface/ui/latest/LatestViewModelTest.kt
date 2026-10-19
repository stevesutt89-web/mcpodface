package com.steve.mcpodface.ui.latest

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
import com.steve.mcpodface.data.feed.FeedRepository
import com.steve.mcpodface.data.prefs.GlobalSettings
import com.steve.mcpodface.data.prefs.LatestFilter
import com.steve.mcpodface.data.prefs.SettingsRepository
import com.steve.mcpodface.data.queue.QueueRepository
import com.steve.mcpodface.playback.NowPlaying
import com.steve.mcpodface.playback.NoOpPlayerController
import com.steve.mcpodface.playback.PlayerController
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

private const val NOW = 1_792_411_200_000L // 2026-10-19T12:00Z
private const val HOUR = 3_600_000L
private const val FEED = "https://example.com/feed"

class LatestDecisionsTest {
    @Test fun filtersMapToObserveLatestArguments() {
        assertThat(latestQueryFor(LatestFilter.ALL)).isEqualTo(LatestQuery(unplayedOnly = false, downloadedOnly = false))
        assertThat(latestQueryFor(LatestFilter.UNPLAYED)).isEqualTo(LatestQuery(unplayedOnly = true, downloadedOnly = false))
        assertThat(latestQueryFor(LatestFilter.DOWNLOADED)).isEqualTo(LatestQuery(unplayedOnly = false, downloadedOnly = true))
    }

    @Test fun swipeLeftTargetIsPlayedUnlessAlreadyPlayed() {
        assertThat(playedToggleTarget(PlayState.UNPLAYED)).isTrue()
        assertThat(playedToggleTarget(PlayState.IN_PROGRESS)).isTrue()
        assertThat(playedToggleTarget(PlayState.PLAYED)).isFalse()
    }
}

@RunWith(AndroidJUnit4::class)
class LatestViewModelTest {
    private lateinit var db: AppDatabase
    private lateinit var settings: FakeSettings
    private lateinit var queue: QueueRepository
    private lateinit var episodes: EpisodeRepository
    private lateinit var feeds: FeedRepository
    private lateinit var player: RecordingPlayer
    private val clock = Clock { NOW }

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        settings = FakeSettings()
        queue = QueueRepository(db)
        episodes = EpisodeRepository(db.episodeDao(), db.podcastDao(), clock)
        feeds = mockk(relaxed = true)
        player = RecordingPlayer()
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    private fun viewModel() = LatestViewModel(episodes, queue, feeds, settings, player, clock)

    private suspend fun seed(
        title: String,
        playState: PlayState = PlayState.UNPLAYED,
        downloaded: Boolean = false,
    ): Long {
        val podcastId = db.podcastDao().getByFeedUrl(FEED)?.id
            ?: db.podcastDao().insert(PodcastEntity(feedUrl = FEED, title = "Show", isSubscribed = true))
        val id = db.episodeDao().insertAll(
            listOf(
                EpisodeEntity(
                    podcastId = podcastId,
                    guid = title,
                    title = title,
                    pubDate = NOW - HOUR,
                    enclosureUrl = "https://example.com/$title.mp3",
                    firstSeenAt = 0,
                    playState = playState,
                ),
            ),
        ).single()
        if (downloaded) {
            db.downloadDao().upsert(DownloadEntity(episodeId = id, state = DownloadState.COMPLETED, source = DownloadSource.MANUAL))
        }
        return id
    }

    /** Room and the ViewModel's stateIn work on other threads, so poll in real time. */
    private suspend fun <T> StateFlow<T>.awaitValue(predicate: (T) -> Boolean): T =
        withContext(Dispatchers.Default) { withTimeout(5_000) { first(predicate) } }

    private suspend fun eventually(condition: suspend () -> Boolean) = withContext(Dispatchers.Default) {
        withTimeout(5_000) { while (!condition()) delay(20) }
    }

    @Test fun filterMapsToObserveLatestArguments() = runTest {
        seed("unplayed")
        seed("played", playState = PlayState.PLAYED)
        seed("downloaded", downloaded = true)
        val vm = viewModel()

        vm.state.awaitValue { it.loaded && it.items.size == 3 }

        vm.setFilter(LatestFilter.UNPLAYED)
        val unplayed = vm.state.awaitValue { it.filter == LatestFilter.UNPLAYED && it.loaded }
        assertThat(unplayed.items.map { it.episode.title }).containsExactly("unplayed", "downloaded")

        vm.setFilter(LatestFilter.DOWNLOADED)
        val downloadedOnly = vm.state.awaitValue { it.filter == LatestFilter.DOWNLOADED && it.loaded }
        assertThat(downloadedOnly.items.map { it.episode.title }).containsExactly("downloaded")
    }

    @Test fun setFilterPersistsToSettings() = runTest {
        val vm = viewModel()
        vm.setFilter(LatestFilter.DOWNLOADED)

        eventually { settings.settings.first().latestFilter == LatestFilter.DOWNLOADED }
        assertThat(settings.settings.first().latestFilter).isEqualTo(LatestFilter.DOWNLOADED)
    }

    @Test fun swipeLeftMarksUnplayedEpisodePlayed() = runTest {
        val id = seed("fresh")
        val vm = viewModel()
        val item = vm.state.awaitValue { it.items.isNotEmpty() }.items.single()

        vm.togglePlayed(item)

        eventually { db.episodeDao().getById(id)?.playState == PlayState.PLAYED }
        val message = vm.messages.first()
        assertThat(message).isEqualTo(LatestMessage.PlayedChanged(episodeId = id, nowPlayed = true))
    }

    @Test fun swipeLeftMarksPlayedEpisodeUnplayed() = runTest {
        val id = seed("done", playState = PlayState.PLAYED)
        val vm = viewModel()
        val item = vm.state.awaitValue { it.items.isNotEmpty() }.items.single()

        vm.togglePlayed(item)

        eventually { db.episodeDao().getById(id)?.playState == PlayState.UNPLAYED }
        assertThat(vm.messages.first()).isEqualTo(LatestMessage.PlayedChanged(episodeId = id, nowPlayed = false))
    }

    @Test fun undoRestoresPreviousPlayedState() = runTest {
        val id = seed("undo me")
        val vm = viewModel()
        val item = vm.state.awaitValue { it.items.isNotEmpty() }.items.single()

        vm.togglePlayed(item)
        eventually { db.episodeDao().getById(id)?.playState == PlayState.PLAYED }
        val message = vm.messages.first() as LatestMessage.PlayedChanged

        vm.undoPlayedChange(message.episodeId, message.nowPlayed)

        eventually { db.episodeDao().getById(id)?.playState == PlayState.UNPLAYED }
        assertThat(db.episodeDao().getById(id)?.playState).isEqualTo(PlayState.UNPLAYED)
    }

    @Test fun swipeRightAddsToUpNextAndEmitsMessage() = runTest {
        val id = seed("queue me")
        val vm = viewModel()
        val item = vm.state.awaitValue { it.items.isNotEmpty() }.items.single()

        vm.addToUpNext(item)

        assertThat(vm.messages.first()).isEqualTo(LatestMessage.AddedToUpNext("queue me"))
        assertThat(queue.observeIds().first()).containsExactly(id)
    }

    @Test fun toggleStarFlipsFlag() = runTest {
        val id = seed("star me")
        val vm = viewModel()
        val item = vm.state.awaitValue { it.items.isNotEmpty() }.items.single()

        vm.toggleStar(item)

        eventually { db.episodeDao().getById(id)?.isStarred == true }
        assertThat(db.episodeDao().getById(id)?.isStarred).isTrue()
    }

    @Test fun playDelegatesToPlayerController() = runTest {
        val vm = viewModel()
        vm.togglePlay(42L)
        assertThat(player.toggled).containsExactly(42L)
    }

    @Test fun refreshCallsRefreshAllAndClearsIndicator() = runTest {
        var calls = 0
        coEvery { feeds.refreshAll() } coAnswers { calls++; emptyList() }
        val vm = viewModel()

        vm.refresh()

        eventually { calls == 1 }
        assertThat(vm.state.awaitValue { !it.refreshing }.refreshing).isFalse()
    }

    private class FakeSettings : SettingsRepository {
        private val state = MutableStateFlow(GlobalSettings())
        override val settings: Flow<GlobalSettings> = state
        override suspend fun update(transform: (GlobalSettings) -> GlobalSettings): GlobalSettings =
            transform(state.value).also { state.value = it }
    }

    private class RecordingPlayer : NoOpPlayerController() {
        val toggled = mutableListOf<Long>()
        override fun togglePlayPause(episodeId: Long) { toggled += episodeId }
    }
}
