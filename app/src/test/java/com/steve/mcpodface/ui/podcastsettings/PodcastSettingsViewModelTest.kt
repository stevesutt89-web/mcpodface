package com.steve.mcpodface.ui.podcastsettings

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.steve.mcpodface.data.db.AppDatabase
import com.steve.mcpodface.data.db.FeedType
import com.steve.mcpodface.data.db.PodcastEntity
import com.steve.mcpodface.data.db.PodcastSettingsEntity
import com.steve.mcpodface.data.db.QueueAdd
import com.steve.mcpodface.data.db.SortOrder
import com.steve.mcpodface.data.prefs.GlobalSettings
import com.steve.mcpodface.data.prefs.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PodcastSettingsViewModelTest {
    private lateinit var db: AppDatabase
    private lateinit var observerScope: CoroutineScope
    private val global = FakeGlobalSettings()

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        observerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After fun tearDown() {
        observerScope.cancel()
        db.close()
        Dispatchers.resetMain()
    }

    private suspend fun insertPodcast(feedType: FeedType = FeedType.EPISODIC, title: String = "Show"): Long =
        db.podcastDao().insert(
            PodcastEntity(feedUrl = "https://example.com/$title", title = title, feedType = feedType, isSubscribed = true),
        )

    /** Starts collecting the VM's state and returns a view of its latest value. */
    private fun observe(podcastId: Long): Pair<PodcastSettingsViewModel, () -> PodcastSettingsUiState> {
        val vm = PodcastSettingsViewModel(
            podcastId = podcastId,
            podcastDao = db.podcastDao(),
            podcastSettingsDao = db.podcastSettingsDao(),
            settingsRepository = global,
        )
        val latest = MutableStateFlow(PodcastSettingsUiState())
        observerScope.launch { vm.uiState.collect { latest.value = it } }
        return vm to { latest.value }
    }

    private suspend fun awaitState(read: () -> PodcastSettingsUiState, predicate: (PodcastSettingsUiState) -> Boolean) =
        withContext(Dispatchers.Default) {
            withTimeout(5_000) {
                while (!predicate(read())) delay(10)
            }
            read()
        }

    private suspend fun awaitRow(podcastId: Long, predicate: (PodcastSettingsEntity?) -> Boolean): PodcastSettingsEntity? =
        withContext(Dispatchers.Default) {
            withTimeout(5_000) {
                while (!predicate(db.podcastSettingsDao().get(podcastId))) delay(10)
            }
            db.podcastSettingsDao().get(podcastId)
        }

    @Test fun firstUpdateCreatesRowWithPodcastIdAndKeepsOtherFieldsDefault() = runBlocking {
        val podcastId = insertPodcast()
        assertThat(db.podcastSettingsDao().get(podcastId)).isNull()
        val (vm, _) = observe(podcastId)

        vm.update { PodcastSettingsOptions.autoDownload.apply(it, false) }

        val saved = awaitRow(podcastId) { it?.autoDownload == false }
        assertThat(saved).isEqualTo(PodcastSettingsEntity(podcastId = podcastId, autoDownload = false))
    }

    @Test fun updatePreservesEveryOtherField() = runBlocking {
        val podcastId = insertPodcast()
        val original = PodcastSettingsEntity(
            podcastId = podcastId,
            autoDownload = true,
            keepLatestCount = 3,
            deletePlayed = false,
            maxAgeDays = 9,
            allowCellular = true,
            sortOrder = SortOrder.OLDEST_FIRST,
            playbackSpeed = 1.5f,
            skipIntroSec = 10,
            skipOutroSec = 20,
            notifyNewEpisodes = false,
            autoAddToQueue = QueueAdd.TOP,
            backfillEnabled = false,
        )
        db.podcastSettingsDao().upsert(original)
        val (vm, _) = observe(podcastId)

        vm.update { PodcastSettingsOptions.keepLatest.apply(it, 10) }

        val saved = awaitRow(podcastId) { it?.keepLatestCount == 10 }
        assertThat(saved).isEqualTo(original.copy(keepLatestCount = 10))
    }

    @Test fun choosingDefaultClearsOnlyThatOverride() = runBlocking {
        val podcastId = insertPodcast()
        db.podcastSettingsDao().upsert(PodcastSettingsEntity(podcastId = podcastId, maxAgeDays = 90, keepLatestCount = 2))
        val (vm, _) = observe(podcastId)

        vm.update { PodcastSettingsOptions.maxAgeDays.apply(it, null) }

        val saved = awaitRow(podcastId) { it?.maxAgeDays == null }
        assertThat(saved?.maxAgeDays).isNull()
        assertThat(saved?.keepLatestCount).isEqualTo(2)
    }

    @Test fun rapidUpdatesAllLandBecauseWritesAreSerialised() = runBlocking {
        val podcastId = insertPodcast()
        val (vm, _) = observe(podcastId)

        vm.update { PodcastSettingsOptions.autoDownload.apply(it, false) }
        vm.update { PodcastSettingsOptions.keepLatest.apply(it, 20) }
        vm.update { PodcastSettingsOptions.skipIntro.apply(it, 30) }

        val saved = awaitRow(podcastId) { it?.skipIntroSec == 30 && it.keepLatestCount == 20 }
        assertThat(saved?.autoDownload).isFalse()
    }

    @Test fun serialPodcastShowsOldestFirstDefaultLabel() = runBlocking {
        val podcastId = insertPodcast(FeedType.SERIAL, title = "Serial")
        val (_, read) = observe(podcastId)

        val state = awaitState(read) { it.loaded }

        assertThat(state.title).isEqualTo("Serial")
        assertThat(PodcastSettingsOptions.sortOrder.label(state.settings, state.inheritance))
            .isEqualTo("Default (Oldest first)")
    }

    @Test fun labelsReflectTheGlobalValueAndChangeWithIt() = runBlocking {
        val podcastId = insertPodcast()
        val (_, read) = observe(podcastId)
        awaitState(read) { it.loaded }

        assertThat(PodcastSettingsOptions.keepLatest.label(read().settings, read().inheritance)).isEqualTo("Default (5)")

        global.update { it.copy(keepLatestCount = 12) }

        val state = awaitState(read) { it.global.keepLatestCount == 12 }
        assertThat(PodcastSettingsOptions.keepLatest.label(state.settings, state.inheritance)).isEqualTo("Default (12)")
    }

    @Test fun explicitSortOverrideIsSavedAndReflectedInState() = runBlocking {
        val podcastId = insertPodcast(FeedType.SERIAL)
        val (vm, read) = observe(podcastId)
        awaitState(read) { it.loaded }

        vm.update { PodcastSettingsOptions.sortOrder.apply(it, SortOrder.NEWEST_FIRST) }

        val state = awaitState(read) { it.settings.sortOrder == SortOrder.NEWEST_FIRST }
        assertThat(PodcastSettingsOptions.sortOrder.label(state.settings, state.inheritance)).isEqualTo("Newest first")
    }

    @Test fun notificationSwitchPersistsWithoutTouchingPolicyFields() = runBlocking {
        val podcastId = insertPodcast()
        db.podcastSettingsDao().upsert(PodcastSettingsEntity(podcastId = podcastId, deletePlayed = true))
        val (vm, _) = observe(podcastId)

        vm.update { PodcastSettingsOptions.applyNotifyNewEpisodes(it, false) }

        val saved = awaitRow(podcastId) { it?.notifyNewEpisodes == false }
        assertThat(saved?.deletePlayed).isTrue()
    }

    @Test fun missingPodcastStillLoadsWithBlankTitle() = runBlocking {
        val (_, read) = observe(podcastId = 4242L)
        val state = awaitState(read) { it.loaded }
        assertThat(state.title).isEmpty()
        assertThat(state.feedType).isEqualTo(FeedType.EPISODIC)
    }
}

/** In-memory global settings for the sheet tests. */
private class FakeGlobalSettings : SettingsRepository {
    private val state = MutableStateFlow(GlobalSettings())
    override val settings: Flow<GlobalSettings> = state
    override suspend fun update(transform: (GlobalSettings) -> GlobalSettings): GlobalSettings =
        transform(state.value).also { state.value = it }
}
