package com.steve.mcpodface.playback

import com.steve.mcpodface.data.db.PlayState

/** Pure playback rules, kept out of the service so they can be unit-tested. */
object PlaybackDecisions {
    /**
     * Where to start an episode. Finished episodes replay from the start; a fresh start honours the
     * podcast's skip-intro; resuming after a pause of at least [longPauseMinutes] rewinds
     * [rewindSec] seconds when [resumeRewind] is on (PLAN.md §8.10).
     */
    fun startPosition(
        savedPositionMs: Long,
        playState: PlayState,
        lastPlayedAt: Long?,
        now: Long,
        resumeRewind: Boolean,
        rewindSec: Int,
        longPauseMinutes: Int,
        skipIntroSec: Int,
        durationMs: Long?,
    ): Long {
        var pos = if (playState == PlayState.PLAYED) 0L else savedPositionMs.coerceAtLeast(0)
        if (durationMs != null && durationMs > 0 && pos >= durationMs - 1_000) pos = 0
        if (pos == 0L) return skipIntroSec * 1_000L
        if (resumeRewind && lastPlayedAt != null && now - lastPlayedAt >= longPauseMinutes * 60_000L) {
            pos = (pos - rewindSec * 1_000L).coerceAtLeast(0)
        }
        return pos
    }
}
