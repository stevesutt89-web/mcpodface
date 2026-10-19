package com.steve.mcpodface.ui.search

import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.steve.mcpodface.core.Clock
import com.steve.mcpodface.data.db.AppDatabase
import com.steve.mcpodface.data.db.EpisodeEntity
import com.steve.mcpodface.data.db.PodcastEntity
import com.steve.mcpodface.data.episodes.EpisodeRepository
import com.steve.mcpodface.data.queue.QueueRepository
import com.steve.mcpodface.data.search.SearchRepository
import com.steve.mcpodface.data.search.SearchSort
import com.steve.mcpodface.ui.library.RecordingPlayer
import com.steve.mcpodface.ui.library.awaitValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EpisodeSearchViewModelTest {
    private lateinit var db: AppDatabase
    private lateinit var player: RecordingPlayer
    private val day = 86_400_000L
    private val now = 20_000 * day
    private val clock = Clock { now }
    private var space = 0L
    private var other = 0L

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        player = RecordingPlayer()
        kotlinx.coroutines.runBlocking {
            space = db.podcastDao().insert(PodcastEntity(feedUrl = "https://a/feed", title = "Space Show", isSubscribed = true))
            other = db.podcastDao().insert(PodcastEntity(feedUrl = "https://b/feed", title = "Other", isSubscribed = true))
        }
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    private fun viewModel(scope: Long? = null) = EpisodeSearchViewModel(
        search = SearchRepository(db, clock, Dispatchers.Unconfined),
        podcasts = db.podcastDao(),
        player = player,
        clock = clock,
        episodes = EpisodeRepository(db.episodeDao(), db.podcastDao(), clock),
        queue = QueueRepository(db),
        savedStateHandle = SavedStateHandle(if (scope == null) emptyMap() else mapOf("podcastId" to scope)),
    )

    private suspend fun episode(
        title: String,
        podcast: Long = space,
        daysAgo: Int = 1,
        notes: String = "",
    ): Long = db.episodeDao().insertAll(
        listOf(
            EpisodeEntity(
                podcastId = podcast, guid = title + podcast, title = title, descriptionText = notes,
                pubDate = now - daysAgo * day, enclosureUrl = "https://cdn/$title.mp3", firstSeenAt = 0,
                durationSec = 1800,
            ),
        ),
    ).single()

    @Test fun blankFieldShowsRecentSearchesAndNoResults() = runTest {
        episode("Mars one")
        val vm = viewModel()
        vm.onQueryChange("mars")
        vm.onSearchSubmitted()
        vm.onQueryChange("")
        val state = vm.uiState.awaitValue { it.recent.isNotEmpty() }
        assertThat(state.results).isNull()
        assertThat(state.recent.map { it.query }).containsExactly("mars")
    }

    @Test fun textSearchIsScopedToThePodcastAndNamesIt() = runTest {
        episode("Rocket launch", podcast = space)
        episode("Rocket recipe", podcast = other)
        val vm = viewModel(scope = space)
        val state = vm.uiState.awaitValue { it.scopeName != null && it.results != null }
        assertThat(state.scopeName).isEqualTo("Space Show")
        vm.onQueryChange("rocket")
        val searched = vm.uiState.awaitValue { it.query == "rocket" && it.results?.items?.isNotEmpty() == true }
        assertThat(searched.results!!.items.map { it.item.episode.title }).containsExactly("Rocket launch")
    }

    @Test fun removingTheScopeWidensToAllSubscriptions() = runTest {
        episode("Rocket launch", podcast = space)
        episode("Rocket recipe", podcast = other)
        val vm = viewModel(scope = space)
        vm.onQueryChange("rocket")
        vm.uiState.awaitValue { it.results?.items?.size == 1 }
        vm.onScopeRemoved()
        val widened = vm.uiState.awaitValue { it.results?.items?.size == 2 }
        assertThat(widened.scopeName).isNull()
        assertThat(widened.filters.podcastIds).isEmpty()
    }

    @Test fun submittingRecordsTheSearchWithTheScope() = runTest {
        episode("Rocket launch", podcast = space)
        val vm = viewModel(scope = space)
        vm.onQueryChange("rocket")
        vm.onSearchSubmitted()
        val state = vm.uiState.awaitValue { it.recent.isNotEmpty() }
        assertThat(state.recent.single().query).isEqualTo("rocket")
        assertThat(state.recent.single().podcastId).isEqualTo(space)
    }

    @Test fun openingAResultRecordsTheSearch() = runTest {
        episode("Rocket launch")
        val vm = viewModel()
        vm.onQueryChange("  rocket  ")
        vm.onResultOpened()
        val state = vm.uiState.awaitValue { it.recent.isNotEmpty() }
        assertThat(state.recent.single().query).isEqualTo("rocket")
    }

    @Test fun blankTextIsNeverRecorded() = runTest {
        episode("Rocket launch")
        val vm = viewModel()
        vm.onQueryChange("   ")
        vm.onSearchSubmitted()
        vm.onResultOpened()
        val state = vm.uiState.awaitValue { it.results == null }
        assertThat(state.recent).isEmpty()
    }

    @Test fun clearRecentRemovesEverySearch() = runTest {
        episode("Rocket launch")
        val vm = viewModel()
        vm.onQueryChange("rocket")
        vm.onSearchSubmitted()
        vm.uiState.awaitValue { it.recent.isNotEmpty() }
        vm.onClearRecent()
        assertThat(vm.uiState.awaitValue { it.recent.isEmpty() }.recent).isEmpty()
    }

    @Test fun recentSelectionRestoresTheQuery() = runTest {
        episode("Rocket launch")
        val vm = viewModel()
        vm.onRecentSelected("rocket")
        assertThat(vm.uiState.awaitValue { it.query == "rocket" }.query).isEqualTo("rocket")
    }

    @Test fun pastWeekPresetWindowsByDateWithBlankText() = runTest {
        episode("Recent", daysAgo = 2)
        episode("Old", daysAgo = 30)
        val vm = viewModel()
        vm.onDatePreset(DatePreset.PAST_WEEK)
        val state = vm.uiState.awaitValue { it.results != null }
        assertThat(state.datePreset).isEqualTo(DatePreset.PAST_WEEK)
        assertThat(state.filters.publishedFrom).isEqualTo(now - 7 * day)
        assertThat(state.results!!.items.map { it.item.episode.title }).containsExactly("Recent")
    }

    @Test fun customRangeSetsTheWindow() = runTest {
        val vm = viewModel()
        vm.onCustomDateRange(from = now - 3 * day, to = now)
        val state = vm.uiState.awaitValue { it.datePreset == DatePreset.CUSTOM }
        assertThat(state.filters.publishedFrom).isEqualTo(now - 3 * day)
        assertThat(state.filters.publishedTo).isEqualTo(now)
    }

    @Test fun sortAndFilterTogglesReachTheSearch() = runTest {
        episode("Starred one", daysAgo = 1)
        val vm = viewModel()
        vm.onSortChange(SearchSort.OLDEST)
        vm.onFiltersChange { it.copy(starredOnly = true) }
        val state = vm.uiState.awaitValue { it.filters.starredOnly && it.results != null }
        assertThat(state.sort).isEqualTo(SearchSort.OLDEST)
        // No episode is starred, so the filtered list is empty rather than the whole library.
        assertThat(state.results!!.items).isEmpty()
    }

    @Test fun togglePlayGoesThroughThePlayer() = runTest {
        val vm = viewModel()
        vm.onTogglePlay(42L)
        assertThat(player.toggled).containsExactly(42L)
    }
}
