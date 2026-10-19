package com.steve.mcpodface.data.feed

import com.steve.mcpodface.data.db.FeedType

/** Output of RssParser: exactly what the feed says, with no user state. */
data class ParsedFeed(
    val title: String,
    val author: String?,
    val descriptionHtml: String?,
    val artworkUrl: String?,
    val websiteUrl: String?,
    val feedType: FeedType,
    /** `<atom:link rel="next">` href, for RSS paging (PLAN.md §7.4). */
    val nextPageUrl: String?,
    val episodes: List<ParsedEpisode>,
)

data class ParsedEpisode(
    /** `<guid>`, else enclosure URL, else a stable hash of title + pubDate. Never blank. */
    val guid: String,
    val title: String,
    /** content:encoded if present, else description, else itunes:summary. Raw HTML. */
    val descriptionHtml: String?,
    /** Epoch millis, or null if missing or unparseable. */
    val pubDate: Long?,
    val durationSec: Int?,
    val enclosureUrl: String,
    val enclosureType: String?,
    val enclosureLength: Long?,
    val artworkUrl: String?,
    val season: Int?,
    val episodeNumber: Int?,
    val chaptersUrl: String?,
    val transcriptUrl: String?,
)

class FeedParseException(message: String, cause: Throwable? = null) : Exception(message, cause)
