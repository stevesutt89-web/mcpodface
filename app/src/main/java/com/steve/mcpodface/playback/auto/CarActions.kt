package com.steve.mcpodface.playback.auto

import androidx.media3.common.Player
import androidx.media3.session.CommandButton
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.steve.mcpodface.data.db.AppDatabase
import com.steve.mcpodface.playback.PlaybackEngine
import com.steve.mcpodface.playback.SleepTimer
import javax.inject.Inject

/**
 * The now-playing overflow buttons in the car (docs/ANDROID-AUTO.md, task A5): speed, star,
 * mark played & next, sleep timer. LibraryCallback.onCustomCommand sends AutoCommands.NOW_PLAYING_ACTIONS
 * here (wired in A7).
 */
class CarActions @Inject constructor(
    private val db: AppDatabase,
    private val sleepTimer: SleepTimer,
) {
    /**
     * Runs [action] for the current episode. [player] is the session's player (skip intervals
     * applied), [engine] the service's PlaybackEngine (for mark played & next).
     */
    suspend fun run(action: String, player: Player, engine: PlaybackEngine): SessionResult =
        SessionResult(SessionError.ERROR_NOT_SUPPORTED)

    /**
     * The overflow buttons (SLOT_OVERFLOW) for the episode playing now, labelled with their current
     * value ("1.5×", "Starred", "Sleep 15 min"). Empty when nothing is loaded.
     */
    suspend fun overflowButtons(episodeId: Long?, player: Player): List<CommandButton> = emptyList()
}
