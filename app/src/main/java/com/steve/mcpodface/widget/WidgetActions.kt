package com.steve.mcpodface.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import com.steve.mcpodface.playback.episodeId

/** The episode a play/pause button belongs to: the widget's last played or loaded episode. */
internal val EpisodeIdKey = ActionParameters.Key<Long>("episodeId")

/**
 * Play/pause for [EpisodeIdKey]. If that episode is loaded, toggles it through the media session.
 * Otherwise it starts the episode through the player, which also handles the mobile-data check.
 */
class PlayPauseAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val episodeId = parameters[EpisodeIdKey] ?: return
        val toggled = withMediaController(context) { controller ->
            if (controller.currentMediaItem?.episodeId() == episodeId) {
                if (controller.isPlaying) controller.pause() else controller.play()
                true
            } else {
                false
            }
        }
        if (!toggled) widgetEntryPoint(context).playerController().play(episodeId)
    }
}

class SkipBackAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        withMediaController(context) { it.seekBack() }
    }
}

class SkipForwardAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        withMediaController(context) { it.seekForward() }
    }
}
