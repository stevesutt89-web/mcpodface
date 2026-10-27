package com.steve.mcpodface.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player

/**
 * Maps next/previous (Galaxy Buds double/triple press, car and Bluetooth buttons, the lock screen)
 * to skip forward/back by the user's intervals: in a podcast those buttons mean "skip a bit", not
 * "next episode", even though the playlist holds Up Next. Jumping to an Up Next episode (the car's
 * queue list) uses seekToDefaultPosition(index), which passes through unchanged. Also gives
 * connection errors a message people can act on: the car and system media controls show it as is.
 */
@OptIn(UnstableApi::class) // Media3 APIs marked unstable (ForwardingPlayer, skip silence, session tweaks)
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

    private var lastError: PlaybackException? = null
    private var shownError: PlaybackException? = null

    override fun getPlayerError(): PlaybackException? {
        val error = super.getPlayerError()
        if (error !== lastError) {
            lastError = error
            shownError = error?.let(::friendlyError)
        }
        return shownError
    }

    override fun seekToNext() = seekForward()
    override fun seekToNextMediaItem() = seekForward()
    override fun seekToPrevious() = seekBack()
    override fun seekToPreviousMediaItem() = seekBack()

    companion object {
        const val NO_CONNECTION_MESSAGE = "No connection. Downloaded episodes still play"

        /** [error] with a plain message when it is a lost or missing connection; otherwise unchanged. */
        fun friendlyError(error: PlaybackException): PlaybackException = when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            -> PlaybackException(NO_CONNECTION_MESSAGE, error, error.errorCode)
            else -> error
        }
    }
}
