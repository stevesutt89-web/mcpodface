package com.steve.mcpodface.data.network

import com.steve.mcpodface.core.Clock
import com.steve.mcpodface.data.prefs.GlobalSettings
import com.steve.mcpodface.data.prefs.SettingsRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** Why a Podcast Index request is being made. Discovery always keeps 20% of the budget (PLAN.md §7.4). */
enum class ApiPurpose { DISCOVERY, SUBSCRIBE, BACKFILL }

/** Gate for every Podcast Index call. */
interface RateLimiter {
    /** Reserves one request. false = over budget or paused: don't make the call. */
    suspend fun tryAcquire(purpose: ApiPurpose): Boolean

    /** Report an HTTP 429 so backfill pauses for 24h. */
    suspend fun onRateLimited()
}

/** Never says no. For tests and previews. */
class UnlimitedRateLimiter @Inject constructor() : RateLimiter {
    override suspend fun tryAcquire(purpose: ApiPurpose) = true
    override suspend fun onRateLimited() = Unit
}

/**
 * Daily token bucket persisted in settings (PLAN.md §7.4). The count resets at local midnight.
 * Backfill may use only 80% of the budget, so discovery and subscribing always have some left, and
 * backfill stops for 24h after a 429.
 */
@Singleton
class PersistedRateLimiter @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val clock: Clock,
) : RateLimiter {
    private val mutex = Mutex()

    /** Tests set a fixed zone; the app uses the phone's. */
    internal var zone: ZoneId = ZoneId.systemDefault()

    override suspend fun tryAcquire(purpose: ApiPurpose): Boolean = mutex.withLock {
        var granted = false
        settingsRepository.update { s ->
            val (next, ok) = acquire(s, purpose, clock.now(), zone)
            granted = ok
            next
        }
        granted
    }

    override suspend fun onRateLimited() = mutex.withLock {
        settingsRepository.update { it.copy(apiPausedUntil = clock.now() + PAUSE_MS) }
        Unit
    }

    companion object {
        const val PAUSE_MS = 24 * 60 * 60_000L
        const val DISCOVERY_RESERVE = 0.2

        fun epochDay(millis: Long, zone: ZoneId): Long =
            Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().toEpochDay()

        /** Requests backfill may make per day: the budget less the discovery reserve. */
        fun backfillAllowance(budget: Int): Int = (budget * (1 - DISCOVERY_RESERVE)).toInt()

        /** Pure decision: the settings after this request, and whether it may go ahead. */
        fun acquire(s: GlobalSettings, purpose: ApiPurpose, now: Long, zone: ZoneId): Pair<GlobalSettings, Boolean> {
            val today = epochDay(now, zone)
            val used = if (s.apiBudgetDay == today) s.apiRequestsToday else 0
            val ok = when (purpose) {
                ApiPurpose.BACKFILL -> now >= s.apiPausedUntil && used < backfillAllowance(s.apiDailyBudget)
                ApiPurpose.DISCOVERY, ApiPurpose.SUBSCRIBE -> used < s.apiDailyBudget
            }
            val next = s.copy(apiBudgetDay = today, apiRequestsToday = if (ok) used + 1 else used)
            return next to ok
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RateLimiterModule {
    @Binds abstract fun rateLimiter(impl: PersistedRateLimiter): RateLimiter
}
