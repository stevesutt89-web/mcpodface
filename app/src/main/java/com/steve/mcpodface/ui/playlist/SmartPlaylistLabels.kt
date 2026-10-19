package com.steve.mcpodface.ui.playlist

import com.steve.mcpodface.data.search.SearchSort
import com.steve.mcpodface.data.search.StatusFilter

/** Options for the smart playlist editor rows (PLAN.md §8.11). Values are the stored rule values. */
internal val SmartPlaylistStatusOptions = listOf(
    StatusFilter.NOT_PLAYED,
    StatusFilter.UNPLAYED,
    StatusFilter.IN_PROGRESS,
    StatusFilter.ANY,
)

/** Null = any age; otherwise the window in days (`publishedWithinDays`). */
internal val SmartPlaylistPublishedOptions: List<Int?> = listOf(null, 1, 7, 30, 90)

internal val SmartPlaylistLimitOptions: List<Int> = listOf(10, 25, 50, 100, 200)

internal val SmartPlaylistOrderOptions = listOf(SearchSort.NEWEST, SearchSort.OLDEST)

/** How many matching episodes the live preview shows. */
internal const val SmartPlaylistPreviewRows = 10

internal const val SmartPlaylistWordsHint = "Use \"quotes\" for exact phrases and -word to leave a word out."

internal fun smartPlaylistEditorTitle(isNew: Boolean): String =
    if (isNew) "New smart playlist" else "Edit smart playlist"

/** The Podcasts row value. An empty selection means every subscription. */
internal fun smartPlaylistPodcastsLabel(selectedCount: Int): String = when (selectedCount) {
    0 -> "All subscriptions"
    1 -> "1 podcast"
    else -> "$selectedCount podcasts"
}

internal fun smartPlaylistPublishedLabel(days: Int?): String = when (days) {
    null -> "Any time"
    1 -> "Last 24 hours"
    else -> "Last $days days"
}

internal fun smartPlaylistLimitLabel(limit: Int): String = "$limit episodes"

internal fun smartPlaylistOrderLabel(sort: SearchSort): String = when (sort) {
    SearchSort.OLDEST -> "Oldest first"
    else -> "Newest first"
}

/**
 * The preview heading. [count] is what the preview returned, which the rules cap at [limit], so a
 * full preview means "at least [limit]" rather than an exact total.
 */
internal fun smartPlaylistMatchLabel(count: Int, limit: Int): String = when {
    count == 0 -> "No episodes match"
    count >= limit -> "Matches $limit+ episodes"
    count == 1 -> "Matches 1 episode"
    else -> "Matches $count episodes"
}
