package com.steve.mcpodface.ui.podcastsettings

import com.google.common.truth.Truth.assertThat
import com.steve.mcpodface.data.db.BackfillStateEntity
import com.steve.mcpodface.data.db.BackfillStatus
import com.steve.mcpodface.data.network.PersistedRateLimiter
import com.steve.mcpodface.data.prefs.GlobalSettings
import org.junit.Test
import java.time.ZoneId
import java.util.Locale

class ArchiveStatusTest {
    private val zone: ZoneId = ZoneId.of("UTC")
    private val locale = Locale.US

    // 2027-01-15 08:00 UTC. Pauses and retries below end at 3:40 pm the same day.
    private val now = 1_800_000_000_000L
    private val threeFortyPm = now + 7 * 3_600_000L + 40 * 60_000L

    private val freshUsage = ApiUsage(requestsToday = 0, dailyBudget = 100, pausedUntil = 0L)

    private fun state(
        status: BackfillStatus = BackfillStatus.PENDING,
        rssPagingDone: Boolean = false,
        indexDone: Boolean = false,
        lastError: String? = null,
        nextEligibleAt: Long? = null,
    ) = BackfillStateEntity(
        podcastId = 1L,
        status = status,
        rssPagingDone = rssPagingDone,
        indexDone = indexDone,
        lastError = lastError,
        nextEligibleAt = nextEligibleAt,
    )

    private fun status(
        state: BackfillStateEntity? = null,
        episodes: Int = 412,
        index: Int? = 1_200,
        on: Boolean = true,
        usage: ApiUsage = freshUsage,
    ) = podcastArchiveStatus(
        state = state,
        episodeCount = episodes,
        indexEpisodeCount = index,
        backfillOn = on,
        usage = usage,
        now = now,
        zone = zone,
        locale = locale,
    )

    @Test fun backfillingWithKnownSizeShowsOfApproximateTotal() {
        val s = status(state(status = BackfillStatus.IN_PROGRESS, rssPagingDone = false))

        assertThat(s.phase).isEqualTo(ArchivePhase.BACKFILLING)
        assertThat(s.headline).isEqualTo("Archive: 412 of ~1,200 episodes · backfilling")
        assertThat(s.detail).isNull()
    }

    @Test fun unknownSizeLeavesOutTheTotal() {
        assertThat(status(state(status = BackfillStatus.IN_PROGRESS), index = null).headline)
            .isEqualTo("Archive: 412 episodes · backfilling")
        assertThat(status(state(status = BackfillStatus.IN_PROGRESS), index = 0).headline)
            .isEqualTo("Archive: 412 episodes · backfilling")
    }

    @Test fun podcastWithNoRowYetIsQueued() {
        val s = status(state = null)

        assertThat(s.phase).isEqualTo(ArchivePhase.BACKFILLING)
        assertThat(s.detail).isEqualTo("Queued for the next run")
    }

    @Test fun pendingRowIsQueued() {
        val s = status(state(status = BackfillStatus.PENDING))

        assertThat(s.phase).isEqualTo(ArchivePhase.BACKFILLING)
        assertThat(s.detail).isEqualTo("Queued for the next run")
    }

    @Test fun completeSaysComplete() {
        val s = status(state(status = BackfillStatus.COMPLETE, rssPagingDone = true, indexDone = true), episodes = 1_200)

        assertThat(s.phase).isEqualTo(ArchivePhase.COMPLETE)
        assertThat(s.headline).isEqualTo("Archive: 1,200 of ~1,200 episodes · complete")
        assertThat(s.detail).isNull()
    }

    @Test fun completeWinsOverAnExhaustedBudget() {
        val exhausted = ApiUsage(requestsToday = 100, dailyBudget = 100, pausedUntil = threeFortyPm)
        val s = status(state(status = BackfillStatus.COMPLETE, rssPagingDone = true, indexDone = true), usage = exhausted)

        assertThat(s.phase).isEqualTo(ArchivePhase.COMPLETE)
    }

    @Test fun pausedSaysPaused() {
        val s = status(state(status = BackfillStatus.PAUSED))

        assertThat(s.phase).isEqualTo(ArchivePhase.PAUSED)
        assertThat(s.headline).isEqualTo("Archive: 412 of ~1,200 episodes · paused")
        assertThat(s.detail).isEqualTo("Paused. Resume to keep filling the archive.")
    }

    @Test fun failedShowsTheReasonAndNextTry() {
        val s = status(state(status = BackfillStatus.FAILED, lastError = "HTTP 503", nextEligibleAt = threeFortyPm))

        assertThat(s.phase).isEqualTo(ArchivePhase.FAILED)
        assertThat(s.headline).isEqualTo("Archive: 412 of ~1,200 episodes · failed")
        assertThat(s.detail).isEqualTo("Last error: HTTP 503. Next try after 3:40 pm.")
    }

    @Test fun failedWithoutReasonOrRetryTimeSaysSo() {
        val s = status(state(status = BackfillStatus.FAILED, lastError = null, nextEligibleAt = now - 1))

        assertThat(s.phase).isEqualTo(ArchivePhase.FAILED)
        assertThat(s.detail).isEqualTo("Last error: unknown error.")
    }

    @Test fun failedReasonIsFirstLineAndShortened() {
        val long = "x".repeat(200)
        val s = status(state(status = BackfillStatus.FAILED, lastError = "$long\nstack trace line"))

        val reason = s.detail!!.removePrefix("Last error: ").removeSuffix(".")
        assertThat(reason).hasLength(80)
        assertThat(reason).endsWith("…")
        assertThat(s.detail).doesNotContain("stack trace")
    }

    @Test fun podcastIndexRateLimitIsWaiting() {
        val paused = freshUsage.copy(pausedUntil = threeFortyPm)
        val s = status(state(rssPagingDone = true, indexDone = false), usage = paused)

        assertThat(s.phase).isEqualTo(ArchivePhase.WAITING)
        assertThat(s.detail).isEqualTo("Podcast Index paused after a rate limit until 3:40 pm")
    }

    @Test fun exhaustedDailyBudgetIsWaiting() {
        val allowance = PersistedRateLimiter.backfillAllowance(100)
        val used = freshUsage.copy(requestsToday = allowance)
        val s = status(state(rssPagingDone = true, indexDone = false), usage = used)

        assertThat(s.phase).isEqualTo(ArchivePhase.WAITING)
        assertThat(s.detail).isEqualTo("Today's Podcast Index budget is used. Continues tomorrow.")
    }

    @Test fun budgetDoesNotHoldBackFreeRssPaging() {
        val used = freshUsage.copy(requestsToday = 100)
        val s = status(state(rssPagingDone = false, indexDone = false), usage = used)

        assertThat(s.phase).isEqualTo(ArchivePhase.BACKFILLING)
    }

    @Test fun pauseAndBudgetDoNotApplyOnceIndexIsDone() {
        val used = ApiUsage(requestsToday = 100, dailyBudget = 100, pausedUntil = threeFortyPm)
        val s = status(state(status = BackfillStatus.IN_PROGRESS, rssPagingDone = true, indexDone = true), usage = used)

        assertThat(s.phase).isEqualTo(ArchivePhase.BACKFILLING)
    }

    @Test fun offForThisPodcastWinsOverEverythingElse() {
        val s = status(state(status = BackfillStatus.FAILED, lastError = "boom"), on = false)

        assertThat(s.phase).isEqualTo(ArchivePhase.OFF)
        assertThat(s.headline).isEqualTo("Archive: 412 of ~1,200 episodes · off")
        assertThat(s.detail).isEqualTo("Archive backfill is off for this podcast")
    }

    @Test fun apiUsageCountsOnlyTodaysRequests() {
        val today = PersistedRateLimiter.epochDay(now, zone)
        val fresh = GlobalSettings(apiBudgetDay = today, apiRequestsToday = 23, apiDailyBudget = 100)
        val stale = fresh.copy(apiBudgetDay = today - 1)

        assertThat(apiUsage(fresh, now, zone).requestsToday).isEqualTo(23)
        assertThat(apiUsage(stale, now, zone).requestsToday).isEqualTo(0)
        assertThat(apiUsage(fresh, now, zone).dailyBudget).isEqualTo(100)
    }

    @Test fun archiveProgressCountsOnlySubscribedCompletePodcasts() {
        val subscribed = (1L..15L).toList()
        val states = (1L..12L).map { state(status = BackfillStatus.COMPLETE).copy(podcastId = it) } +
            BackfillStateEntity(podcastId = 99L, status = BackfillStatus.COMPLETE)

        val progress = archiveProgress(subscribed, states)

        assertThat(progress).isEqualTo(ArchiveProgress(complete = 12, total = 15))
        assertThat(archiveProgressLine(progress)).isEqualTo("12 of 15 podcasts complete")
    }

    @Test fun archiveProgressWithNoSubscriptionsOrOneSubscription() {
        assertThat(archiveProgressLine(archiveProgress(emptyList(), emptyList()))).isEqualTo("No subscriptions yet")
        assertThat(archiveProgressLine(ArchiveProgress(complete = 1, total = 1))).isEqualTo("1 of 1 podcast complete")
    }

    @Test fun requestsLineShowsUsedOfBudget() {
        assertThat(requestsLine(ApiUsage(requestsToday = 23, dailyBudget = 100, pausedUntil = 0L)))
            .isEqualTo("23 of 100")
    }

    @Test fun pausedUntilOnlyWhilePauseIsInTheFuture() {
        val paused = ApiUsage(requestsToday = 0, dailyBudget = 100, pausedUntil = threeFortyPm)
        val over = paused.copy(pausedUntil = now - 1)

        assertThat(pausedUntilLine(paused, now, zone, locale)).isEqualTo("Paused until 3:40 pm")
        assertThat(pausedUntilLine(over, now, zone, locale)).isNull()
        assertThat(pausedUntilLine(freshUsage, now, zone, locale)).isNull()
    }

    @Test fun clockTimeIsLowercaseTwelveHour() {
        assertThat(clockTime(threeFortyPm, zone, locale)).isEqualTo("3:40 pm")
        assertThat(clockTime(now, zone, locale)).isEqualTo("8:00 am")
    }

    @Test fun formatCountGroupsThousands() {
        assertThat(formatCount(412)).isEqualTo("412")
        assertThat(formatCount(1_200)).isEqualTo("1,200")
        assertThat(formatCount(1_234_567)).isEqualTo("1,234,567")
    }
}
