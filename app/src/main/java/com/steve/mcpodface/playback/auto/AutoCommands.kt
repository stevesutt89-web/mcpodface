package com.steve.mcpodface.playback.auto

import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.SessionCommand
import com.google.common.collect.ImmutableList
import com.steve.mcpodface.R

/**
 * Every custom session command the car (and notification, watch) can send (docs/ANDROID-AUTO.md).
 * They are all granted in LibraryCallback.onConnectAsync: a command that isn't granted is silently
 * ignored by Media3, so add new ones to [all].
 */
@OptIn(UnstableApi::class) // CommandButton slots and icons
object AutoCommands {
    // Now playing: back and forward slots. Action strings kept from before the car work.
    const val SKIP_BACK = "com.steve.mcpodface.SKIP_BACK"
    const val SKIP_FORWARD = "com.steve.mcpodface.SKIP_FORWARD"

    // Now playing: overflow (task A5).
    const val SPEED = "com.steve.mcpodface.car.SPEED"
    const val STAR = "com.steve.mcpodface.car.STAR"
    const val MARK_PLAYED_NEXT = "com.steve.mcpodface.car.MARK_PLAYED_NEXT"
    const val SLEEP = "com.steve.mcpodface.car.SLEEP"

    // Row actions ("custom browse actions", task A8). Auto passes the row's mediaId (the episode id)
    // in args under MediaConstants.EXTRA_KEY_MEDIA_ID.
    const val ROW_UP_NEXT = "com.steve.mcpodface.car.ROW_UP_NEXT"
    const val ROW_DOWNLOAD = "com.steve.mcpodface.car.ROW_DOWNLOAD"
    const val ROW_STAR = "com.steve.mcpodface.car.ROW_STAR"
    const val ROW_MARK_PLAYED = "com.steve.mcpodface.car.ROW_MARK_PLAYED"

    val NOW_PLAYING_ACTIONS = listOf(SPEED, STAR, MARK_PLAYED_NEXT, SLEEP)
    val ROW_ACTIONS = listOf(ROW_UP_NEXT, ROW_DOWNLOAD, ROW_STAR, ROW_MARK_PLAYED)

    fun command(action: String) = SessionCommand(action, Bundle.EMPTY)

    /** Every custom command, for onConnectAsync. */
    val all: List<SessionCommand> =
        (listOf(SKIP_BACK, SKIP_FORWARD) + NOW_PLAYING_ACTIONS + ROW_ACTIONS).map(::command)

    /**
     * Skip buttons are custom session commands rather than player commands: Android Auto only runs
     * them as custom actions (a player-command button in the back/forward slot also removes the
     * standard skip actions it would otherwise use).
     */
    val skipButtons: ImmutableList<CommandButton> = ImmutableList.of(
        CommandButton.Builder(CommandButton.ICON_SKIP_BACK)
            .setSessionCommand(command(SKIP_BACK))
            .setDisplayName("Skip back")
            .setSlots(CommandButton.SLOT_BACK)
            .build(),
        CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD)
            .setSessionCommand(command(SKIP_FORWARD))
            .setDisplayName("Skip forward")
            .setSlots(CommandButton.SLOT_FORWARD)
            .build(),
    )

    /**
     * Row action buttons for MediaLibrarySession.Builder.setCommandButtonsForMediaItems (wired in A7).
     * Rows opt in with MediaMetadata.Builder.setSupportedCommands(ROW_ACTIONS) (task A1).
     */
    val rowButtons: List<CommandButton> = listOf(
        rowButton(ROW_UP_NEXT, CommandButton.ICON_QUEUE_ADD, "Up Next"),
        CommandButton.Builder(CommandButton.ICON_UNDEFINED)
            .setCustomIconResId(R.drawable.ic_car_download)
            .setSessionCommand(command(ROW_DOWNLOAD))
            .setDisplayName("Download")
            .build(),
        rowButton(ROW_STAR, CommandButton.ICON_STAR_UNFILLED, "Star"),
        rowButton(ROW_MARK_PLAYED, CommandButton.ICON_CHECK_CIRCLE_UNFILLED, "Mark played"),
    )

    private fun rowButton(action: String, icon: Int, name: String) = CommandButton.Builder(icon)
        .setSessionCommand(command(action))
        .setDisplayName(name)
        .build()
}
