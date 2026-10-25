package com.steve.mcpodface.playback.auto

import android.os.Bundle
import android.provider.MediaStore
import com.steve.mcpodface.data.db.AppDatabase
import com.steve.mcpodface.data.db.EpisodeEntity
import com.steve.mcpodface.data.db.PlayState
import com.steve.mcpodface.data.db.PodcastEntity
import com.steve.mcpodface.data.db.SortOrder
import com.steve.mcpodface.data.queue.QueueRepository
import com.steve.mcpodface.data.search.SearchRepository
import com.steve.mcpodface.domain.policy.defaultSortOrder
import kotlinx.coroutines.flow.first
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
    suspend fun resolve(query: String, extras: Bundle): List<Long> {
        val text = query.trim()
        if (text.isEmpty()) return blankQueryEpisode()

        val candidates = listOfNotNull(text, extras.getString(MediaStore.EXTRA_MEDIA_ARTIST), extras.getString(MediaStore.EXTRA_MEDIA_TITLE))
            .mapNotNull(::normalise)
            .distinct()
        podcastEpisode(candidates)?.let { return listOf(it) }

        return search.search(text, pageSize = SEARCH_LIMIT).first().items.map { it.item.episode.id }
    }

    private suspend fun blankQueryEpisode(): List<Long> {
        db.episodeDao().getLastPlayed()?.let { return listOf(it.id) }
        return queue.observeIds().first().take(1)
    }

    /** The best subscribed podcast matching any candidate, resolved to one episode; null if none. */
    private suspend fun podcastEpisode(candidates: List<String>): Long? {
        if (candidates.isEmpty()) return null
        val podcast = db.podcastDao().getSubscribed().mapNotNull { p ->
            val title = normalise(p.title) ?: return@mapNotNull null
            val exact = candidates.any { it == title }
            if (exact || candidates.any { fuzzyMatch(it, title) }) Match(p, title, exact) else null
        }.maxWithOrNull(compareBy<Match>({ it.exact }, { it.title.length }))?.podcast ?: return null
        return episodeFor(podcast)
    }

    private class Match(val podcast: PodcastEntity, val title: String, val exact: Boolean)

    /**
     * In-progress (most recently played), else the next unplayed in the podcast's sort order
     * (oldest first for serials), else its newest episode.
     */
    private suspend fun episodeFor(podcast: PodcastEntity): Long? {
        val episodes = db.episodeDao().getForPodcast(podcast.id)
        if (episodes.isEmpty()) return null
        val inProgress = episodes
            .filter { it.positionMs > 0 && it.playState != PlayState.PLAYED }
            .maxByOrNull { it.lastPlayedAt ?: Long.MIN_VALUE }
        if (inProgress != null) return inProgress.id

        val order = db.podcastSettingsDao().get(podcast.id)?.sortOrder ?: defaultSortOrder(podcast.feedType)
        val unplayed = episodes.filter { it.playState != PlayState.PLAYED }
        val next = if (order == SortOrder.OLDEST_FIRST) {
            unplayed.minWithOrNull(compareBy<EpisodeEntity>({ it.pubDate }, { it.id }))
        } else {
            unplayed.maxWithOrNull(compareBy<EpisodeEntity>({ it.pubDate }, { it.id }))
        }
        return (next ?: episodes.maxWith(compareBy<EpisodeEntity>({ it.pubDate }, { it.id }))).id
    }

    private companion object {
        const val SEARCH_LIMIT = 10
        const val MIN_FUZZY_LENGTH = 4

        /** Lowercase, punctuation to spaces, collapsed, no leading "the "; null when nothing is left. */
        fun normalise(text: String?): String? {
            val cleaned = text.orEmpty().lowercase()
                .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
                .trim()
                .replace(Regex("\\s+"), " ")
            return cleaned.removePrefix("the ").takeIf { it.isNotEmpty() }
        }

        /**
         * Whole-word containment of [title] in [candidate], or [candidate] as the leading words of
         * [title]. Candidates shorter than [MIN_FUZZY_LENGTH] never match loosely.
         */
        fun fuzzyMatch(candidate: String, title: String): Boolean {
            if (candidate.length < MIN_FUZZY_LENGTH) return false
            return " $candidate ".contains(" $title ") || title.startsWith("$candidate ")
        }
    }
}
