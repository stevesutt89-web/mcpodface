package com.steve.mcpodface.ui.playlist

import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.steve.mcpodface.core.Clock
import com.steve.mcpodface.data.db.AppDatabase
import com.steve.mcpodface.data.db.EpisodeEntity
import com.steve.mcpodface.data.db.PlaylistKind
import com.steve.mcpodface.data.db.PodcastEntity
import com.steve.mcpodface.data.playlist.PlaylistRepository
import com.steve.mcpodface.data.playlist.SmartRules
import com.steve.mcpodface.data.queue.QueueRepository
import com.steve.mcpodface.data.search.SearchRepository
import com.steve.mcpodface.playback.NoOpPlayerController
import com.steve.mcpodface.ui.library.awaitValue
import com.steve.mcpodface.ui.library.eventually
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaylistViewModelTest {
    private lateinit var db: AppDatabase
    private lateinit var playlists: PlaylistRepository
    private lateinit var queue: QueueRepository
    private lateinit var player: RecordingPlayer
    private var now = 20_000L * DAY
    private var showId = 0L
    private val ids = mutableMapOf<String, Long>()

    @Before fun setUp() = runBlocking<Unit> {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val clock = Clock { now++ }
        playlists = PlaylistRepository(db, SearchRepository(db, clock, Dispatchers.Unconfined), clock)
        queue = QueueRepository(db)
        player = RecordingPlayer()
        showId = db.podcastDao().insert(PodcastEntity(feedUrl = "https://a/feed", title = "Show", isSubscribed = true))
        for (guid in listOf("a", "b", "c")) episode(guid)
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    private suspend fun episode(guid: String) {
        ids[guid] = db.episodeDao().insertAll(
            listOf(
                EpisodeEntity(
                    podcastId = showId, guid = guid, title = "Episode $guid", pubDate = now - DAY,
                    enclosureUrl = "https://cdn/$guid.mp3", firstSeenAt = 0,
                ),
            ),
        ).single()
    }

    private fun viewModel(playlistId: Long) = PlaylistViewModel(
        savedStateHandle = SavedStateHandle(mapOf("playlistId" to playlistId)),
        playlists = playlists,
        queue = queue,
        player = player,
        clock = Clock { now },
    )

    /** A manual playlist holding [guids] in order. */
    private suspend fun manual(vararg guids: String): Long {
        val id = playlists.createManual("Mine")
        guids.forEach { playlists.add(id, ids.getValue(it)) }
        return id
    }

    private suspend fun queueIds() = queue.observeIds().first()

    @Test fun playAllReplacesEmptyQueueInOrderAndPlaysFirst() = runTest {
        val id = manual("b", "a", "c")
        val vm = viewModel(id)
        vm.state.awaitValue { it.items.size == 3 }

        vm.playAll()

        eventually { queueIds() == listOf(ids["b"], ids["a"], ids["c"]) }
        assertThat(vm.state.value.pendingReplace).isNull()
        assertThat(player.played).containsExactly(ids["b"])
    }

    @Test fun playAllWithUpNextNotEmptyAsksFirstAndLeavesQueueAlone() = runTest {
        queue.addLast(ids.getValue("c"))
        val id = manual("a", "b")
        val vm = viewModel(id)
        vm.state.awaitValue { it.items.size == 2 }

        vm.playAll()

        assertThat(vm.state.awaitValue { it.pendingReplace != null }.pendingReplace).isEqualTo(ReplaceAction.PLAY_ALL)
        assertThat(queueIds()).containsExactly(ids["c"])
        assertThat(player.played).isEmpty()
    }

    @Test fun confirmingReplaceRunsTheDeferredPlayAll() = runTest {
        queue.addLast(ids.getValue("c"))
        val id = manual("a", "b")
        val vm = viewModel(id)
        vm.state.awaitValue { it.items.size == 2 }
        vm.playAll()
        vm.state.awaitValue { it.pendingReplace != null }

        vm.confirmReplace()

        eventually { queueIds() == listOf(ids["a"], ids["b"]) }
        assertThat(vm.state.awaitValue { it.pendingReplace == null }.pendingReplace).isNull()
        assertThat(player.played).containsExactly(ids["a"])
    }

    @Test fun dismissingTheConfirmKeepsUpNext() = runTest {
        queue.addLast(ids.getValue("c"))
        val id = manual("a")
        val vm = viewModel(id)
        vm.state.awaitValue { it.items.size == 1 }
        vm.shuffle()
        vm.state.awaitValue { it.pendingReplace == ReplaceAction.SHUFFLE }

        vm.dismissReplace()

        assertThat(vm.state.awaitValue { it.pendingReplace == null }.pendingReplace).isNull()
        assertThat(queueIds()).containsExactly(ids["c"])
        assertThat(player.played).isEmpty()
    }

    @Test fun shuffleReplacesQueueWithEveryEpisodeAndPlaysTheFirstOfTheShuffle() = runTest {
        val id = manual("a", "b", "c")
        val vm = viewModel(id)
        vm.state.awaitValue { it.items.size == 3 }

        vm.shuffle()

        eventually { queueIds().size == 3 }
        val shuffled = queueIds()
        assertThat(shuffled).containsExactly(ids["a"], ids["b"], ids["c"])
        assertThat(player.played).containsExactly(shuffled.first())
    }

    @Test fun removeDropsTheEpisodeFromThePlaylist() = runTest {
        val id = manual("a", "b", "c")
        val vm = viewModel(id)
        vm.state.awaitValue { it.items.size == 3 }

        vm.remove(ids.getValue("b"))

        eventually { playlists.episodeIds(id) == listOf(ids["a"], ids["c"]) }
        assertThat(vm.state.awaitValue { it.items.size == 2 }.items.map { it.episode.id })
            .containsExactly(ids["a"], ids["c"]).inOrder()
    }

    @Test fun dragReorderPersistsTheMove() = runTest {
        val id = manual("a", "b", "c")
        val vm = viewModel(id)
        // Keep the state hot so the drag handlers read the current list.
        backgroundScope.launch { vm.state.collect {} }
        vm.state.awaitValue { it.items.size == 3 }
        val expected = listOf(ids["b"], ids["c"], ids["a"])

        vm.onDragStarted(ids.getValue("a"))
        vm.onDragMove(ids.getValue("a"), ids.getValue("c"))
        vm.onDragStopped()

        eventually { playlists.episodeIds(id) == expected }
        vm.state.awaitValue { state -> state.items.map { it.episode.id } == expected }
    }

    @Test fun manualPlaylistsAreEditableAndSmartOnesAreNot() = runTest {
        val manualVm = viewModel(manual("a"))
        val smartVm = viewModel(playlists.createSmart("Smart", SmartRules()))

        assertThat(manualVm.state.awaitValue { it.loaded }.isEditable).isTrue()
        assertThat(smartVm.state.awaitValue { it.loaded }.isEditable).isFalse()
    }

    @Test fun renameChangesTheName() = runTest {
        val id = manual("a")
        val vm = viewModel(id)
        vm.state.awaitValue { it.loaded }

        vm.rename("Road trip")

        eventually { playlists.observePlaylist(id).first()?.name == "Road trip" }
    }

    @Test fun deleteRemovesThePlaylistAndSignalsTheScreenToLeave() = runTest {
        val id = manual("a")
        val vm = viewModel(id)
        vm.state.awaitValue { it.loaded }

        vm.delete()

        // Room runs on real threads, so wait in real time rather than on the test's virtual clock.
        val event = withContext(Dispatchers.Default) { withTimeout(5_000) { vm.events.first() } }
        assertThat(event).isEqualTo(PlaylistEvent.Deleted)
        assertThat(playlists.observePlaylist(id).first()).isNull()
    }

    private class RecordingPlayer : NoOpPlayerController() {
        val played = mutableListOf<Long>()
        override fun play(episodeId: Long, startPositionMs: Long?) {
            played += episodeId
        }
    }

    private companion object {
        const val DAY = 86_400_000L
    }
}
