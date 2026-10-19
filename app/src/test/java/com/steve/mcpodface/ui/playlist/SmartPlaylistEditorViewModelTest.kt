package com.steve.mcpodface.ui.playlist

import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.steve.mcpodface.core.Clock
import com.steve.mcpodface.data.db.AppDatabase
import com.steve.mcpodface.data.db.EpisodeEntity
import com.steve.mcpodface.data.db.PlayState
import com.steve.mcpodface.data.db.PlaylistKind
import com.steve.mcpodface.data.db.PodcastEntity
import com.steve.mcpodface.data.playlist.PlaylistRepository
import com.steve.mcpodface.data.playlist.SmartRules
import com.steve.mcpodface.data.search.SearchRepository
import com.steve.mcpodface.data.search.SearchSort
import com.steve.mcpodface.data.search.StatusFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SmartPlaylistEditorViewModelTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: PlaylistRepository
    private lateinit var observerScope: CoroutineScope
    private val day = 86_400_000L
    private val clock = Clock { NOW }
    private var show = 0L
    private var other = 0L
    private val ids = mutableMapOf<String, Long>()

    @Before fun setUp() = runBlocking<Unit> {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        observerScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = PlaylistRepository(db, SearchRepository(db, clock, Dispatchers.Unconfined), clock)
        show = db.podcastDao().insert(PodcastEntity(feedUrl = "https://a/feed", title = "Show", isSubscribed = true))
        other = db.podcastDao().insert(PodcastEntity(feedUrl = "https://b/feed", title = "Other", isSubscribed = true))
        episode("new", show, daysAgo = 1)
        episode("week", show, daysAgo = 6)
        episode("old", show, daysAgo = 60)
        episode("played", show, daysAgo = 2, state = PlayState.PLAYED)
        episode("elsewhere", other, daysAgo = 3)
    }

    /** Every ViewModel made, so their scopes (the debounced preview runs off the test thread) stop before the DB closes. */
    private val viewModels = mutableListOf<SmartPlaylistEditorViewModel>()

    @After fun tearDown() {
        observerScope.cancel()
        runBlocking { viewModels.forEach { it.viewModelScope.coroutineContext[Job]?.cancelAndJoin() } }
        db.close()
        Dispatchers.resetMain()
    }

    private suspend fun episode(guid: String, podcast: Long, daysAgo: Int, state: PlayState = PlayState.UNPLAYED) {
        ids[guid] = db.episodeDao().insertAll(
            listOf(
                EpisodeEntity(
                    podcastId = podcast, guid = guid, title = "Episode $guid", pubDate = NOW - daysAgo * day,
                    enclosureUrl = "https://cdn/$guid.mp3", firstSeenAt = 0, playState = state,
                ),
            ),
        ).single()
    }

    private fun editor(playlistId: Long = 0L): Editor =
        Editor(
            SmartPlaylistEditorViewModel(
                savedStateHandle = SavedStateHandle(mapOf("playlistId" to playlistId)),
                playlists = repo,
                podcasts = db.podcastDao(),
                clock = clock,
            ).also { viewModels += it },
        )

    /** Keeps the ViewModel's state flow subscribed and exposes the latest value to the test. */
    private inner class Editor(val vm: SmartPlaylistEditorViewModel) {
        @Volatile private var latest = SmartPlaylistEditorUiState()

        init {
            vm.uiState.onEach { latest = it }.launchIn(observerScope)
        }

        suspend fun await(predicate: (SmartPlaylistEditorUiState) -> Boolean): SmartPlaylistEditorUiState =
            withTimeout(5_000) {
                while (!predicate(latest)) delay(10)
                latest
            }
    }

    @Test fun newPlaylistSavesASmartPlaylistWithTheChosenRules() = runBlocking<Unit> {
        val ed = editor()
        ed.vm.onNameChange("  Fresh  ")
        ed.vm.onRulesChange {
            it.copy(podcastIds = listOf(show), publishedWithinDays = 7, sort = SearchSort.OLDEST, limit = 25)
        }

        val id = ed.vm.save()!!

        val saved = db.playlistDao().getById(id)!!
        assertThat(saved.kind).isEqualTo(PlaylistKind.SMART)
        assertThat(saved.name).isEqualTo("Fresh")
        assertThat(SmartRules.fromJson(saved.rulesJson)).isEqualTo(
            SmartRules(podcastIds = listOf(show), publishedWithinDays = 7, sort = SearchSort.OLDEST, limit = 25),
        )
    }

    @Test fun editLoadsTheExistingNameAndRulesAndSavingUpdatesThem() = runBlocking<Unit> {
        val id = repo.createSmart("Old name", SmartRules(podcastIds = listOf(other), status = StatusFilter.ANY, limit = 10))
        val ed = editor(id)

        val loaded = ed.await { it.loaded }
        assertThat(loaded.isNew).isFalse()
        assertThat(loaded.name).isEqualTo("Old name")
        assertThat(loaded.rules).isEqualTo(SmartRules(podcastIds = listOf(other), status = StatusFilter.ANY, limit = 10))

        ed.vm.onNameChange("New name")
        ed.vm.onRulesChange { it.copy(limit = 100, starredOnly = true) }
        assertThat(ed.vm.save()).isEqualTo(id)

        val saved = db.playlistDao().getById(id)!!
        assertThat(saved.name).isEqualTo("New name")
        assertThat(saved.kind).isEqualTo(PlaylistKind.SMART)
        assertThat(SmartRules.fromJson(saved.rulesJson)).isEqualTo(
            SmartRules(podcastIds = listOf(other), status = StatusFilter.ANY, limit = 100, starredOnly = true),
        )
    }

    @Test fun previewCountFollowsTheRules() = runBlocking<Unit> {
        val ed = editor()
        // Default rules: not played, every subscription, so the played episode is left out.
        val all = ed.await { it.preview?.size == 4 }
        assertThat(all.preview!!.map { it.episode.guid }).containsExactly("new", "elsewhere", "week", "old").inOrder()

        ed.vm.onRulesChange { it.copy(podcastIds = listOf(show), publishedWithinDays = 7) }
        val narrowed = ed.await { it.preview?.size == 2 }
        assertThat(narrowed.preview!!.map { it.episode.guid }).containsExactly("new", "week").inOrder()

        ed.vm.onRulesChange { it.copy(limit = 1) }
        val capped = ed.await { it.preview?.size == 1 }
        assertThat(capped.preview!!.map { it.episode.guid }).containsExactly("new")
    }

    @Test fun blankNameCannotBeSaved() = runBlocking<Unit> {
        val ed = editor()
        ed.vm.onNameChange("   ")

        assertThat(ed.await { it.loaded }.canSave).isFalse()
        assertThat(ed.vm.save()).isNull()
        assertThat(db.playlistDao().getFirstOfKind(PlaylistKind.SMART)).isNull()
    }

    @Test fun podcastToggleBuildsTheScopeAndAllClearsIt() = runBlocking<Unit> {
        val ed = editor()
        ed.vm.togglePodcast(other)
        ed.vm.togglePodcast(show)
        assertThat(ed.await { it.rules.podcastIds.size == 2 }.rules.podcastIds).containsExactly(show, other).inOrder()

        ed.vm.togglePodcast(show)
        assertThat(ed.await { it.rules.podcastIds == listOf(other) }.rules.podcastIds).containsExactly(other)

        ed.vm.selectAllPodcasts()
        assertThat(ed.await { it.rules.podcastIds.isEmpty() }.rules.podcastIds).isEmpty()
    }

    @Test fun manualPlaylistsCannotBeEditedHere() = runBlocking<Unit> {
        val manual = repo.createManual("Mine")
        val ed = editor(manual)

        val state = ed.await { it.loaded }
        assertThat(state.notFound).isTrue()
        assertThat(state.canSave).isFalse()
        ed.vm.onNameChange("Renamed")
        assertThat(ed.vm.save()).isNull()
        assertThat(db.playlistDao().getById(manual)!!.name).isEqualTo("Mine")
    }

    private companion object {
        // 2026-10-19T12:00Z in epoch millis, a fixed "now" for the age windows.
        const val NOW = 1_792_411_200_000L
    }
}
