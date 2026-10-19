package com.steve.mcpodface.domain.policy

import com.steve.mcpodface.data.db.PlayState
import com.steve.mcpodface.data.db.SortOrder

/** Which episodes of one podcast to auto-download (PLAN.md §6.2). Pure; the caller enqueues. */
object DownloadPolicy {

    /**
     * The episodes the podcast "should" have downloaded, in sort order, regardless of download rows.
     *
     * NEWEST_FIRST: the N newest episodes, played or not.
     * OLDEST_FIRST: the next N unplayed, non-tombstoned episodes after the resume point (the pubDate
     * of the newest PLAYED episode, or the very start if nothing is played). Episodes behind the
     * resume point, such as backfilled ones, never enter the window.
     */
    fun window(episodes: List<PolicyEpisode>, settings: EffectiveSettings): List<PolicyEpisode> {
        val n = settings.keepLatestCount
        return when (settings.sortOrder) {
            SortOrder.NEWEST_FIRST ->
                episodes.sortedWith(compareByDescending<PolicyEpisode> { it.pubDate }.thenByDescending { it.id })
                    .take(n)
            SortOrder.OLDEST_FIRST -> {
                val resume = episodes.filter { it.playState == PlayState.PLAYED }.maxOfOrNull { it.pubDate }
                episodes
                    .filter { resume == null || it.pubDate > resume }
                    .filter { it.playState != PlayState.PLAYED && !it.autoDownloadBlocked }
                    .sortedWith(compareBy<PolicyEpisode> { it.pubDate }.thenBy { it.id })
                    .take(n)
            }
        }
    }

    /** Episode ids to enqueue as AUTO downloads, in sort order. */
    fun select(episodes: List<PolicyEpisode>, settings: EffectiveSettings, now: Long): List<Long> {
        if (!settings.autoDownload) return emptyList()
        return window(episodes, settings)
            .filter { it.playState != PlayState.PLAYED }
            .filter { !it.autoDownloadBlocked }
            // Any downloads row (queued, running, done, paused or failed) means "already handled".
            .filter { it.download == null }
            .filter { settings.sortOrder == SortOrder.OLDEST_FIRST || withinMaxAge(it.pubDate, settings, now) }
            .map { it.id }
    }

    private fun withinMaxAge(pubDate: Long, settings: EffectiveSettings, now: Long): Boolean =
        settings.maxAgeDays == 0 || now - pubDate <= settings.maxAgeDays * DAY_MS
}
