package com.steve.mcpodface.ui.playlist

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
import com.steve.mcpodface.data.search.SearchRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

@RunWith(AndroidJUnit4::class)
class AddToPlaylistViewModelTest {
    private lateinit var db: AppDatabase
    private lateinit var playlists: PlaylistRepository
    private lateinit var vm: AddToPlaylistViewModel
    private val clock = Clock { NOW }
    private var episodeId = 0L

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        playlists = PlaylistRepository(db, SearchRepository(db, clock, Dispatchers.Unconfined), clock)
        vm = AddToPlaylistViewModel(playlists)
        kotlinx.coroutines.runBlocking {
            val podcastId = db.podcastDao().insert(PodcastEntity(feedUrl = FEED, title = "Show", isSubscribed = true))
            episodeId = db.episodeDao().insertAll(
                listOf(
                    EpisodeEntity(
                        podcastId = podcastId,
                        guid = "ep",
                        title = "Episode",
                        pubDate = NOW - 3_600_000L,
                        enclosureUrl = "https://example.com/ep.mp3",
                        firstSeenAt = 0,
                    ),
                ),
            ).single()
        }
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    /** Room and stateIn work on other threads, so poll in real time. */
    private suspend fun <T> StateFlow<T>.awaitValue(predicate: (T) -> Boolean): T =
        withContext(Dispatchers.Default) { withTimeout(5_000) { first(predicate) } }

    private suspend fun eventually(condition: suspend () -> Boolean) = withContext(Dispatchers.Default) {
        withTimeout(5_000) { while (!condition()) delay(20) }
    }

    private suspend fun listenLaterId(): Long = playlists.listenLaterId()

    @Test fun listsListenLaterFirstThenManualPlaylistsAndNoSmartOnes() = runTest {
        val manual = playlists.createManual("Morning commute")
        playlists.createSmart("Unplayed", SmartRules())
        vm.bind(episodeId)

        val rows = vm.state.awaitValue { it.loaded }.rows

        assertThat(rows.map { it.name }).containsExactly("Listen Later", "Morning commute").inOrder()
        assertThat(rows.map { it.kind }).containsExactly(PlaylistKind.LISTEN_LATER, PlaylistKind.MANUAL).inOrder()
        assertThat(rows.last().playlistId).isEqualTo(manual)
    }

    @Test fun tickShowsWhereTheEpisodeIsAlready() = runTest {
        val manual = playlists.createManual("Science")
        playlists.add(manual, episodeId)
        vm.bind(episodeId)

        val rows = vm.state.awaitValue { it.loaded && it.rows.any { r -> r.contains } }.rows

        assertThat(rows.single { it.name == "Science" }.contains).isTrue()
        assertThat(rows.single { it.kind == PlaylistKind.LISTEN_LATER }.contains).isFalse()
    }

    @Test fun tappingManualRowAddsThenRemoves() = runTest {
        val manual = playlists.createManual("Science")
        vm.bind(episodeId)
        vm.state.awaitValue { it.loaded }

        vm.toggle(manual)

        eventually { db.playlistDao().getItems(manual).any { it.episodeId == episodeId } }
        assertThat(vm.state.awaitValue { row(it, manual)?.contains == true }).isNotNull()

        vm.toggle(manual)

        eventually { db.playlistDao().getItems(manual).none { it.episodeId == episodeId } }
        assertThat(vm.state.awaitValue { row(it, manual)?.contains == false }).isNotNull()
    }

    @Test fun tappingListenLaterTogglesIt() = runTest {
        val later = listenLaterId()
        vm.bind(episodeId)
        vm.state.awaitValue { it.loaded }

        vm.toggle(later)

        eventually { playlists.observeListenLaterIds().first().contains(episodeId) }
        assertThat(vm.state.awaitValue { row(it, later)?.contains == true }).isNotNull()

        vm.toggle(later)

        eventually { !playlists.observeListenLaterIds().first().contains(episodeId) }
        assertThat(playlists.observeListenLaterIds().first()).doesNotContain(episodeId)
    }

    @Test fun newPlaylistIsCreatedWithTheEpisodeInIt() = runTest {
        vm.bind(episodeId)
        vm.state.awaitValue { it.loaded }

        vm.createPlaylistAndAdd("  Road trip  ")

        eventually { db.playlistDao().getAll().any { it.name == "Road trip" } }
        val created = db.playlistDao().getAll().single { it.name == "Road trip" }
        assertThat(created.kind).isEqualTo(PlaylistKind.MANUAL)
        assertThat(db.playlistDao().getItems(created.id).map { it.episodeId }).containsExactly(episodeId)
        assertThat(vm.state.awaitValue { row(it, created.id)?.contains == true }).isNotNull()
    }

    @Test fun blankNameCreatesNothing() = runTest {
        vm.bind(episodeId)
        vm.state.awaitValue { it.loaded }

        vm.createPlaylistAndAdd("   ")

        assertThat(db.playlistDao().getAll().filter { it.kind == PlaylistKind.MANUAL }).isEmpty()
    }

    @Test fun actionsBeforeBindDoNothing() = runTest {
        val manual = playlists.createManual("Science")

        vm.toggle(manual)
        vm.createPlaylistAndAdd("Ignored")

        assertThat(db.playlistDao().getItems(manual)).isEmpty()
        assertThat(db.playlistDao().getAll().map { it.name }).doesNotContain("Ignored")
    }

    private fun row(state: AddToPlaylistUiState, playlistId: Long): AddToPlaylistRow? =
        state.rows.firstOrNull { it.playlistId == playlistId }
}
