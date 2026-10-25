package com.steve.mcpodface.playback.auto

import com.steve.mcpodface.data.db.AppDatabase
import com.steve.mcpodface.data.db.EpisodeListItem
import com.steve.mcpodface.data.db.PlayState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Tells the car which lists changed, so Up Next and Downloads aren't stale until it reconnects
 * (docs/ANDROID-AUTO.md, task A6). PlaybackService starts it with session.notifyChildrenChanged (A7).
 *
 * Every watched flow skips its first emission (the car already has the first load), and bursts of
 * changes are coalesced by a 500 ms debounce. Parent ids match BrowseTree's node ids.
 */
@OptIn(FlowPreview::class)
class BrowseInvalidator @Inject constructor(
    private val db: AppDatabase,
) {
    private var job: Job? = null

    /**
     * Watches the queue, downloads, starred episodes, episode play state, subscriptions and playlists
     * in [scope], debounced, and calls [notify] once per affected parent id. A second call replaces
     * the first watcher rather than adding another.
     */
    @Synchronized
    fun start(scope: CoroutineScope, notify: (parentId: String) -> Unit) {
        job?.cancel()
        job = scope.launch {
            launch { upNext().notifyWhenChanged(UP_NEXT, notify) }
            launch { db.downloadDao().observeAll().map { list -> list.map { it.episodeId to it.state } }.notifyWhenChanged(DOWNLOADS, notify) }
            launch { db.episodeDao().observeStarred().map { list -> list.map { it.episode.id } }.notifyWhenChanged(STARRED, notify) }
            launch { db.podcastDao().observeSubscribed().map { list -> list.map { it.id } }.notifyWhenChanged(PODCASTS, notify) }
            launch { db.playlistDao().observeAll().notifyWhenChanged(PLAYLISTS, notify) }
            launch { watchEpisodes(notify) }
        }
    }

    private fun upNext(): Flow<List<Long>> = db.queueDao().observe().map { queue -> queue.map { it.episodeId } }

    /**
     * Subscribed episodes, grouped by podcast, compared as (id, playState, positionMs) sets. A change
     * in play state or position, or the episode set itself (a refresh adding episodes), shows up
     * here. Download and queue changes also re-run the Latest query, but leave the snapshot equal,
     * so they notify nothing.
     */
    private suspend fun watchEpisodes(notify: (String) -> Unit) {
        var initial: Map<Long, Set<Mark>>? = null
        var previous: Map<Long, Set<Mark>>? = null
        db.episodeDao()
            .observeLatest(since = Long.MIN_VALUE, limit = Int.MAX_VALUE, unplayedOnly = false, downloadedOnly = false)
            .map { snapshot(it) }
            .distinctUntilChanged()
            // The first raw emission is the baseline. The debounced value is then compared with it, so
            // no change made in the first 500 ms is lost.
            .onEach { if (initial == null) initial = it }
            .debounce(DEBOUNCE_MS)
            .collect { latest ->
                val before = previous ?: initial ?: latest
                previous = latest
                val changed = (before.keys + latest.keys).filter { before[it].orEmpty() != latest[it].orEmpty() }
                if (changed.isEmpty()) return@collect
                notify(LATEST)
                notify(IN_PROGRESS)
                notify(HISTORY)
                for (podcastId in changed) notify(PODCAST_PREFIX + podcastId)
            }
    }

    private suspend fun <T> Flow<T>.notifyWhenChanged(parentId: String, notify: (String) -> Unit) {
        distinctUntilChanged()
            .drop(1)
            .debounce(DEBOUNCE_MS)
            .collect { notify(parentId) }
    }

    private fun snapshot(items: List<EpisodeListItem>): Map<Long, Set<Mark>> =
        items
            .groupBy({ it.episode.podcastId }, { Mark(it.episode.id, it.episode.playState, it.episode.positionMs) })
            .mapValues { it.value.toSet() }

    private data class Mark(val id: Long, val playState: PlayState, val positionMs: Long)

    private companion object {
        const val DEBOUNCE_MS = 500L
        // BrowseTree node ids. Some are not in BrowseTree yet (A2 adds them).
        const val UP_NEXT = "upnext"
        const val DOWNLOADS = "downloads"
        const val LATEST = "latest"
        const val IN_PROGRESS = "inprogress"
        const val HISTORY = "history"
        const val STARRED = "starred"
        const val PODCASTS = "podcasts"
        const val PLAYLISTS = "playlists"
        const val PODCAST_PREFIX = "podcast:"
    }
}
