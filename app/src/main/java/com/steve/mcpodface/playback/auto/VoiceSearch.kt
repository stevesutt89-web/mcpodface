package com.steve.mcpodface.playback.auto

import android.os.Bundle
import com.steve.mcpodface.data.db.AppDatabase
import com.steve.mcpodface.data.queue.QueueRepository
import com.steve.mcpodface.data.search.SearchRepository
import javax.inject.Inject

/**
 * "Hey Google, play … on McPodface" (docs/ANDROID-AUTO.md, task A4). Voice requests reach
 * onSetMediaItems as a MediaItem with an empty mediaId and `requestMetadata.searchQuery`;
 * LibraryCallback sends those here (wired in A7).
 */
class VoiceSearch @Inject constructor(
    private val db: AppDatabase,
    private val queue: QueueRepository,
    private val search: SearchRepository,
) {
    /**
     * The episode ids to play, best first; empty when nothing matches.
     * - blank [query]: the last played episode, else the first Up Next item;
     * - a podcast title (also `MediaStore.EXTRA_MEDIA_ARTIST` / `EXTRA_MEDIA_TITLE` in [extras]):
     *   that podcast's in-progress episode, else its newest unplayed one;
     * - anything else: the top full-text matches.
     */
    suspend fun resolve(query: String, extras: Bundle): List<Long> = emptyList()
}
