package com.steve.mcpodface.ui.library

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.steve.mcpodface.core.Clock
import com.steve.mcpodface.data.db.AppDatabase
import com.steve.mcpodface.data.db.EpisodeEntity
import com.steve.mcpodface.data.db.PodcastEntity
import com.steve.mcpodface.data.episodes.EpisodeRepository
import com.steve.mcpodface.data.playlist.PlaylistRepository
import com.steve.mcpodface.data.search.SearchRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

private const val STARRED_FEED = "https://example.com/starred"

@RunWith(AndroidJUnit4::class)
class StarredViewModelTest {
    private lateinit var db: AppDatabase
    private lateinit var player: RecordingPlayer
    private val clock = Clock { LIBRARY_TEST_NOW }

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        player = RecordingPlayer()
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    private fun viewModel() = StarredViewModel(
        EpisodeRepository(db.episodeDao(), db.podcastDao(), clock),
        player,
        PlaylistRepository(db, SearchRepository(db, clock, Dispatchers.Unconfined), clock),
        clock,
    )

    /** Inserts one episode under a shared podcast and returns its id. */
    private suspend fun seedEpisode(title: String, starred: Boolean): Long {
        val podcastId = db.podcastDao().getByFeedUrl(STARRED_FEED)?.id
            ?: db.podcastDao().insert(PodcastEntity(feedUrl = STARRED_FEED, title = "Show", isSubscribed = true))
        return db.episodeDao().insertAll(
            listOf(
                EpisodeEntity(
                    podcastId = podcastId,
                    guid = title,
                    title = title,
                    pubDate = LIBRARY_TEST_NOW - LIBRARY_TEST_HOUR,
                    enclosureUrl = "https://example.com/$title.mp3",
                    firstSeenAt = 0,
                    isStarred = starred,
                ),
            ),
        ).single()
    }

    @Test fun listsOnlyStarredEpisodesAndUnstarRemovesThem() = runTest {
        val starredId = seedEpisode("kept", starred = true)
        val plainId = seedEpisode("plain", starred = false)
        val vm = viewModel()

        val before = vm.state.awaitValue { it.loaded }
        assertThat(before.items.map { it.episode.title }).containsExactly("kept")

        vm.removeStar(starredId)

        val after = vm.state.awaitValue { it.loaded && it.items.isEmpty() }
        assertThat(after.items).isEmpty()
        assertThat(db.episodeDao().getById(starredId)!!.isStarred).isFalse()
        assertThat(db.episodeDao().getById(plainId)!!.isStarred).isFalse()
    }

    @Test fun togglePlayPassesThroughToPlayer() = runTest {
        val vm = viewModel()

        vm.togglePlay(42L)

        assertThat(player.toggled).containsExactly(42L)
    }
}
