package com.steve.mcpodface.playback

import com.steve.mcpodface.data.db.PlayState

/** Pure playback rules, kept out of the service so they can be unit-tested. */
object PlaybackDecisions {
    /** A pause longer than this triggers the resume rewind (PLAN.md §8.10). */
    const val LONG_PAUSE_MS = 5 * 60_000L
    const val RESUME_REWIND_MS = 3_000L

    /**
     * Where to start an episode. Finished episodes replay from the start; a fresh start honours the
     * podcast's skip-intro; resuming after a long pause rewinds a few seconds.
     */
    fun startPosition(
        savedPositionMs: Long,
        playState: PlayState,
        lastPlayedAt: Long?,
        now: Long,
        resumeRewind: Boolean,
        skipIntroSec: Int,
        durationMs: Long?,
    ): Long {
        var pos = if (playState == PlayState.PLAYED) 0L else savedPositionMs.coerceAtLeast(0)
        if (durationMs != null && durationMs > 0 && pos >= durationMs - 1_000) pos = 0
        if (pos == 0L) return skipIntroSec * 1_000L
        if (resumeRewind && lastPlayedAt != null && now - lastPlayedAt > LONG_PAUSE_MS) {
            pos = (pos - RESUME_REWIND_MS).coerceAtLeast(0)
        }
        return pos
    }
}
