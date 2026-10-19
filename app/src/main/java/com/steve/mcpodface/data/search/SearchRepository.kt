package com.steve.mcpodface.data.search

import com.steve.mcpodface.core.Clock
import com.steve.mcpodface.core.di.IoDispatcher
import com.steve.mcpodface.data.db.AppDatabase
import com.steve.mcpodface.data.db.EpisodeListItem
import com.steve.mcpodface.data.db.RecentSearchEntity
import com.steve.mcpodface.data.db.SearchHit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import javax.inject.Inject

/** One result: the row the list shows, plus the highlighted snippet (`<b>` marks the matches). */
data class SearchResult(val item: EpisodeListItem, val snippet: String?)

data class SearchResults(
    val items: List<SearchResult>,
    /** Total matches, capped at [SearchSql.MAX_HITS]. */
    val totalCount: Int,
) {
    val isCapped: Boolean get() = totalCount >= SearchSql.MAX_HITS
}

/**
 * Offline episode search over every stored episode, including archived and backfilled ones
 * (PLAN.md §7). Two phases so it stays fast on 100k episodes: all matching ids with `matchinfo`
 * (cheap, observed so results update live), then display rows and `snippet()` for the top
 * [pageSize] only. No API calls.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchRepository @Inject constructor(
    private val db: AppDatabase,
    private val clock: Clock,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    /**
     * Results for [text] with [filters]. Blank text with default filters emits nothing to show;
     * blank text with filters lists the matching episodes (newest first).
     */
    fun search(
        text: String,
        filters: SearchFilters = SearchFilters(),
        sort: SearchSort = SearchSort.RELEVANCE,
        pageSize: Int = 200,
        /** Smart playlists: blank text with default filters lists every subscribed episode. */
        listAllWhenBlank: Boolean = false,
    ): Flow<SearchResults> {
        val parsed = SearchQueryBuilder.parse(text)
        if (parsed == null && (text.isNotBlank() || (filters.isDefault && !listAllWhenBlank))) {
            return flowOf(SearchResults(emptyList(), 0))
        }
        return db.searchDao().observeHits(SearchSql.hits(parsed, filters, sort))
            .mapLatest { hits -> load(parsed, filters.titleOnly, rank(hits, sort, parsed != null), pageSize) }
            .flowOn(io)
    }

    private suspend fun load(
        parsed: SearchQueryBuilder.Parsed?,
        titleOnly: Boolean,
        ranked: List<SearchHit>,
        pageSize: Int,
    ): SearchResults {
        val top = ranked.take(pageSize).map { it.id }
        if (top.isEmpty()) return SearchResults(emptyList(), 0)
        val rows = db.searchDao().rows(SearchSql.rows(parsed, titleOnly, top)).associateBy { it.episode.id }
        val items = top.mapNotNull { id ->
            rows[id]?.let { r ->
                SearchResult(
                    EpisodeListItem(r.episode, r.podcastTitle, r.podcastArtworkUrl, r.downloadState, r.downloadBytes, r.inQueue),
                    r.snippet,
                )
            }
        }
        return SearchResults(items, ranked.size)
    }

    /** Remembers a search for the recent list (up to [RECENT_LIMIT]). */
    suspend fun recordSearch(text: String, podcastId: Long? = null) {
        val q = text.trim()
        if (q.isEmpty()) return
        db.recentSearchDao().upsert(RecentSearchEntity(query = q, lastUsedAt = clock.now(), podcastId = podcastId))
        db.recentSearchDao().trimTo(RECENT_LIMIT)
    }

    fun recentSearches(): Flow<List<RecentSearchEntity>> = db.recentSearchDao().observeRecent(RECENT_LIMIT)

    suspend fun clearRecentSearches() = db.recentSearchDao().clear()

    companion object {
        const val RECENT_LIMIT = 10

        /** Relevance: score, ties broken by recency. The other sorts keep the SQL order. */
        internal fun rank(hits: List<SearchHit>, sort: SearchSort, hasText: Boolean): List<SearchHit> =
            if (sort == SearchSort.RELEVANCE && hasText) {
                hits.map { it to SearchSql.score(it.matchinfo) }
                    .sortedWith(compareByDescending<Pair<SearchHit, Double>> { it.second }.thenByDescending { it.first.pubDate })
                    .map { it.first }
            } else {
                hits
            }
    }
}
