package com.steve.mcpodface.playback

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.steve.mcpodface.core.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SleepTimerTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    /** Follows the test scheduler, so the timer's clock and delays agree. */
    private val clock = Clock { START + dispatcher.scheduler.currentTime }
    private var expired = 0

    private fun timer() = SleepTimer(ApplicationProvider.getApplicationContext(), scope, clock).also {
        it.onExpire = { expired++ }
    }

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun fadeIsFullUntilTheLastTenSecondsThenLinear() {
        assertThat(SleepTimer.fadeVolume(60_000)).isEqualTo(1f)
        assertThat(SleepTimer.fadeVolume(10_000)).isEqualTo(1f)
        assertThat(SleepTimer.fadeVolume(5_000)).isEqualTo(0.5f)
        assertThat(SleepTimer.fadeVolume(0)).isEqualTo(0f)
        assertThat(SleepTimer.fadeVolume(-5)).isEqualTo(0f)
    }

    @Test fun runningTimerFadesThenExpiresAndTurnsOff() {
        val t = timer()
        t.start(1)
        assertThat(t.state.value).isEqualTo(SleepTimerState.Running(START + 60_000))
        scope.runCurrent()
        scope.advanceTimeBy(55_000)
        scope.runCurrent()
        assertThat(t.volume.value).isLessThan(1f)
        assertThat(expired).isEqualTo(0)
        scope.advanceTimeBy(6_000)
        scope.runCurrent()
        assertThat(expired).isEqualTo(1)
        assertThat(t.state.value).isEqualTo(SleepTimerState.Off)
        scope.advanceTimeBy(1_000)
        scope.runCurrent()
        assertThat(t.volume.value).isEqualTo(1f)
    }

    @Test fun extendAddsTimeAndRestoresVolume() {
        val t = timer()
        t.start(1)
        scope.runCurrent()
        scope.advanceTimeBy(55_000)
        scope.runCurrent()
        t.extend(SleepTimer.EXTEND_MS)
        assertThat(t.volume.value).isEqualTo(1f)
        scope.advanceTimeBy(60_000)
        scope.runCurrent()
        assertThat(expired).isEqualTo(0)
        assertThat(t.state.value).isEqualTo(SleepTimerState.Running(START + 60_000 + SleepTimer.EXTEND_MS))
        t.cancel()
    }

    @Test fun cancelStopsTheTimer() {
        val t = timer()
        t.start(1)
        t.cancel()
        scope.advanceTimeBy(120_000)
        scope.runCurrent()
        assertThat(expired).isEqualTo(0)
        assertThat(t.state.value).isEqualTo(SleepTimerState.Off)
    }

    @Test fun endOfEpisodeIsConsumedOnce() {
        val t = timer()
        assertThat(t.consumeEndOfEpisode()).isFalse()
        t.stopAtEndOfEpisode()
        assertThat(t.consumeEndOfEpisode()).isTrue()
        assertThat(t.consumeEndOfEpisode()).isFalse()
        assertThat(t.state.value).isEqualTo(SleepTimerState.Off)
    }

    @Test fun extendDoesNothingWhenNotRunning() {
        val t = timer()
        t.stopAtEndOfEpisode()
        t.extend(SleepTimer.EXTEND_MS)
        assertThat(t.state.value).isEqualTo(SleepTimerState.EndOfEpisode)
    }

    private companion object {
        const val START = 1_800_000_000_000L
    }
}
