package com.steve.mcpodface.ui.playlists

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
import com.steve.mcpodface.ui.library.awaitValue
import kotlinx.coroutines.Dispatchers
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

@RunWith(AndroidJUnit4::class)
class PlaylistsViewModelTest {
    private lateinit var db: AppDatabase
    private lateinit var playlists: PlaylistRepository

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val clock = Clock { NOW }
        playlists = PlaylistRepository(db, SearchRepository(db, clock, Dispatchers.Unconfined), clock)
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    private fun viewModel() = PlaylistsViewModel(playlists, db.episodeDao(), db.downloadDao())

    @Test fun playlistsListManualAndSmartPlaylistsWithCounts() = runTest {
        val mine = playlists.createManual("Mine")
        playlists.createSmart("Fresh", SmartRules())
        val podcastId = db.podcastDao().insert(
            PodcastEntity(
                feedUrl = "https://example.com/alpha",
                title = "Alpha",
                artworkUrl = "https://example.com/alpha.jpg",
                isSubscribed = true,
                subscribedAt = NOW,
            ),
        )
        val episodeId = db.episodeDao().insertAll(
            listOf(
                EpisodeEntity(
                    podcastId = podcastId,
                    guid = "saved",
                    title = "Saved",
                    pubDate = NOW,
                    enclosureUrl = "https://example.com/saved.mp3",
                    firstSeenAt = 0,
                ),
            ),
        ).single()
        playlists.add(mine, episodeId)
        val vm = viewModel()

        val state = vm.state.awaitValue { it.playlists.size == 2 }

        assertThat(state.loaded).isTrue()
        assertThat(state.playlists.map { it.playlist.name to it.playlist.kind to it.itemCount })
            .containsExactly(
                "Mine" to PlaylistKind.MANUAL to 1,
                "Fresh" to PlaylistKind.SMART to 0,
            ).inOrder()
        assertThat(state.artworks[mine]).containsExactly("https://example.com/alpha.jpg")
    }

    @Test fun mosaicTakesTheFirstFourDistinctArtworks() {
        assertThat(mosaicArtworks(listOf("a", null, "a", "", "b", "c", "b", "d", "e")))
            .containsExactly("a", "b", "c", "d").inOrder()
        assertThat(mosaicArtworks(listOf(null, " "))).isEmpty()
    }

    @Test fun createPlaylistMakesAManualPlaylistAndAsksToOpenIt() = runTest {
        val vm = viewModel()
        vm.state.awaitValue { it.loaded }

        vm.createPlaylist("Road trip")

        val event = withContext(Dispatchers.Default) { withTimeout(5_000) { vm.events.first() } }
            as PlaylistsEvent.OpenPlaylist
        val created = playlists.observePlaylists().first().single { it.playlist.id == event.playlistId }
        assertThat(created.playlist.name).isEqualTo("Road trip")
        assertThat(created.playlist.kind).isEqualTo(PlaylistKind.MANUAL)
    }

    private companion object {
        const val NOW = 1_792_411_200_000L // 2026-10-19T12:00Z
    }
}
