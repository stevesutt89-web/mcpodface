package com.steve.mcpodface.playback

import com.steve.mcpodface.core.Clock
import com.steve.mcpodface.data.db.ListeningSessionEntity

/**
 * Turns play/pause events into listening sessions for history and stats (PLAN.md §13, task 4.5).
 * A session runs from play to pause, episode change or service stop. [listenedMs] is wall-clock
 * time, so time saved by speed = listenedMs * (speed - 1). Sessions under [MIN_SESSION_MS] are
 * dropped (accidental taps).
 */
class ListeningSessionRecorder(private val clock: Clock) {
    private var episodeId: Long? = null
    private var startedAt = 0L
    private var speed = 1f

    val isRecording: Boolean get() = episodeId != null

    fun start(episodeId: Long, speed: Float) {
        if (this.episodeId == episodeId) return
        this.episodeId = episodeId
        this.startedAt = clock.now()
        this.speed = speed
    }

    /** Ends the current session; returns it if it is long enough to keep. */
    fun stop(): ListeningSessionEntity? {
        val id = episodeId ?: return null
        episodeId = null
        val listened = clock.now() - startedAt
        if (listened < MIN_SESSION_MS) return null
        return ListeningSessionEntity(episodeId = id, startedAt = startedAt, listenedMs = listened, speed = speed)
    }

    companion object {
        const val MIN_SESSION_MS = 5_000L
    }
}
