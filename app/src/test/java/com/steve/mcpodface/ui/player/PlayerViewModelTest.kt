package com.steve.mcpodface.ui.player

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.steve.mcpodface.core.Clock
import com.steve.mcpodface.data.db.AppDatabase
import com.steve.mcpodface.data.db.EpisodeEntity
import com.steve.mcpodface.data.db.PlayState
import com.steve.mcpodface.data.db.PodcastEntity
import com.steve.mcpodface.data.db.PodcastSettingsEntity
import com.steve.mcpodface.data.episodes.EpisodeRepository
import com.steve.mcpodface.data.prefs.GlobalSettings
import com.steve.mcpodface.data.prefs.SettingsRepository
import com.steve.mcpodface.playback.NoOpPlayerController
import com.steve.mcpodface.playback.NowPlaying
import com.steve.mcpodface.playback.SleepTimer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlayerViewModelTest {
    private lateinit var db: AppDatabase
    private val player = FakePlayer()
    private val settings = FakeSettings()
    private val clock = Clock { NOW }

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private fun viewModel() = PlayerViewModel(
        playerController = player,
        episodeRepository = EpisodeRepository(db.episodeDao(), db.podcastDao(), clock),
        podcastSettingsDao = db.podcastSettingsDao(),
        settingsRepository = settings,
        sleepTimer = SleepTimer(ApplicationProvider.getApplicationContext(), CoroutineScope(Dispatchers.Unconfined), clock),
        clock = clock,
    )

    /** Inserts a subscribed podcast and one episode in it. Returns (podcastId, episodeId). */
    private suspend fun insertPodcastAndEpisode(
        podcastArtwork: String? = "https://example.com/podcast.jpg",
        episodeArtwork: String? = null,
        durationSec: Int? = 3_000,
    ): Pair<Long, Long> {
        val podcastId = db.podcastDao().insert(
            PodcastEntity(
                feedUrl = "https://example.com/feed",
                title = "Sample Show",
                artworkUrl = podcastArtwork,
                isSubscribed = true,
            ),
        )
        val episodeId = db.episodeDao().insertAll(
            listOf(
                EpisodeEntity(
                    podcastId = podcastId,
                    guid = "ep-1",
                    title = "Episode One",
                    descriptionHtml = "<p>Notes</p>",
                    pubDate = NOW - 1_000L,
                    durationSec = durationSec,
                    enclosureUrl = "https://example.com/ep.mp3",
                    artworkUrl = episodeArtwork,
                    firstSeenAt = 0,
                    playState = PlayState.UNPLAYED,
                ),
            ),
        ).single()
        return podcastId to episodeId
    }

    @Test fun stateIsNullWhenNothingIsPlaying() = runBlocking<Unit> {
        val vm = viewModel()
        assertThat(vm.uiState.value).isNull()

        val (_, episodeId) = insertPodcastAndEpisode()
        player.nowPlaying.value = playing(episodeId, positionMs = 1_000)
        assertThat(vm.uiState.await { it != null }).isNotNull()

        player.nowPlaying.value = null
        assertThat(vm.uiState.await { it == null }).isNull()
    }

    @Test fun stateCombinesEpisodeAndPodcastWithSettings() = runBlocking<Unit> {
        val (podcastId, episodeId) = insertPodcastAndEpisode()
        player.nowPlaying.value = NowPlaying(
            episodeId = episodeId,
            isPlaying = true,
            positionMs = 754_000,
            durationMs = 3_000_000,
            speed = 1.5f,
            isBuffering = true,
        )

        val state = viewModel().uiState.await { it != null }!!

        assertThat(state.episodeId).isEqualTo(episodeId)
        assertThat(state.title).isEqualTo("Episode One")
        assertThat(state.podcastTitle).isEqualTo("Sample Show")
        assertThat(state.podcastId).isEqualTo(podcastId)
        assertThat(state.artworkUrl).isEqualTo("https://example.com/podcast.jpg")
        assertThat(state.descriptionHtml).isEqualTo("<p>Notes</p>")
        assertThat(state.isPlaying).isTrue()
        assertThat(state.isBuffering).isTrue()
        assertThat(state.positionMs).isEqualTo(754_000L)
        assertThat(state.durationMs).isEqualTo(3_000_000L)
        assertThat(state.speed).isEqualTo(1.5f)
        assertThat(state.skipBackSec).isEqualTo(15)
        assertThat(state.skipForwardSec).isEqualTo(45)
    }

    @Test fun episodeArtworkWinsOverPodcastArtwork() = runBlocking<Unit> {
        val (_, episodeId) = insertPodcastAndEpisode(episodeArtwork = "https://example.com/episode.jpg")
        player.nowPlaying.value = playing(episodeId, positionMs = 0)

        val state = viewModel().uiState.await { it != null }!!

        assertThat(state.artworkUrl).isEqualTo("https://example.com/episode.jpg")
    }

    @Test fun durationFallsBackToEpisodeLengthWhenPlayerHasNone() = runBlocking<Unit> {
        val (_, episodeId) = insertPodcastAndEpisode(durationSec = 3_000)
        player.nowPlaying.value = NowPlaying(episodeId, isPlaying = false, positionMs = 0, durationMs = 0)

        val state = viewModel().uiState.await { it != null }!!

        assertThat(state.durationMs).isEqualTo(3_000_000L)
    }

    @Test fun playerDurationWinsWhenKnown() = runBlocking<Unit> {
        val (_, episodeId) = insertPodcastAndEpisode(durationSec = 3_000)
        player.nowPlaying.value = NowPlaying(episodeId, isPlaying = false, positionMs = 0, durationMs = 2_500_000)

        val state = viewModel().uiState.await { it != null }!!

        assertThat(state.durationMs).isEqualTo(2_500_000L)
    }

    @Test fun setSpeedForPodcastPersistsPlaybackSpeedAndKeepsOtherFields() = runBlocking<Unit> {
        val (podcastId, episodeId) = insertPodcastAndEpisode()
        db.podcastSettingsDao().upsert(
            PodcastSettingsEntity(podcastId = podcastId, autoDownload = false, skipIntroSec = 12, skipOutroSec = 7),
        )
        player.nowPlaying.value = playing(episodeId, positionMs = 0)
        val vm = viewModel()

        vm.setSpeed(1.8f, rememberForPodcast = true)

        assertThat(player.speeds).containsExactly(1.8f)
        eventually { db.podcastSettingsDao().get(podcastId)?.playbackSpeed == 1.8f }
        val saved = db.podcastSettingsDao().get(podcastId)!!
        assertThat(saved.playbackSpeed).isEqualTo(1.8f)
        assertThat(saved.autoDownload).isFalse()
        assertThat(saved.skipIntroSec).isEqualTo(12)
        assertThat(saved.skipOutroSec).isEqualTo(7)
        assertThat(settings.state.value.defaultSpeed).isEqualTo(1.0f)
    }

    @Test fun setSpeedForPodcastCreatesTheSettingsRowWhenMissing() = runBlocking<Unit> {
        val (podcastId, episodeId) = insertPodcastAndEpisode()
        assertThat(db.podcastSettingsDao().get(podcastId)).isNull()
        player.nowPlaying.value = playing(episodeId, positionMs = 0)

        viewModel().setSpeed(1.2f, rememberForPodcast = true)

        eventually { db.podcastSettingsDao().get(podcastId)?.playbackSpeed == 1.2f }
        assertThat(db.podcastSettingsDao().get(podcastId)?.playbackSpeed).isEqualTo(1.2f)
    }

    @Test fun setSpeedGlobalUpdatesDefaultSpeedOnly() = runBlocking<Unit> {
        val (podcastId, episodeId) = insertPodcastAndEpisode()
        player.nowPlaying.value = playing(episodeId, positionMs = 0)

        viewModel().setSpeed(2.0f, rememberForPodcast = false)

        assertThat(player.speeds).containsExactly(2.0f)
        eventually { settings.state.value.defaultSpeed == 2.0f }
        assertThat(settings.state.value.defaultSpeed).isEqualTo(2.0f)
        assertThat(db.podcastSettingsDao().get(podcastId)).isNull()
    }

    @Test fun clockFormatsMinutesAndHours() {
        assertThat(formatClock(0)).isEqualTo("0:00")
        assertThat(formatClock(999)).isEqualTo("0:00")
        assertThat(formatClock(5_000)).isEqualTo("0:05")
        assertThat(formatClock(59_999)).isEqualTo("0:59")
        assertThat(formatClock(65_000)).isEqualTo("1:05")
        assertThat(formatClock(1_930_000)).isEqualTo("32:10")
        assertThat(formatClock(3_723_000)).isEqualTo("1:02:03")
        assertThat(formatClock(-5_000)).isEqualTo("0:00")
    }

    @Test fun remainingTimeIsDividedBySpeed() {
        assertThat(remainingPlaybackMs(durationMs = 3_600_000, positionMs = 0, speed = 1f)).isEqualTo(3_600_000L)
        assertThat(remainingPlaybackMs(durationMs = 3_600_000, positionMs = 0, speed = 2f)).isEqualTo(1_800_000L)
        assertThat(remainingPlaybackMs(durationMs = 3_600_000, positionMs = 3_000_000, speed = 1.5f)).isEqualTo(400_000L)
        // Past the end never goes negative; a non-positive speed behaves as 1x.
        assertThat(remainingPlaybackMs(durationMs = 1_000, positionMs = 5_000, speed = 1f)).isEqualTo(0L)
        assertThat(remainingPlaybackMs(durationMs = 60_000, positionMs = 0, speed = 0f)).isEqualTo(60_000L)
    }

    @Test fun remainingLabelIsNegativeClockAdjustedForSpeed() {
        assertThat(formatRemaining(durationMs = 3_930_000, positionMs = 1_930_000, speed = 1f)).isEqualTo("-33:20")
        assertThat(formatRemaining(durationMs = 3_930_000, positionMs = 1_930_000, speed = 2f)).isEqualTo("-16:40")
        assertThat(formatRemaining(durationMs = 1_000, positionMs = 1_000, speed = 1f)).isEqualTo("-0:00")
    }

    @Test fun speedLabelHasOneDecimalAndTimesSign() {
        assertThat(formatSpeed(1f)).isEqualTo("1.0×")
        assertThat(formatSpeed(1.5f)).isEqualTo("1.5×")
        assertThat(roundSpeed(1.4000001f)).isEqualTo(1.4f)
    }

    /** Waits (up to 5s) until a value satisfying [predicate] is emitted, then returns it. */
    private suspend fun <T> StateFlow<T>.await(predicate: (T) -> Boolean): T =
        withTimeout(5_000) { first(predicate) }

    /** Waits (up to 5s) for [check] to hold. ViewModel writes run on Room's executor, so they finish later. */
    private suspend fun eventually(check: suspend () -> Boolean) = withTimeout(5_000) {
        while (!check()) delay(10)
    }

    private fun playing(episodeId: Long, positionMs: Long) =
        NowPlaying(episodeId, isPlaying = true, positionMs = positionMs, durationMs = 0)

    /** Records speed changes; everything else is a no-op. */
    private class FakePlayer : NoOpPlayerController() {
        override val nowPlaying = MutableStateFlow<NowPlaying?>(null)
        val speeds = mutableListOf<Float>()

        override fun setSpeed(speed: Float) {
            speeds += speed
        }
    }

    private class FakeSettings : SettingsRepository {
        val state = MutableStateFlow(GlobalSettings(skipBackSec = 15, skipForwardSec = 45))
        override val settings: Flow<GlobalSettings> = state

        override suspend fun update(transform: (GlobalSettings) -> GlobalSettings): GlobalSettings {
            state.value = transform(state.value)
            return state.value
        }
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
    }
}
