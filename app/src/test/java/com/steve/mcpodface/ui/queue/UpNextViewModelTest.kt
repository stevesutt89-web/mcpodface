package com.steve.mcpodface.ui.queue

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.steve.mcpodface.core.Clock
import com.steve.mcpodface.data.db.AppDatabase
import com.steve.mcpodface.data.db.EpisodeEntity
import com.steve.mcpodface.data.db.EpisodeListItem
import com.steve.mcpodface.data.db.PlayState
import com.steve.mcpodface.data.db.PodcastEntity
import com.steve.mcpodface.data.queue.QueueRepository
import com.steve.mcpodface.playback.NoOpPlayerController
import com.steve.mcpodface.playback.NowPlaying
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
private const val FEED = "https://example.com/feed"

class UpNextDecisionsTest {
    private fun item(id: Long, durationSec: Int?, state: PlayState = PlayState.UNPLAYED, positionMs: Long = 0L) =
        EpisodeListItem(
            episode = EpisodeEntity(
                id = id, podcastId = 1L, guid = "g$id", title = "E$id", pubDate = 0L,
                durationSec = durationSec, enclosureUrl = "https://x/$id.mp3", firstSeenAt = 0L,
                playState = state, positionMs = positionMs,
            ),
            podcastTitle = "Show",
            podcastArtworkUrl = null,
            downloadState = null,
            downloadBytes = null,
            inQueue = true,
        )

    @Test fun summaryTotalsRemainingTimeOfEachEpisode() {
        val items = (1L..5L).map { item(it, durationSec = 40 * 60) }

        assertThat(upNextSummary(items)).isEqualTo("5 episodes · 3 h 20 min")
    }

    @Test fun summaryUsesSingularAndOmitsUnknownTime() {
        assertThat(upNextSummary(listOf(item(1L, durationSec = 3_600)))).isEqualTo("1 episode · 1 h")
        assertThat(upNextSummary(listOf(item(1L, durationSec = null)))).isEqualTo("1 episode")
    }

    @Test fun remainingTimeSubtractsProgressAndIgnoresPlayed() {
        assertThat(remainingSeconds(item(1L, durationSec = 600, state = PlayState.IN_PROGRESS, positionMs = 120_000L)))
            .isEqualTo(480)
        assertThat(remainingSeconds(item(2L, durationSec = 600, state = PlayState.PLAYED))).isEqualTo(0)
        assertThat(remainingSeconds(item(3L, durationSec = 600, state = PlayState.IN_PROGRESS, positionMs = 900_000L)))
            .isEqualTo(0)
    }

    @Test fun reorderMovesDraggedIdToTargetIndexInBothDirections() {
        assertThat(reorderedIds(listOf(1L, 2L, 3L, 4L), fromId = 1L, toId = 3L)).containsExactly(2L, 3L, 1L, 4L).inOrder()
        assertThat(reorderedIds(listOf(1L, 2L, 3L, 4L), fromId = 4L, toId = 2L)).containsExactly(1L, 4L, 2L, 3L).inOrder()
        assertThat(reorderedIds(listOf(1L, 2L), fromId = 9L, toId = 2L)).containsExactly(1L, 2L).inOrder()
    }

    @Test fun applyOrderDropsMissingIdsAndKeepsUnmentionedRowsAtEnd() {
        val db = listOf(item(1L, 60), item(2L, 60), item(3L, 60))

        val ordered = applyOrder(db, listOf(3L, 9L, 1L))

        assertThat(ordered.map { it.episode.id }).containsExactly(3L, 1L, 2L).inOrder()
        assertThat(applyOrder(db, null)).isSameInstanceAs(db)
    }
}

@RunWith(AndroidJUnit4::class)
class UpNextViewModelTest {
    private lateinit var db: AppDatabase
    private lateinit var queue: QueueRepository
    private lateinit var player: RecordingPlayer
    private val clock = Clock { NOW }

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        queue = QueueRepository(db)
        player = RecordingPlayer()
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    private fun viewModel() = UpNextViewModel(
        queue = queue,
        queueDao = db.queueDao(),
        episodes = db.episodeDao(),
        podcasts = db.podcastDao(),
        player = player,
        clock = clock,
    )

    /** Inserts episodes 1..n (titles E1..En) into the queue in order and returns their ids. */
    private suspend fun seedQueue(n: Int): List<Long> {
        val podcastId = db.podcastDao().getByFeedUrl(FEED)?.id
            ?: db.podcastDao().insert(PodcastEntity(feedUrl = FEED, title = "Show", isSubscribed = true))
        val ids = (1..n).map { i ->
            db.episodeDao().insertAll(
                listOf(
                    EpisodeEntity(
                        podcastId = podcastId, guid = "g$i", title = "E$i", pubDate = NOW - i,
                        durationSec = 600, enclosureUrl = "https://example.com/$i.mp3", firstSeenAt = 0,
                    ),
                ),
            ).single()
        }
        ids.forEach { queue.addLast(it) }
        return ids
    }

    /** Room and the ViewModel's stateIn work on other threads, so poll in real time. */
    private suspend fun eventually(condition: suspend () -> Boolean) = withContext(Dispatchers.Default) {
        withTimeout(5_000) { while (!condition()) delay(20) }
    }

    private suspend fun <T> StateFlow<T>.awaitValue(predicate: (T) -> Boolean): T =
        withContext(Dispatchers.Default) { withTimeout(5_000) { first(predicate) } }

    private suspend fun queueIds(): List<Long> = db.queueDao().getAll().map { it.episodeId }

    @Test fun dragReorderPersistsNewPositions() = runTest {
        val (a, b, c) = seedQueue(3)
        val vm = viewModel()
        backgroundScope.launchCollector(vm)
        vm.state.awaitValue { it.items.size == 3 }

        vm.onDragStarted(a)
        vm.onDragMove(a, b)
        vm.onDragMove(a, c)
        val during = vm.state.awaitValue { it.items.map { i -> i.episode.id } == listOf(b, c, a) }
        assertThat(during.items.map { it.episode.id }).containsExactly(b, c, a).inOrder()

        vm.onDragStopped()

        eventually { queueIds() == listOf(b, c, a) }
        assertThat(queueIds()).containsExactly(b, c, a).inOrder()
        assertThat(db.queueDao().getAll().map { it.position }).containsExactly(0, 1, 2).inOrder()
    }

    @Test fun removeThenUndoRestoresOriginalIndex() = runTest {
        val (a, b, c) = seedQueue(3)
        val vm = viewModel()
        backgroundScope.launchCollector(vm)
        vm.state.awaitValue { it.items.size == 3 }

        vm.remove(b)

        eventually { queueIds() == listOf(a, c) }
        val message = vm.messages.first() as UpNextMessage.Removed
        assertThat(message).isEqualTo(UpNextMessage.Removed(episodeId = b, index = 1))

        vm.undoRemove(message.episodeId, message.index)

        eventually { queueIds() == listOf(a, b, c) }
        assertThat(queueIds()).containsExactly(a, b, c).inOrder()
    }

    @Test fun clearQueueEmptiesTheQueue() = runTest {
        seedQueue(2)
        val vm = viewModel()
        backgroundScope.launchCollector(vm)
        vm.state.awaitValue { it.items.size == 2 }

        vm.clearQueue()

        eventually { queueIds().isEmpty() }
        assertThat(vm.state.awaitValue { it.loaded && it.items.isEmpty() }.items).isEmpty()
    }

    @Test fun nowPlayingHeaderShownOnlyWhenPlayingEpisodeIsNotFirst() = runTest {
        val (a, b) = seedQueue(2)
        val vm = viewModel()
        backgroundScope.launchCollector(vm)
        vm.state.awaitValue { it.items.size == 2 }

        player.nowPlaying.value = NowPlaying(episodeId = b, isPlaying = true, positionMs = 0L, durationMs = 600_000L)
        assertThat(vm.state.awaitValue { it.nowPlayingHeader?.episode?.id == b }.nowPlayingHeader?.podcastTitle).isEqualTo("Show")

        player.nowPlaying.value = NowPlaying(episodeId = a, isPlaying = true, positionMs = 0L, durationMs = 600_000L)
        vm.state.awaitValue { it.nowPlayingHeader == null }
    }

    @Test fun playDelegatesToPlayerController() = runTest {
        val vm = viewModel()
        vm.togglePlay(42L)
        assertThat(player.toggled).containsExactly(42L)
    }

    /** Keeps the stateIn subscribed so `state.value` tracks the database during the test. */
    private fun kotlinx.coroutines.CoroutineScope.launchCollector(vm: UpNextViewModel) {
        launch { vm.state.collect {} }
    }

    private class RecordingPlayer : NoOpPlayerController() {
        val toggled = mutableListOf<Long>()
        override val nowPlaying: MutableStateFlow<NowPlaying?> = MutableStateFlow(null)
        override fun togglePlayPause(episodeId: Long) { toggled += episodeId }
    }
}
