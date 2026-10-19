package com.steve.mcpodface.data.playlist

import com.steve.mcpodface.data.search.DurationFilter
import com.steve.mcpodface.data.search.SearchFilters
import com.steve.mcpodface.data.search.SearchSort
import com.steve.mcpodface.data.search.StatusFilter
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The rules of a smart playlist (PLAN.md §8.11), evaluated live by the episode search engine.
 * Stored as JSON in `playlists.rulesJson`; field names are part of that format, don't rename them.
 */
@Serializable
data class SmartRules(
    /** Empty = every subscription. */
    val podcastIds: List<Long> = emptyList(),
    val status: StatusFilter = StatusFilter.NOT_PLAYED,
    val downloadedOnly: Boolean = false,
    val starredOnly: Boolean = false,
    /** Only episodes published in the last N days; null = any age. */
    val publishedWithinDays: Int? = null,
    val duration: DurationFilter = DurationFilter.ANY,
    /** Optional words that must appear in the title or show notes (search syntax). */
    val text: String = "",
    val titleOnly: Boolean = false,
    /** NEWEST or OLDEST (RELEVANCE only applies with [text]). */
    val sort: SearchSort = SearchSort.NEWEST,
    /** Most episodes the playlist holds. */
    val limit: Int = DEFAULT_LIMIT,
) {
    /** The search filters for these rules at time [now] (the age window moves with the clock). */
    fun toFilters(now: Long): SearchFilters = SearchFilters(
        podcastIds = podcastIds.toSet(),
        publishedFrom = publishedWithinDays?.let { now - it * DAY_MS },
        titleOnly = titleOnly,
        status = status,
        downloadedOnly = downloadedOnly,
        starredOnly = starredOnly,
        duration = duration,
    )

    fun toJson(): String = json.encodeToString(serializer(), this)

    companion object {
        const val DEFAULT_LIMIT = 50
        const val MAX_LIMIT = 500
        private const val DAY_MS = 86_400_000L
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        /** Unreadable JSON falls back to the defaults rather than breaking the playlist. */
        fun fromJson(value: String?): SmartRules =
            value?.let { runCatching { json.decodeFromString(serializer(), it) }.getOrNull() } ?: SmartRules()
    }
}
