package com.steve.mcpodface.domain.policy

import com.steve.mcpodface.data.db.DownloadSource
import com.steve.mcpodface.data.db.DownloadState
import com.steve.mcpodface.data.db.PlayState
import com.steve.mcpodface.data.db.SortOrder

/**
 * Which downloaded episodes of one podcast to delete (PLAN.md §6.3). Pure; the caller deletes the
 * files, removes the download rows and sets autoDownloadBlocked = true on each (§6.4).
 * Run it with proposed settings to build the "This will remove 7 episodes" preview (§6.5).
 */
object CleanupPolicy {

    fun select(
        episodes: List<PolicyEpisode>,
        settings: EffectiveSettings,
        now: Long,
        currentlyPlayingId: Long?,
    ): List<Deletion> {
        // Only finished downloads are candidates, so mid-download episodes are never touched.
        val candidates = episodes.filter {
            it.download?.state == DownloadState.COMPLETED && !it.isStarred && it.id != currentlyPlayingId
        }
        val deletions = mutableListOf<Deletion>()

        for (e in candidates) {
            when {
                isPlayedLongEnough(e, settings, now) -> deletions += Deletion(e.id, DeleteReason.PLAYED)
                isTooOld(e, settings, now) -> deletions += Deletion(e.id, DeleteReason.TOO_OLD)
            }
        }

        // R3: keep at most N downloads that count toward the limit. Up Next items are exempt.
        val deleted = deletions.mapTo(HashSet()) { it.episodeId }
        val counted = candidates.filter {
            it.id !in deleted && !it.inQueue &&
                (it.download!!.source == DownloadSource.AUTO || settings.countManualTowardLimit)
        }
        val excess = counted.size - settings.keepLatestCount
        if (excess > 0) {
            val windowRank = DownloadPolicy.window(episodes, settings)
                .withIndex().associate { (i, e) -> e.id to i }
            val furthestFirst = when (settings.sortOrder) {
                SortOrder.NEWEST_FIRST -> compareBy<PolicyEpisode> { it.pubDate }.thenBy { it.id }
                SortOrder.OLDEST_FIRST -> compareByDescending<PolicyEpisode> { it.pubDate }.thenByDescending { it.id }
            }
            counted
                .sortedWith(compareBy<PolicyEpisode> { it.id in windowRank }.then(furthestFirst))
                .take(excess)
                .forEach { deletions += Deletion(it.id, DeleteReason.OVER_LIMIT) }
        }
        return deletions
    }

    /** R1: played (§6.0) and the delay since playedAt has passed. Applies to AUTO and MANUAL. */
    private fun isPlayedLongEnough(e: PolicyEpisode, s: EffectiveSettings, now: Long): Boolean {
        if (!s.deletePlayed || e.playState != PlayState.PLAYED) return false
        val playedAt = e.playedAt ?: return false
        return now - playedAt >= s.deletePlayedDelayHours * HOUR_MS
    }

    /**
     * R2: older than maxAgeDays. Age runs from pubDate for AUTO + NEWEST_FIRST downloads, otherwise
     * from downloadedAt, so a back-catalogue episode you chose isn't deleted the moment it lands.
     */
    private fun isTooOld(e: PolicyEpisode, s: EffectiveSettings, now: Long): Boolean {
        if (s.maxAgeDays <= 0) return false
        val download = e.download!!
        val from = if (download.source == DownloadSource.AUTO && s.sortOrder == SortOrder.NEWEST_FIRST) {
            e.pubDate
        } else {
            download.downloadedAt ?: return false
        }
        return now - from > s.maxAgeDays * DAY_MS
    }
}
