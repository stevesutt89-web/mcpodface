package com.steve.mcpodface.data.search

import androidx.sqlite.db.SimpleSQLiteQuery
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ln
import kotlin.math.min

enum class StatusFilter {
    ANY, UNPLAYED, IN_PROGRESS, PLAYED,

    /** Unplayed or in progress (smart playlists). */
    NOT_PLAYED,
}

/** Duration buckets from PLAN.md §7.3, in seconds (upper bound exclusive). */
enum class DurationFilter(val minSec: Int?, val maxSec: Int?) {
    ANY(null, null),
    UNDER_15(null, 15 * 60),
    FROM_15_TO_45(15 * 60, 45 * 60),
    FROM_45_TO_90(45 * 60, 90 * 60),
    OVER_90(90 * 60, null),
}

enum class SearchSort { RELEVANCE, NEWEST, OLDEST }

/** The filter chips and sheet (PLAN.md §7.3). Empty [podcastIds] = all subscriptions. */
data class SearchFilters(
    val podcastIds: Set<Long> = emptySet(),
    /** Epoch millis, inclusive. */
    val publishedFrom: Long? = null,
    /** Epoch millis, exclusive. */
    val publishedTo: Long? = null,
    val titleOnly: Boolean = false,
    val status: StatusFilter = StatusFilter.ANY,
    val downloadedOnly: Boolean = false,
    val starredOnly: Boolean = false,
    val duration: DurationFilter = DurationFilter.ANY,
) {
    val isDefault: Boolean get() = this == SearchFilters()
}

/**
 * Builds the two raw queries behind episode search. Pure, so the SQL and its bound arguments can
 * be tested without a database. All user input is bound, never concatenated.
 */
object SearchSql {
    /** Relevance needs every hit scored; beyond this many the long tail isn't worth it. */
    const val MAX_HITS = 2_000
    const val TITLE_WEIGHT = 5.0
    const val NOTES_WEIGHT = 1.0

    /** Phase 1: ids, dates and (with text) matchinfo for every match, newest first. */
    fun hits(parsed: SearchQueryBuilder.Parsed?, filters: SearchFilters, sort: SearchSort): SimpleSQLiteQuery {
        val args = mutableListOf<Any>()
        val sql = StringBuilder()
        if (parsed != null) {
            sql.append("SELECT e.id AS id, e.pubDate AS pubDate, matchinfo(episode_fts, 'pcnx') AS matchinfo ")
            sql.append("FROM episode_fts JOIN episodes e ON e.id = episode_fts.docid ")
        } else {
            sql.append("SELECT e.id AS id, e.pubDate AS pubDate, NULL AS matchinfo FROM episodes e ")
        }
        sql.append("JOIN podcasts p ON p.id = e.podcastId LEFT JOIN downloads d ON d.episodeId = e.id WHERE 1 ")
        if (parsed != null) {
            sql.append(if (filters.titleOnly) "AND episode_fts.title MATCH ? " else "AND episode_fts MATCH ? ")
            args += parsed.match
            for (phrase in parsed.excludedPhrases) {
                sql.append("AND e.id NOT IN (SELECT docid FROM episode_fts WHERE episode_fts MATCH ?) ")
                args += "\"" + phrase.replace("\"", "") + "\""
            }
        }
        appendFilters(sql, args, filters)
        sql.append(
            when (sort) {
                SearchSort.OLDEST -> "ORDER BY e.pubDate ASC, e.id ASC "
                else -> "ORDER BY e.pubDate DESC, e.id DESC "
            },
        )
        sql.append("LIMIT ").append(MAX_HITS)
        return SimpleSQLiteQuery(sql.toString(), args.toTypedArray())
    }

    /** Phase 2: display rows (and snippets) for [ids]. Order is restored by the caller. */
    fun rows(parsed: SearchQueryBuilder.Parsed?, titleOnly: Boolean, ids: List<Long>): SimpleSQLiteQuery {
        val args = mutableListOf<Any>()
        val placeholders = ids.joinToString(",") { "?" }
        val select = """
            e.*, p.title AS podcastTitle, p.artworkUrl AS podcastArtworkUrl,
            d.state AS downloadState, COALESCE(d.totalBytes, d.bytesDownloaded) AS downloadBytes,
            EXISTS (SELECT 1 FROM queue_items q WHERE q.episodeId = e.id) AS inQueue
        """.trimIndent()
        val sql = if (parsed != null) {
            args += parsed.match
            "SELECT $select, snippet(episode_fts, '<b>', '</b>', '…', -1, 16) AS snippet " +
                "FROM episode_fts JOIN episodes e ON e.id = episode_fts.docid " +
                "JOIN podcasts p ON p.id = e.podcastId LEFT JOIN downloads d ON d.episodeId = e.id " +
                "WHERE ${if (titleOnly) "episode_fts.title" else "episode_fts"} MATCH ? AND e.id IN ($placeholders)"
        } else {
            "SELECT $select, NULL AS snippet FROM episodes e " +
                "JOIN podcasts p ON p.id = e.podcastId LEFT JOIN downloads d ON d.episodeId = e.id " +
                "WHERE e.id IN ($placeholders)"
        }
        args.addAll(ids)
        return SimpleSQLiteQuery(sql, args.toTypedArray())
    }

    private fun appendFilters(sql: StringBuilder, args: MutableList<Any>, f: SearchFilters) {
        if (f.podcastIds.isEmpty()) {
            sql.append("AND p.isSubscribed = 1 ")
        } else {
            sql.append("AND e.podcastId IN (").append(f.podcastIds.joinToString(",") { "?" }).append(") ")
            args.addAll(f.podcastIds)
        }
        f.publishedFrom?.let { sql.append("AND e.pubDate >= ? "); args += it }
        f.publishedTo?.let { sql.append("AND e.pubDate < ? "); args += it }
        when (f.status) {
            StatusFilter.ANY -> Unit
            StatusFilter.NOT_PLAYED -> sql.append("AND e.playState != 'PLAYED' ")
            else -> {
                sql.append("AND e.playState = ? ")
                args += f.status.name
            }
        }
        if (f.downloadedOnly) sql.append("AND d.state = 'COMPLETED' ")
        if (f.starredOnly) sql.append("AND e.isStarred = 1 ")
        f.duration.minSec?.let { sql.append("AND e.durationSec >= ? "); args += it }
        f.duration.maxSec?.let { sql.append("AND e.durationSec < ? "); args += it }
    }

    /**
     * Relevance from FTS4 `matchinfo(…, 'pcnx')`: per phrase and column, hits in this row weighted
     * by column (title [TITLE_WEIGHT]× show notes) and by how rare the phrase is (idf). Repeated
     * hits count with diminishing returns so a long show-notes page can't outrank a title match.
     */
    fun score(matchinfo: ByteArray?): Double {
        if (matchinfo == null || matchinfo.size < 12) return 0.0
        val ints = ByteBuffer.wrap(matchinfo).order(ByteOrder.nativeOrder()).asIntBuffer()
        val phrases = ints.get(0)
        val cols = ints.get(1)
        val docs = ints.get(2).coerceAtLeast(1)
        if (ints.limit() < 3 + phrases * cols * 3) return 0.0
        var score = 0.0
        for (i in 0 until phrases) {
            for (c in 0 until cols) {
                val base = 3 + (i * cols + c) * 3
                val hitsHere = ints.get(base)
                if (hitsHere == 0) continue
                val docsWithHits = ints.get(base + 2).coerceAtLeast(1)
                val idf = ln(1.0 + docs.toDouble() / docsWithHits)
                val weight = if (c == 0) TITLE_WEIGHT else NOTES_WEIGHT
                score += weight * idf * (1 + ln(min(hitsHere, 20).toDouble()))
            }
        }
        return score
    }
}
