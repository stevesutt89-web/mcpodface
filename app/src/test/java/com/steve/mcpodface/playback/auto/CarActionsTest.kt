package com.steve.mcpodface.playback.auto

import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.CommandButton
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.steve.mcpodface.core.Clock
import com.steve.mcpodface.data.db.AppDatabase
import com.steve.mcpodface.data.db.EpisodeEntity
import com.steve.mcpodface.data.db.PodcastEntity
import com.steve.mcpodface.data.db.PodcastSettingsEntity
import com.steve.mcpodface.playback.PlaybackEngine
import com.steve.mcpodface.playback.SleepTimer
import com.steve.mcpodface.playback.SleepTimerState
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CarActionsTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private var now = START
    private val clock = Clock { now }
    private val db: AppDatabase = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
        .allowMainThreadQueries().build()
    private val timer = SleepTimer(ApplicationProvider.getApplicationContext(), scope, clock)
    private val engine = mockk<PlaybackEngine>(relaxed = true)
    private val actions = CarActions(db, timer, clock)

    private var podcastId = 0L
    private var episodeId = 0L

    @Before
    fun setUp() = runBlocking {
        Dispatchers.setMain(dispatcher)
        podcastId = db.podcastDao().insert(PodcastEntity(feedUrl = "https://example.com/feed", title = "Show"))
        episodeId = db.episodeDao().insertAll(
            listOf(EpisodeEntity(podcastId = podcastId, guid = "g1", title = "Ep", enclosureUrl = "u", pubDate = START, firstSeenAt = START)),
        ).single()
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private fun player(mediaId: String? = episodeId.toString(), speed: Float = 1f): Player =
        mockk<Player>(relaxed = true) {
            every { currentMediaItem } returns mediaId?.let { MediaItem.Builder().setMediaId(it).build() }
            every { playbackParameters } returns PlaybackParameters(speed)
        }

    // --- speed ---

    @Test fun speedStepsThroughTheCycleAndWrapsAboveTwo() {
        val expected = mapOf(1.0f to 1.2f, 1.2f to 1.5f, 1.5f to 1.8f, 1.8f to 2.0f, 2.0f to 1.0f, 2.5f to 1.0f)
        for ((current, next) in expected) {
            val p = player(speed = current)
            val result = runBlocking { actions.run(AutoCommands.SPEED, p, engine) }
            assertThat(result.resultCode).isEqualTo(SessionResult.RESULT_SUCCESS)
            verify { p.setPlaybackSpeed(next) }
        }
    }

    @Test fun speedIsSavedAsThePodcastSpeedKeepingOtherSettings() = runBlocking {
        db.podcastSettingsDao().upsert(PodcastSettingsEntity(podcastId = podcastId, skipOutroSec = 7))

        actions.run(AutoCommands.SPEED, player(speed = 1.2f), engine)

        val saved = db.podcastSettingsDao().get(podcastId)!!
        assertThat(saved.playbackSpeed).isEqualTo(1.5f)
        assertThat(saved.skipOutroSec).isEqualTo(7)
    }

    @Test fun speedCreatesTheSettingsRowWhenMissing() = runBlocking {
        assertThat(db.podcastSettingsDao().get(podcastId)).isNull()

        actions.run(AutoCommands.SPEED, player(speed = 1f), engine)

        assertThat(db.podcastSettingsDao().get(podcastId)?.playbackSpeed).isEqualTo(1.2f)
    }

    // --- star ---

    @Test fun starToggleGoesBothWays() = runBlocking {
        actions.run(AutoCommands.STAR, player(), engine)
        assertThat(db.episodeDao().getById(episodeId)!!.isStarred).isTrue()

        actions.run(AutoCommands.STAR, player(), engine)
        assertThat(db.episodeDao().getById(episodeId)!!.isStarred).isFalse()
    }

    // --- mark played & next ---

    @Test fun markPlayedNextCompletesThroughTheEngine() = runBlocking {
        val result = actions.run(AutoCommands.MARK_PLAYED_NEXT, player(), engine)

        assertThat(result.resultCode).isEqualTo(SessionResult.RESULT_SUCCESS)
        coVerify { engine.completeNow() }
    }

    // --- sleep ---

    @Test fun sleepCyclesOffFifteenThirtyEndOfEpisodeOff() = runBlocking {
        val p = player()
        actions.run(AutoCommands.SLEEP, p, engine)
        assertThat(timer.state.value).isEqualTo(SleepTimerState.Running(START + 15 * 60_000L))

        actions.run(AutoCommands.SLEEP, p, engine)
        assertThat(timer.state.value).isEqualTo(SleepTimerState.Running(START + 30 * 60_000L))

        actions.run(AutoCommands.SLEEP, p, engine)
        assertThat(timer.state.value).isEqualTo(SleepTimerState.EndOfEpisode)

        actions.run(AutoCommands.SLEEP, p, engine)
        assertThat(timer.state.value).isEqualTo(SleepTimerState.Off)
    }

    // --- errors ---

    @Test fun unknownActionIsNotSupported() = runBlocking {
        val result = actions.run("com.steve.mcpodface.car.NOPE", player(), engine)
        assertThat(result.resultCode).isEqualTo(SessionError.ERROR_NOT_SUPPORTED)
    }

    @Test fun noCurrentItemIsBadValue() = runBlocking {
        for (action in AutoCommands.NOW_PLAYING_ACTIONS) {
            val result = actions.run(action, player(mediaId = null), engine)
            assertThat(result.resultCode).isEqualTo(SessionError.ERROR_BAD_VALUE)
        }
        coVerify(exactly = 0) { engine.completeNow() }
    }

    // --- overflow buttons ---

    @Test fun overflowButtonsAreEmptyWithoutAnEpisode() = runBlocking {
        assertThat(actions.overflowButtons(null, player(mediaId = null))).isEmpty()
    }

    @Test fun overflowLabelsReflectSpeedStarAndSleep() = runBlocking {
        val buttons = actions.overflowButtons(episodeId, player(speed = 1f))
        assertThat(buttons.map { it.displayName.toString() })
            .containsExactly("1×", "Star", "Mark played", "Sleep").inOrder()
        assertThat(buttons.map { it.sessionCommand?.customAction }).containsExactly(
            AutoCommands.SPEED, AutoCommands.STAR, AutoCommands.MARK_PLAYED_NEXT, AutoCommands.SLEEP,
        ).inOrder()
        assertThat(buttons.all { it.slots.contains(CommandButton.SLOT_OVERFLOW) }).isTrue()
        assertThat(buttons[0].icon).isEqualTo(CommandButton.ICON_PLAYBACK_SPEED)
        assertThat(buttons[2].icon).isEqualTo(CommandButton.ICON_CHECK_CIRCLE_FILLED)
    }

    @Test fun overflowSpeedLabelShowsDecimals() = runBlocking {
        assertThat(actions.overflowButtons(episodeId, player(speed = 1.2f))[0].displayName.toString())
            .isEqualTo("1.2×")
        assertThat(actions.overflowButtons(episodeId, player(speed = 1.5f))[0].displayName.toString())
            .isEqualTo("1.5×")
    }

    @Test fun overflowStarLabelFollowsTheEpisode() = runBlocking {
        val unstarred = actions.overflowButtons(episodeId, player())[1]
        assertThat(unstarred.displayName.toString()).isEqualTo("Star")
        assertThat(unstarred.icon).isEqualTo(CommandButton.ICON_STAR_UNFILLED)

        db.episodeDao().setStarred(episodeId, true)
        val starred = actions.overflowButtons(episodeId, player())[1]
        assertThat(starred.displayName.toString()).isEqualTo("Starred")
        assertThat(starred.icon).isEqualTo(CommandButton.ICON_STAR_FILLED)
    }

    @Test fun overflowSleepLabelFollowsTheTimer() = runBlocking {
        suspend fun sleepName() = actions.overflowButtons(episodeId, player())[3].displayName.toString()

        assertThat(sleepName()).isEqualTo("Sleep")
        timer.start(15)
        assertThat(sleepName()).isEqualTo("Sleep 15 min")
        timer.start(30)
        assertThat(sleepName()).isEqualTo("Sleep 30 min")
        timer.stopAtEndOfEpisode()
        assertThat(sleepName()).isEqualTo("Sleep: end of episode")
        timer.cancel()
    }

    private companion object {
        const val START = 1_700_000_000_000L
    }
}
