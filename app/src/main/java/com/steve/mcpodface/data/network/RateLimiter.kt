package com.steve.mcpodface.data.network

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
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

/** Placeholder until task 6.4 implements the persisted token bucket. */
@Singleton
class UnlimitedRateLimiter @Inject constructor() : RateLimiter {
    override suspend fun tryAcquire(purpose: ApiPurpose) = true
    override suspend fun onRateLimited() = Unit
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RateLimiterModule {
    @Binds abstract fun rateLimiter(impl: UnlimitedRateLimiter): RateLimiter
}
