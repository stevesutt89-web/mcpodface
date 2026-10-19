package com.steve.mcpodface.ui.podcastsettings

import com.steve.mcpodface.data.db.BackfillStateEntity
import com.steve.mcpodface.data.db.BackfillStatus
import com.steve.mcpodface.data.network.PersistedRateLimiter
import com.steve.mcpodface.data.prefs.GlobalSettings
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// Pure Kotlin status strings for the archive UI (PLAN.md §7.4, §8.4, §8.10). No Android or Compose
// imports, so every state can be unit-tested with a fixed clock, zone and locale.

/** Where a podcast is in its archive backfill, as the sheet and Settings describe it. */
enum class ArchivePhase { OFF, BACKFILLING, WAITING, COMPLETE, PAUSED, FAILED }

/** The status line and an optional second line for one podcast. */
data class PodcastArchiveStatus(
    val phase: ArchivePhase,
    /** "Archive: 412 of ~1,200 episodes · backfilling" */
    val headline: String,
    /** Reason or next step, e.g. "Last error: HTTP 503. Next try after 3:40 pm." */
    val detail: String?,
)

/** Podcast Index usage for today, read from [GlobalSettings] with the day rule applied. */
data class ApiUsage(val requestsToday: Int, val dailyBudget: Int, val pausedUntil: Long)

/** Global archive totals for Settings → Search & archive. */
data class ArchiveProgress(val complete: Int, val total: Int)

private const val MAX_REASON_CHARS = 80

/** Requests made today. The stored count is only for [GlobalSettings.apiBudgetDay]; any other day is 0. */
fun apiUsage(s: GlobalSettings, now: Long, zone: ZoneId = ZoneId.systemDefault()): ApiUsage {
    val today = PersistedRateLimiter.epochDay(now, zone)
    return ApiUsage(
        requestsToday = if (s.apiBudgetDay == today) s.apiRequestsToday else 0,
        dailyBudget = s.apiDailyBudget,
        pausedUntil = s.apiPausedUntil,
    )
}

/**
 * The per-podcast status. [state] is null before the podcast has ever been backfilled. [backfillOn]
 * is the effective switch (the global switch and this podcast's override together).
 *
 * Order matters: a finished or paused archive says so even when the API budget is also used up.
 * "Waiting" is only for the Podcast Index budget or its 429 pause, which both hold back the API step
 * only after the free RSS paging is done. A failed podcast stays FAILED while it backs off.
 */
fun podcastArchiveStatus(
    state: BackfillStateEntity?,
    episodeCount: Int,
    indexEpisodeCount: Int?,
    backfillOn: Boolean,
    usage: ApiUsage,
    now: Long,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): PodcastArchiveStatus {
    val indexStillNeeded = state != null && state.rssPagingDone && !state.indexDone
    val (phase, detail) = when {
        !backfillOn -> ArchivePhase.OFF to "Archive backfill is off for this podcast"
        state?.status == BackfillStatus.COMPLETE -> ArchivePhase.COMPLETE to null
        state?.status == BackfillStatus.PAUSED -> ArchivePhase.PAUSED to "Paused. Resume to keep filling the archive."
        state?.status == BackfillStatus.FAILED -> ArchivePhase.FAILED to failedDetail(state, now, zone, locale)
        indexStillNeeded && usage.pausedUntil > now ->
            ArchivePhase.WAITING to "Podcast Index paused after a rate limit until ${clockTime(usage.pausedUntil, zone, locale)}"
        indexStillNeeded && usage.requestsToday >= PersistedRateLimiter.backfillAllowance(usage.dailyBudget) ->
            ArchivePhase.WAITING to "Today's Podcast Index budget is used. Continues tomorrow."
        state == null || state.status == BackfillStatus.PENDING -> ArchivePhase.BACKFILLING to "Queued for the next run"
        else -> ArchivePhase.BACKFILLING to null
    }
    return PodcastArchiveStatus(
        phase = phase,
        headline = "Archive: ${episodeCountLine(episodeCount, indexEpisodeCount)} · ${phaseLabel(phase)}",
        detail = detail,
    )
}

/** "412 of ~1,200 episodes" when the Podcast Index size is known, otherwise "412 episodes". */
fun episodeCountLine(episodeCount: Int, indexEpisodeCount: Int?): String =
    if (indexEpisodeCount != null && indexEpisodeCount > 0) {
        "${formatCount(episodeCount)} of ~${formatCount(indexEpisodeCount)} episodes"
    } else {
        "${formatCount(episodeCount)} episodes"
    }

fun phaseLabel(phase: ArchivePhase): String = when (phase) {
    ArchivePhase.OFF -> "off"
    ArchivePhase.BACKFILLING -> "backfilling"
    ArchivePhase.WAITING -> "waiting"
    ArchivePhase.COMPLETE -> "complete"
    ArchivePhase.PAUSED -> "paused"
    ArchivePhase.FAILED -> "failed"
}

/** Counts subscribed podcasts whose archive is COMPLETE. Rows for unsubscribed podcasts are ignored. */
fun archiveProgress(subscribedIds: Collection<Long>, states: Collection<BackfillStateEntity>): ArchiveProgress {
    val ids = subscribedIds.toSet()
    val complete = states.count { it.podcastId in ids && it.status == BackfillStatus.COMPLETE }
    return ArchiveProgress(complete = complete, total = ids.size)
}

/** "12 of 15 podcasts complete". */
fun archiveProgressLine(progress: ArchiveProgress): String = when {
    progress.total == 0 -> "No subscriptions yet"
    else -> {
        val noun = if (progress.total == 1) "podcast" else "podcasts"
        "${progress.complete} of ${progress.total} $noun complete"
    }
}

/** "23 of 100". */
fun requestsLine(usage: ApiUsage): String = "${usage.requestsToday} of ${usage.dailyBudget}"

/** "Paused until 3:40 pm" while the 429 pause runs, otherwise null. */
fun pausedUntilLine(
    usage: ApiUsage,
    now: Long,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): String? = if (usage.pausedUntil > now) "Paused until ${clockTime(usage.pausedUntil, zone, locale)}" else null

/** "3:40 pm" in the phone's zone and locale. */
fun clockTime(epochMs: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String =
    Instant.ofEpochMilli(epochMs)
        .atZone(zone)
        .format(DateTimeFormatter.ofPattern("h:mm a", locale))
        .lowercase(locale)

/** "1,200". Grouping is fixed to English so the digits read the same on every phone. */
fun formatCount(n: Int): String = String.format(Locale.ENGLISH, "%,d", n)

private fun failedDetail(state: BackfillStateEntity, now: Long, zone: ZoneId, locale: Locale): String {
    val reason = state.lastError?.lineSequence()?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        ?.let { if (it.length > MAX_REASON_CHARS) it.take(MAX_REASON_CHARS - 1) + "…" else it }
        ?: "unknown error"
    val retry = state.nextEligibleAt?.takeIf { it > now }
        ?.let { " Next try after ${clockTime(it, zone, locale)}." }
        .orEmpty()
    return "Last error: $reason.$retry"
}
