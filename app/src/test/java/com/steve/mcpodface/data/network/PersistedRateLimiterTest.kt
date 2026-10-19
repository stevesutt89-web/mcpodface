package com.steve.mcpodface.data.network

import com.google.common.truth.Truth.assertThat
import com.steve.mcpodface.core.Clock
import com.steve.mcpodface.data.prefs.GlobalSettings
import com.steve.mcpodface.data.prefs.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class PersistedRateLimiterTest {
    private val zone = ZoneId.of("Australia/Sydney")
    private var now = at(2026, 10, 19, 9, 0)

    private val settings = object : SettingsRepository {
        val state = MutableStateFlow(GlobalSettings(apiDailyBudget = 10))
        override val settings = state
        override suspend fun update(transform: (GlobalSettings) -> GlobalSettings) =
            transform(state.value).also { state.value = it }
    }

    private fun limiter() = PersistedRateLimiter(settings, Clock { now }).also { it.zone = zone }

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int) =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    @Test fun backfillStopsAtEightyPercentButDiscoveryUsesTheRest() = runTest {
        val l = limiter()
        repeat(8) { assertThat(l.tryAcquire(ApiPurpose.BACKFILL)).isTrue() }
        assertThat(l.tryAcquire(ApiPurpose.BACKFILL)).isFalse()
        assertThat(l.tryAcquire(ApiPurpose.DISCOVERY)).isTrue()
        assertThat(l.tryAcquire(ApiPurpose.SUBSCRIBE)).isTrue()
        assertThat(l.tryAcquire(ApiPurpose.DISCOVERY)).isFalse()
        assertThat(settings.state.value.apiRequestsToday).isEqualTo(10)
    }

    @Test fun refusedRequestsAreNotCounted() = runTest {
        val l = limiter()
        repeat(8) { l.tryAcquire(ApiPurpose.BACKFILL) }
        repeat(5) { l.tryAcquire(ApiPurpose.BACKFILL) }
        assertThat(settings.state.value.apiRequestsToday).isEqualTo(8)
    }

    @Test fun countResetsAtLocalMidnight() = runTest {
        val l = limiter()
        repeat(10) { l.tryAcquire(ApiPurpose.DISCOVERY) }
        now = at(2026, 10, 19, 23, 59)
        assertThat(l.tryAcquire(ApiPurpose.DISCOVERY)).isFalse()
        now = at(2026, 10, 20, 0, 1)
        assertThat(l.tryAcquire(ApiPurpose.DISCOVERY)).isTrue()
        assertThat(settings.state.value.apiRequestsToday).isEqualTo(1)
    }

    @Test fun rateLimitPausesBackfillForADayButNotDiscovery() = runTest {
        val l = limiter()
        l.onRateLimited()
        assertThat(l.tryAcquire(ApiPurpose.BACKFILL)).isFalse()
        assertThat(l.tryAcquire(ApiPurpose.DISCOVERY)).isTrue()
        now += PersistedRateLimiter.PAUSE_MS
        assertThat(l.tryAcquire(ApiPurpose.BACKFILL)).isTrue()
    }

    @Test fun stateSurvivesANewInstance() = runTest {
        repeat(8) { limiter().tryAcquire(ApiPurpose.BACKFILL) }
        assertThat(limiter().tryAcquire(ApiPurpose.BACKFILL)).isFalse()
    }

    @Test fun allowanceRoundsDown() {
        assertThat(PersistedRateLimiter.backfillAllowance(100)).isEqualTo(80)
        assertThat(PersistedRateLimiter.backfillAllowance(25)).isEqualTo(20)
        assertThat(PersistedRateLimiter.backfillAllowance(1)).isEqualTo(0)
    }
}
