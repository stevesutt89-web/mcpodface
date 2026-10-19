package com.steve.mcpodface.ui.podcast

import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.steve.mcpodface.core.Clock
import com.steve.mcpodface.data.db.AppDatabase
import com.steve.mcpodface.data.db.EpisodeEntity
import com.steve.mcpodface.data.db.FeedType
import com.steve.mcpodface.data.db.PlayState
import com.steve.mcpodface.data.db.PodcastEntity
import com.steve.mcpodface.data.db.PodcastSettingsEntity
import com.steve.mcpodface.data.db.QueueAdd
import com.steve.mcpodface.data.db.SortOrder
import com.steve.mcpodface.data.episodes.EpisodeRepository
import com.steve.mcpodface.data.feed.FeedRepository
import com.steve.mcpodface.data.feed.SubscriptionRepository
import com.steve.mcpodface.playback.NowPlaying
import com.steve.mcpodface.playback.PlayerController
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PodcastViewModelTest {
    private lateinit var db: AppDatabase
    private lateinit var observerScope: CoroutineScope
    private val subscriptions = mockk<SubscriptionRepository>(relaxed = true)
    private val feeds = mockk<FeedRepository>(relaxed = true)
    private val player = FakePlayerController()
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

    private fun viewModel(podcastId: Long): Observed {
        val vm = PodcastViewModel(
            savedStateHandle = SavedStateHandle(mapOf("podcastId" to podcastId)),
            episodeRepository = EpisodeRepository(db.episodeDao(), db.podcastDao(), clock),
            subscriptionRepository = subscriptions,
            feedRepository = feeds,
            podcastSettingsDao = db.podcastSettingsDao(),
            playerController = player,
            clock = clock,
        )
        return Observed(vm, vm.uiState)
    }

    private suspend fun insertPodcast(
        feedType: FeedType = FeedType.EPISODIC,
        subscribed: Boolean = true,
    ): Long = db.podcastDao().insert(
        PodcastEntity(feedUrl = "https://example.com/feed", title = "Show", feedType = feedType, isSubscribed = subscribed),
    )

    private suspend fun insertEpisodes(podcastId: Long, vararg episodes: Pair<String, Long>, played: Set<String> = emptySet()) {
        db.episodeDao().insertAll(
            episodes.map { (guid, pubDate) ->
                EpisodeEntity(
                    podcastId = podcastId,
                    guid = guid,
                    title = guid,
                    pubDate = pubDate,
                    enclosureUrl = "https://example.com/$guid.mp3",
                    firstSeenAt = 0,
                    playState = if (guid in played) PlayState.PLAYED else PlayState.UNPLAYED,
                )
            },
        )
    }

    @Test fun episodicPodcastDefaultsToNewestFirst() = runBlocking {
        val podcastId = insertPodcast(FeedType.EPISODIC)
        insertEpisodes(podcastId, "old" to 1_000L, "new" to 3_000L, "mid" to 2_000L)

        val state = viewModel(podcastId).await { it.loaded && it.episodes.size == 3 }

        assertThat(state.sortOrder).isEqualTo(SortOrder.NEWEST_FIRST)
        assertThat(state.episodes.map { it.episode.guid }).containsExactly("new", "mid", "old").inOrder()
    }

    @Test fun serialPodcastDefaultsToOldestFirst() = runBlocking {
        val podcastId = insertPodcast(FeedType.SERIAL)
        insertEpisodes(podcastId, "old" to 1_000L, "new" to 3_000L, "mid" to 2_000L)

        val state = viewModel(podcastId).await { it.loaded && it.episodes.size == 3 }

        assertThat(state.sortOrder).isEqualTo(SortOrder.OLDEST_FIRST)
        assertThat(state.episodes.map { it.episode.guid }).containsExactly("old", "mid", "new").inOrder()
    }

    @Test fun toggleSortSavesOverrideAndKeepsOtherSettingsFields() = runBlocking {
        val podcastId = insertPodcast(FeedType.EPISODIC)
        insertEpisodes(podcastId, "a" to 1_000L, "b" to 2_000L)
        val original = PodcastSettingsEntity(
            podcastId = podcastId,
            autoDownload = false,
            keepLatestCount = 3,
            deletePlayed = false,
            maxAgeDays = 9,
            allowCellular = true,
            sortOrder = null,
            playbackSpeed = 1.5f,
            skipIntroSec = 10,
            skipOutroSec = 20,
            notifyNewEpisodes = false,
            autoAddToQueue = QueueAdd.TOP,
            backfillEnabled = false,
        )
        db.podcastSettingsDao().upsert(original)
        val vm = viewModel(podcastId)
        vm.await { it.sortOrder == SortOrder.NEWEST_FIRST && it.episodes.size == 2 }

        vm.vm.toggleSortOrder()

        vm.await { it.sortOrder == SortOrder.OLDEST_FIRST }
        val saved = db.podcastSettingsDao().get(podcastId)
        assertThat(saved).isEqualTo(original.copy(sortOrder = SortOrder.OLDEST_FIRST))
    }

    @Test fun toggleSortCreatesSettingsRowWhenMissing() = runBlocking {
        val podcastId = insertPodcast(FeedType.SERIAL)
        insertEpisodes(podcastId, "a" to 1_000L)
        assertThat(db.podcastSettingsDao().get(podcastId)).isNull()
        val vm = viewModel(podcastId)
        vm.await { it.loaded && it.sortOrder == SortOrder.OLDEST_FIRST }

        vm.vm.toggleSortOrder()

        vm.await { it.sortOrder == SortOrder.NEWEST_FIRST }
        val saved = db.podcastSettingsDao().get(podcastId)
        assertThat(saved).isEqualTo(PodcastSettingsEntity(podcastId = podcastId, sortOrder = SortOrder.NEWEST_FIRST))
    }

    @Test fun explicitSortOverrideWinsOverSerialDefault() = runBlocking {
        val podcastId = insertPodcast(FeedType.SERIAL)
        insertEpisodes(podcastId, "a" to 1_000L, "b" to 2_000L)
        db.podcastSettingsDao().upsert(PodcastSettingsEntity(podcastId = podcastId, sortOrder = SortOrder.NEWEST_FIRST))

        val state = viewModel(podcastId).await { it.loaded && it.episodes.size == 2 }

        assertThat(state.sortOrder).isEqualTo(SortOrder.NEWEST_FIRST)
        assertThat(state.episodes.map { it.episode.guid }).containsExactly("b", "a").inOrder()
    }

    @Test fun hidePlayedFiltersListButKeepsEpisodeCount() = runBlocking {
        val podcastId = insertPodcast()
        insertEpisodes(podcastId, "a" to 1_000L, "b" to 2_000L, "c" to 3_000L, played = setOf("b"))
        val vm = viewModel(podcastId)
        vm.await { it.episodes.size == 3 }

        vm.vm.onHidePlayedChange(true)

        val state = vm.await { it.hidePlayed && it.episodes.size == 2 }
        assertThat(state.episodes.map { it.episode.guid }).containsExactly("c", "a").inOrder()
        assertThat(state.episodeCount).isEqualTo(3)
    }

    @Test fun subscribedPodcastReportsSubscribed() = runBlocking {
        val podcastId = insertPodcast(subscribed = true)
        val state = viewModel(podcastId).await { it.loaded }
        assertThat(state.isSubscribed).isTrue()
        assertThat(state.podcast?.isSubscribed).isTrue()
    }

    @Test fun unsubscribedPreviewReportsNotSubscribed() = runBlocking {
        val podcastId = insertPodcast(subscribed = false)
        val state = viewModel(podcastId).await { it.loaded }
        assertThat(state.isSubscribed).isFalse()
    }

    @Test fun missingPodcastIsNotFound() = runBlocking {
        val state = viewModel(podcastId = 999L).await { it.loaded }
        assertThat(state.podcast).isNull()
        assertThat(state.notFound).isTrue()
        assertThat(PodcastUiState().isLoading).isTrue()
    }

    @Test fun unsubscribeDelegatesWithDeleteDownloadsFlag() = runBlocking {
        val podcastId = insertPodcast(subscribed = true)
        val vm = viewModel(podcastId)
        vm.await { it.loaded }

        vm.vm.unsubscribe(deleteDownloads = true)

        coVerify { subscriptions.unsubscribe(podcastId, true) }
    }

    @Test fun refreshCallsFeedRepository() = runBlocking {
        val podcastId = insertPodcast()
        val vm = viewModel(podcastId)
        vm.await { it.loaded }

        vm.vm.refresh()

        coVerify { feeds.refresh(podcastId, any()) }
    }

    @Test fun nowPlayingEpisodeIsFlaggedInState() = runBlocking {
        val podcastId = insertPodcast()
        insertEpisodes(podcastId, "a" to 1_000L)
        val episodeId = db.episodeDao().getForPodcast(podcastId).single().id
        val vm = viewModel(podcastId)
        vm.await { it.episodes.size == 1 }

        player.nowPlaying.value = NowPlaying(episodeId = episodeId, isPlaying = true, positionMs = 0, durationMs = 0)

        val state = vm.await { it.isPlaying }
        assertThat(state.nowPlayingEpisodeId).isEqualTo(episodeId)
    }

    @Test fun effectiveSortOrderDefaultsByFeedTypeAndToggleFlips() {
        assertThat(effectiveSortOrder(null, FeedType.SERIAL)).isEqualTo(SortOrder.OLDEST_FIRST)
        assertThat(effectiveSortOrder(null, FeedType.EPISODIC)).isEqualTo(SortOrder.NEWEST_FIRST)
        assertThat(effectiveSortOrder(null, null)).isEqualTo(SortOrder.NEWEST_FIRST)
        assertThat(effectiveSortOrder(PodcastSettingsEntity(1L, sortOrder = SortOrder.NEWEST_FIRST), FeedType.SERIAL))
            .isEqualTo(SortOrder.NEWEST_FIRST)
        assertThat(SortOrder.NEWEST_FIRST.toggled()).isEqualTo(SortOrder.OLDEST_FIRST)
        assertThat(SortOrder.OLDEST_FIRST.toggled()).isEqualTo(SortOrder.NEWEST_FIRST)
    }

    /** Keeps one collector alive on the ViewModel's state and lets tests wait for a condition on it. */
    private inner class Observed(val vm: PodcastViewModel, flow: StateFlow<PodcastUiState>) {
        @Volatile private var latest = PodcastUiState()

        init {
            flow.onEach { latest = it }.launchIn(observerScope)
        }

        suspend fun await(predicate: (PodcastUiState) -> Boolean): PodcastUiState = withTimeout(5_000) {
            while (!predicate(latest)) delay(10)
            latest
        }
    }

    private class FakePlayerController : PlayerController {
        override val nowPlaying = MutableStateFlow<NowPlaying?>(null)
        override fun play(episodeId: Long) = Unit
        override fun togglePlayPause(episodeId: Long) = Unit
        override fun pause() = Unit
        override fun seekTo(positionMs: Long) = Unit
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
    }
}
