package com.steve.mcpodface.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import com.steve.mcpodface.core.di.ApplicationScope
import com.steve.mcpodface.playback.PlayerController
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pushes playback changes to the home-screen widget. Only the episode and play state are watched,
 * so position ticks don't redraw the widget.
 */
@Singleton
class WidgetUpdater @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope private val appScope: CoroutineScope,
    private val playerController: PlayerController,
) {
    fun start() {
        appScope.launch {
            playerController.nowPlaying
                .map(::widgetPlaybackKey)
                .distinctUntilChanged()
                .collect { NowPlayingWidget().updateAll(context) }
        }
    }
}
