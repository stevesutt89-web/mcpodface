package com.steve.mcpodface.ui.settings

import com.steve.mcpodface.data.prefs.GlobalSettings

// Pure Kotlin: which global setting changes can delete downloads (PLAN.md §6.5), and the wording of
// the preview. No Android or Compose imports, so it is unit-tested on the JVM.
internal object CleanupChange {

    /**
     * True when moving from [before] to [after] keeps fewer downloads than before: a lower
     * "Keep latest N", a shorter max age (Never counts as the longest), or delete-played turned on
     * or switched to a shorter delay.
     */
    fun reducesRetention(before: GlobalSettings, after: GlobalSettings): Boolean =
        after.keepLatestCount < before.keepLatestCount ||
            maxAgeShortened(before.maxAgeDays, after.maxAgeDays) ||
            (!before.deletePlayed && after.deletePlayed) ||
            (after.deletePlayed && after.deletePlayedDelayHours < before.deletePlayedDelayHours)

    /** [after] is 0 for "Never"; any limit is shorter than Never. */
    private fun maxAgeShortened(before: Int, after: Int): Boolean = when {
        after == 0 -> false
        before == 0 -> true
        else -> after < before
    }

    /** "412 MB", "980 KB" or "1.2 GB" (decimal units, as Android's file-size formatting uses). */
    fun formatSize(bytes: Long): String = when {
        bytes < 1_000_000L -> "${maxOf(bytes, 0L) / 1_000} KB"
        bytes < 1_000_000_000L -> "${(bytes + 500_000) / 1_000_000} MB"
        else -> {
            val tenths = (bytes + 50_000_000) / 100_000_000
            "${tenths / 10}.${tenths % 10} GB"
        }
    }

    fun episodes(count: Int): String = if (count == 1) "1 episode" else "$count episodes"

    /** "This will remove 7 episodes (412 MB)." */
    fun message(count: Int, bytes: Long): String =
        "This will remove ${episodes(count)} (${formatSize(bytes)})."
}
