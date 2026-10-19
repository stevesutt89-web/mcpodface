package com.steve.mcpodface.ui.downloads

import com.steve.mcpodface.data.db.DownloadEntity
import com.steve.mcpodface.data.db.DownloadSource
import com.steve.mcpodface.data.db.PlayState
import com.steve.mcpodface.data.db.SortOrder
import com.steve.mcpodface.domain.policy.EffectiveSettings

// Pure Kotlin: when will the cleanup policy (PLAN.md §6.3) delete a finished download? Shown as the
// "Deletes in 18h · played" label on each Downloaded row (PLAN.md §8.7). Unit-tested without Android.

private const val HOUR_MS = 3_600_000L
private const val DAY_MS = 24 * HOUR_MS

/** The cleanup outcome for one downloaded episode. Absent (null) means no rule will delete it. */
sealed interface DeletionEta {
    /** The earliest rule that will fire, at [atMillis] (epoch millis). */
    data class Scheduled(
        val atMillis: Long,
        val reason: Reason,
        /** The podcast's maxAgeDays, for the "older than N days" label. Only meaningful for TOO_OLD. */
        val maxAgeDays: Int = 0,
    ) : DeletionEta

    /** Starred episodes are never deleted (PLAN.md §6.3). */
    data class Kept(val reason: KeptReason = KeptReason.STARRED) : DeletionEta

    enum class Reason { PLAYED, TOO_OLD }

    enum class KeptReason { STARRED }
}

/**
 * When the earliest cleanup rule will delete this download, or null when none will (PLAN.md §6.3):
 *
 * - R1: deletePlayed is on, the episode is PLAYED, and it fires at playedAt + deletePlayedDelayHours.
 * - R2: maxAgeDays > 0 and it fires at (pubDate for AUTO + NEWEST_FIRST, else downloadedAt) + maxAgeDays.
 *   A download with no downloadedAt has no R2 date.
 *
 * Starred episodes are kept whatever the rules say. [now] is accepted so callers pass the same
 * clock reading they use for labels; the returned instant does not depend on it.
 */
@Suppress("UNUSED_PARAMETER")
fun deletionEta(
    download: DownloadEntity,
    playState: PlayState,
    playedAt: Long?,
    pubDate: Long,
    isStarred: Boolean,
    settings: EffectiveSettings,
    now: Long,
): DeletionEta? {
    if (isStarred) return DeletionEta.Kept()
    val rules = buildList {
        if (settings.deletePlayed && playState == PlayState.PLAYED && playedAt != null) {
            add(
                DeletionEta.Scheduled(
                    atMillis = playedAt + settings.deletePlayedDelayHours * HOUR_MS,
                    reason = DeletionEta.Reason.PLAYED,
                ),
            )
        }
        if (settings.maxAgeDays > 0) {
            val from = if (download.source == DownloadSource.AUTO && settings.sortOrder == SortOrder.NEWEST_FIRST) {
                pubDate
            } else {
                download.downloadedAt
            }
            if (from != null) {
                add(
                    DeletionEta.Scheduled(
                        atMillis = from + settings.maxAgeDays * DAY_MS,
                        reason = DeletionEta.Reason.TOO_OLD,
                        maxAgeDays = settings.maxAgeDays,
                    ),
                )
            }
        }
    }
    // On a tie the first rule (played) wins, because it is listed first.
    return rules.minByOrNull { it.atMillis }
}

/**
 * "Deletes soon · played", "Deletes in 18h · played", "Deletes in 3 days · older than 30 days",
 * "Kept · starred". Null when no rule will delete the episode, so the row shows no label.
 */
fun formatDeletionLabel(eta: DeletionEta?, now: Long): String? = when (eta) {
    null -> null
    is DeletionEta.Kept -> "Kept · starred"
    is DeletionEta.Scheduled -> "${formatTimeUntil(eta.atMillis - now)} · ${formatReason(eta)}"
}

/** "Deletes soon" under an hour (including overdue), "Deletes in 18h" under a day, then days. */
internal fun formatTimeUntil(remainingMs: Long): String = when {
    remainingMs < HOUR_MS -> "Deletes soon"
    remainingMs < DAY_MS -> "Deletes in ${remainingMs / HOUR_MS}h"
    else -> {
        val days = remainingMs / DAY_MS
        "Deletes in $days ${if (days == 1L) "day" else "days"}"
    }
}

private fun formatReason(eta: DeletionEta.Scheduled): String = when (eta.reason) {
    DeletionEta.Reason.PLAYED -> "played"
    DeletionEta.Reason.TOO_OLD -> "older than ${eta.maxAgeDays} ${if (eta.maxAgeDays == 1) "day" else "days"}"
}
