package com.steve.mcpodface.playback.auto

import com.steve.mcpodface.data.db.AppDatabase
import kotlinx.coroutines.CoroutineScope
import javax.inject.Inject

/**
 * Tells the car which lists changed, so Up Next and Downloads aren't stale until it reconnects
 * (docs/ANDROID-AUTO.md, task A6). PlaybackService starts it with session.notifyChildrenChanged (A7).
 */
class BrowseInvalidator @Inject constructor(
    private val db: AppDatabase,
) {
    /**
     * Watches the queue, downloads, episode play state and subscriptions in [scope], debounced, and
     * calls [notify] once per affected parent id (BrowseTree ids such as `upnext`, `podcast:<id>`).
     */
    fun start(scope: CoroutineScope, notify: (parentId: String) -> Unit) {}
}
