package com.steve.mcpodface.data.db

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.RawQuery
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

/** Phase 1 of a search: every match, cheaply. [matchinfo] is FTS4 `matchinfo(…, 'pcnx')`, null without text. */
data class SearchHit(val id: Long, val pubDate: Long, val matchinfo: ByteArray?)

/** Phase 2: a display row for one of the top hits, with its highlighted snippet. */
data class SearchRow(
    @Embedded val episode: EpisodeEntity,
    val podcastTitle: String,
    val podcastArtworkUrl: String?,
    val downloadState: DownloadState?,
    val downloadBytes: Long?,
    val inQueue: Boolean,
    /** `snippet()` with the matches wrapped in `<b>…</b>`; null when searching without text. */
    val snippet: String?,
)

/** Raw queries built by [com.steve.mcpodface.data.search.SearchSql] (PLAN.md §7.3). */
@Dao
interface SearchDao {
    /** Re-runs whenever episodes, podcasts, downloads or the queue change, so results stay live. */
    @RawQuery(observedEntities = [EpisodeEntity::class, PodcastEntity::class, DownloadEntity::class, QueueItemEntity::class])
    fun observeHits(query: SupportSQLiteQuery): Flow<List<SearchHit>>

    @RawQuery
    suspend fun rows(query: SupportSQLiteQuery): List<SearchRow>
}
