package com.steve.mcpodface.ui.library

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.steve.mcpodface.data.db.AppDatabase
import com.steve.mcpodface.data.db.EpisodeEntity
import com.steve.mcpodface.data.db.PlayState
import com.steve.mcpodface.data.db.PodcastEntity
import com.steve.mcpodface.data.feed.FeedRepository
import com.steve.mcpodface.data.feed.SubscriptionRepository
import com.steve.mcpodface.data.prefs.LibraryLayout
import com.steve.mcpodface.data.prefs.LibrarySort
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryViewModelTest {
    private lateinit var db: AppDatabase
    private lateinit var settings: FakeLibrarySettings
    private lateinit var feeds: FeedRepository
    private lateinit var subscriptions: SubscriptionRepository

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        settings = FakeLibrarySettings()
        feeds = mockk(relaxed = true)
        subscriptions = mockk(relaxed = true)
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    private fun viewModel() = LibraryViewModel(db, settings, feeds, subscriptions)

    /** Inserts a podcast with [unplayed] unplayed episodes plus one played episode. */
    private suspend fun seedPodcast(title: String, unplayed: Int = 0, subscribed: Boolean = true): Long {
        val podcastId = db.podcastDao().insert(
            PodcastEntity(
                feedUrl = "https://example.com/$title",
                title = title,
                isSubscribed = subscribed,
                subscribedAt = LIBRARY_TEST_NOW,
            ),
        )
        val episodes = (1..unplayed).map { n ->
            EpisodeEntity(
                podcastId = podcastId,
                guid = "$title-$n",
                title = "$title $n",
                pubDate = LIBRARY_TEST_NOW - n * LIBRARY_TEST_HOUR,
                enclosureUrl = "https://example.com/$title/$n.mp3",
                firstSeenAt = 0,
            )
        } + EpisodeEntity(
            podcastId = podcastId,
            guid = "$title-played",
            title = "$title played",
            pubDate = LIBRARY_TEST_NOW,
            enclosureUrl = "https://example.com/$title/played.mp3",
            firstSeenAt = 0,
            playState = PlayState.PLAYED,
        )
        db.episodeDao().insertAll(episodes)
        return podcastId
    }

    @Test fun stateListsSubscribedPodcastsWithUnplayedCounts() = runTest {
        seedPodcast("Alpha", unplayed = 2)
        seedPodcast("Beta", unplayed = 0)
        seedPodcast("Hidden", unplayed = 5, subscribed = false)
        val vm = viewModel()

        val state = vm.state.awaitValue { it.loaded }

        assertThat(state.podcasts.map { it.podcast.title to it.unplayedCount })
            .containsExactly("Alpha" to 2, "Beta" to 0).inOrder()
    }

    @Test fun sortAndLayoutPersistToSettingsAndReflectInState() = runTest {
        seedPodcast("Beta")
        seedPodcast("Alpha")
        val vm = viewModel()

        vm.setSort(LibrarySort.ALPHABETICAL)
        vm.setLayout(LibraryLayout.LIST)

        val state = vm.state.awaitValue { it.sort == LibrarySort.ALPHABETICAL && it.layout == LibraryLayout.LIST }
        assertThat(state.podcasts.map { it.podcast.title }).containsExactly("Alpha", "Beta").inOrder()
        eventually {
            val saved = settings.settings.first()
            saved.librarySort == LibrarySort.ALPHABETICAL && saved.libraryLayout == LibraryLayout.LIST
        }
    }

    @Test fun unsubscribeCallsThroughWithDeleteDownloadsFlag() = runTest {
        val vm = viewModel()

        vm.unsubscribe(7L, deleteDownloads = false)
        vm.unsubscribe(8L, deleteDownloads = true)

        coVerify(exactly = 1) { subscriptions.unsubscribe(7L, false) }
        coVerify(exactly = 1) { subscriptions.unsubscribe(8L, true) }
    }

    @Test fun refreshCallsRefreshAllAndClearsRefreshingFlag() = runTest {
        coEvery { feeds.refreshAll() } returns emptyList()
        val vm = viewModel()

        vm.refresh()

        coVerify(exactly = 1) { feeds.refreshAll() }
        vm.state.awaitValue { !it.refreshing }
    }
}
