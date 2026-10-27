package com.steve.mcpodface.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps the player's playlist as the playing episode followed by Up Next (docs/ANDROID-AUTO.md,
 * A12), so the car's queue button lists Up Next and can jump to any of it, and the player moves on
 * to the next Up Next episode by itself at the end of one. Episodes played earlier are dropped from
 * the front. Up Next (QueueRepository) stays the source of truth; this only mirrors it.
 */
internal class UpNextTimeline(
    private val player: Player,
    private val resolve: suspend (Long) -> MediaItem?,
) {
    private val mutex = Mutex()

    /** Rebuilds the items after the playing one from [queueIds]. Does nothing when nothing is loaded. */
    suspend fun sync(queueIds: List<Long>) = mutex.withLock {
        val current = player.currentMediaItem?.episodeId() ?: return@withLock
        val wanted = upNextAfter(current, queueIds)
        if (inSync(player.currentMediaItemIndex, playlistIds(), wanted)) return@withLock
        val items = wanted.mapNotNull { resolve(it) }
        // The player may have moved on while the items resolved; its transition starts a fresh sync.
        if (player.currentMediaItem?.episodeId() != current) return@withLock
        val index = player.currentMediaItemIndex
        if (index + 1 < player.mediaItemCount) player.removeMediaItems(index + 1, player.mediaItemCount)
        if (index > 0) player.removeMediaItems(0, index)
        if (items.isNotEmpty()) player.addMediaItems(items)
    }

    private fun playlistIds(): List<Long?> = List(player.mediaItemCount) { player.getMediaItemAt(it).episodeId() }

    companion object {
        /** What follows the playing episode: Up Next in order, without the playing episode itself. */
        fun upNextAfter(currentId: Long, queueIds: List<Long>): List<Long> = queueIds.filter { it != currentId }

        /** True when the playing item is first and the rest of the playlist is exactly [wanted]. */
        fun inSync(currentIndex: Int, playlistIds: List<Long?>, wanted: List<Long>): Boolean =
            currentIndex == 0 && playlistIds.drop(1) == wanted
    }
}
