package com.steve.mcpodface.playback.auto

import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.steve.mcpodface.R
import com.steve.mcpodface.core.Clock
import com.steve.mcpodface.data.db.AppDatabase
import com.steve.mcpodface.data.db.PodcastSettingsEntity
import com.steve.mcpodface.playback.PlaybackEngine
import com.steve.mcpodface.playback.SleepTimer
import com.steve.mcpodface.playback.SleepTimerState
import javax.inject.Inject

/**
 * The now-playing overflow buttons in the car (docs/ANDROID-AUTO.md, task A5): speed, star,
 * mark played & next, sleep timer. LibraryCallback.onCustomCommand sends AutoCommands.NOW_PLAYING_ACTIONS
 * here (wired in A7).
 */
@OptIn(UnstableApi::class) // CommandButton slots and icons
class CarActions @Inject constructor(
    private val db: AppDatabase,
    private val sleepTimer: SleepTimer,
    private val clock: Clock,
) {
    /**
     * Runs [action] for the current episode. [player] is the session's player (skip intervals
     * applied), [engine] the service's PlaybackEngine (for mark played & next).
     */
    suspend fun run(action: String, player: Player, engine: PlaybackEngine): SessionResult {
        if (action !in AutoCommands.NOW_PLAYING_ACTIONS) {
            return SessionResult(SessionError.ERROR_NOT_SUPPORTED)
        }
        val episodeId = player.currentMediaItem?.mediaId?.toLongOrNull()
            ?: return SessionResult(SessionError.ERROR_BAD_VALUE)
        when (action) {
            AutoCommands.SPEED -> {
                val next = nextSpeed(player.playbackParameters.speed)
                val podcastId = db.episodeDao().getById(episodeId)?.podcastId
                    ?: return SessionResult(SessionError.ERROR_BAD_VALUE)
                player.setPlaybackSpeed(next)
                val dao = db.podcastSettingsDao()
                val current = dao.get(podcastId) ?: PodcastSettingsEntity(podcastId = podcastId)
                dao.upsert(current.copy(playbackSpeed = next))
            }
            AutoCommands.STAR -> {
                val episode = db.episodeDao().getById(episodeId)
                    ?: return SessionResult(SessionError.ERROR_BAD_VALUE)
                db.episodeDao().setStarred(episodeId, !episode.isStarred)
            }
            AutoCommands.MARK_PLAYED_NEXT -> engine.completeNow()
            AutoCommands.SLEEP -> advanceSleep()
        }
        return SessionResult(SessionResult.RESULT_SUCCESS)
    }

    /**
     * The overflow buttons (SLOT_OVERFLOW) for the episode playing now, labelled with their current
     * value ("1.5×", "Starred", "Sleep 15 min"). Empty when nothing is loaded.
     */
    suspend fun overflowButtons(episodeId: Long?, player: Player): List<CommandButton> {
        if (episodeId == null) return emptyList()
        val starred = db.episodeDao().getById(episodeId)?.isStarred ?: false
        return listOf(
            overflow(
                AutoCommands.SPEED, CommandButton.ICON_PLAYBACK_SPEED,
                speedLabel(player.playbackParameters.speed),
            ),
            if (starred) {
                overflow(AutoCommands.STAR, CommandButton.ICON_STAR_FILLED, "Starred")
            } else {
                overflow(AutoCommands.STAR, CommandButton.ICON_STAR_UNFILLED, "Star")
            },
            overflow(AutoCommands.MARK_PLAYED_NEXT, CommandButton.ICON_CHECK_CIRCLE_FILLED, "Mark played"),
            CommandButton.Builder(CommandButton.ICON_UNDEFINED)
                .setCustomIconResId(R.drawable.ic_car_sleep)
                .setSessionCommand(AutoCommands.command(AutoCommands.SLEEP))
                .setDisplayName(sleepLabel(sleepTimer.state.value, clock.now()))
                .setSlots(CommandButton.SLOT_OVERFLOW)
                .build(),
        )
    }

    private fun overflow(action: String, icon: Int, name: String): CommandButton =
        CommandButton.Builder(icon)
            .setSessionCommand(AutoCommands.command(action))
            .setDisplayName(name)
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build()

    /** Off → 15 min → 30 min → end of episode → Off. A running timer is placed by time left. */
    private fun advanceSleep() {
        when (val state = sleepTimer.state.value) {
            SleepTimerState.Off -> sleepTimer.start(SHORT_MINUTES)
            is SleepTimerState.Running ->
                if (state.endsAt - clock.now() < MIDPOINT_MS) sleepTimer.start(LONG_MINUTES)
                else sleepTimer.stopAtEndOfEpisode()
            SleepTimerState.EndOfEpisode -> sleepTimer.cancel()
        }
    }

    private fun sleepLabel(state: SleepTimerState, now: Long): String = when (state) {
        SleepTimerState.Off -> "Sleep"
        SleepTimerState.EndOfEpisode -> "Sleep: end of episode"
        is SleepTimerState.Running ->
            if (state.endsAt - now < MIDPOINT_MS) "Sleep $SHORT_MINUTES min" else "Sleep $LONG_MINUTES min"
    }

    private companion object {
        val SPEED_STEPS = listOf(1.0f, 1.2f, 1.5f, 1.8f, 2.0f)
        const val SHORT_MINUTES = 15
        const val LONG_MINUTES = 30
        /** Between the two presets: a running timer with less time than this is the 15-minute one. */
        const val MIDPOINT_MS = 22 * 60_000L + 30_000L

        /**
         * The step after the nearest step at or above [current]; anything above the top step (or
         * the top step itself) wraps to 1.0.
         */
        fun nextSpeed(current: Float): Float {
            val index = SPEED_STEPS.indexOfFirst { it >= current - 0.001f }
            return if (index < 0) SPEED_STEPS.first() else SPEED_STEPS[(index + 1) % SPEED_STEPS.size]
        }

        /** 1.0 → "1×", 1.2 → "1.2×". */
        fun speedLabel(speed: Float): String =
            if (speed % 1f == 0f) "${speed.toInt()}×" else "$speed×"
    }
}
