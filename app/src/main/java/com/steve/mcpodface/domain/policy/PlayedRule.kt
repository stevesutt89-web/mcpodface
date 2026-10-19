package com.steve.mcpodface.domain.policy

/** When an episode counts as PLAYED (PLAN.md §6.0). */
object PlayedRule {
    /**
     * True once [positionMs] is past [markPlayedPercent]% of [durationMs], or has reached the
     * skip-outro point. Use ExoPlayer's duration when the feed's is missing or wrong.
     */
    fun isPlayed(positionMs: Long, durationMs: Long, markPlayedPercent: Int, skipOutroSec: Int = 0): Boolean {
        if (durationMs <= 0) return false
        if (skipOutroSec > 0 && positionMs >= durationMs - skipOutroSec * 1000L) return true
        return positionMs * 100 > durationMs * markPlayedPercent
    }
}
