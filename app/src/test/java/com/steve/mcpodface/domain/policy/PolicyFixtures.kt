package com.steve.mcpodface.domain.policy

import com.steve.mcpodface.data.db.DownloadSource
import com.steve.mcpodface.data.db.DownloadState
import com.steve.mcpodface.data.db.PlayState
import com.steve.mcpodface.data.db.SortOrder

internal const val NOW = 20_000 * DAY_MS

internal fun daysAgo(days: Double): Long = NOW - (days * DAY_MS).toLong()
internal fun hoursAgo(hours: Double): Long = NOW - (hours * HOUR_MS).toLong()

/** Episode [id] published [days] ago. */
internal fun ep(
    id: Long,
    days: Double,
    playState: PlayState = PlayState.UNPLAYED,
    playedAt: Long? = if (playState == PlayState.PLAYED) daysAgo(30.0) else null,
    starred: Boolean = false,
    blocked: Boolean = false,
    inQueue: Boolean = false,
    download: PolicyDownload? = null,
) = PolicyEpisode(id, daysAgo(days), playState, playedAt, starred, blocked, inQueue, download)

internal fun dl(
    source: DownloadSource = DownloadSource.AUTO,
    state: DownloadState = DownloadState.COMPLETED,
    downloadedAt: Long? = daysAgo(1.0),
) = PolicyDownload(state, source, downloadedAt)

/** Global defaults from PLAN.md §6.1. */
internal fun settings(
    autoDownload: Boolean = true,
    keep: Int = 5,
    deletePlayed: Boolean = true,
    delayHours: Int = 24,
    maxAgeDays: Int = 30,
    sort: SortOrder = SortOrder.NEWEST_FIRST,
    countManual: Boolean = false,
) = EffectiveSettings(autoDownload, keep, deletePlayed, delayHours, maxAgeDays, false, sort, countManual)

/** Ten weekly episodes: id 1 is the oldest (63 days ago), id 10 the newest (today). */
internal fun weekly(count: Int = 10): List<PolicyEpisode> =
    (1..count).map { ep(it.toLong(), days = (count - it) * 7.0) }
