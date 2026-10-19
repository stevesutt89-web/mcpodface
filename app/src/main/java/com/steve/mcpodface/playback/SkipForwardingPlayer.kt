package com.steve.mcpodface.playback

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player

/**
 * Maps next/previous (Galaxy Buds double/triple press, car and Bluetooth buttons, the lock screen)
 * to skip forward/back by the user's intervals, since we play one episode at a time.
 */
class SkipForwardingPlayer(player: Player) : ForwardingPlayer(player) {
    var skipBackMs: Long = 10_000
    var skipForwardMs: Long = 30_000

    override fun getAvailableCommands(): Player.Commands =
        super.getAvailableCommands().buildUpon()
            .addAll(
                Player.COMMAND_SEEK_BACK,
                Player.COMMAND_SEEK_FORWARD,
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_PREVIOUS,
            )
            .build()

    override fun isCommandAvailable(command: Int): Boolean = availableCommands.contains(command)

    override fun getSeekBackIncrement(): Long = skipBackMs
    override fun getSeekForwardIncrement(): Long = skipForwardMs

    override fun seekBack() = seekTo((currentPosition - skipBackMs).coerceAtLeast(0))

    override fun seekForward() {
        val target = currentPosition + skipForwardMs
        val d = duration
        seekTo(if (d > 0) target.coerceAtMost(d) else target)
    }

    override fun seekToNext() = seekForward()
    override fun seekToNextMediaItem() = seekForward()
    override fun seekToPrevious() = seekBack()
    override fun seekToPreviousMediaItem() = seekBack()
}
