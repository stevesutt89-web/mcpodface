package com.steve.mcpodface.domain.policy

import com.steve.mcpodface.data.db.DownloadSource
import com.steve.mcpodface.data.db.DownloadState
import com.steve.mcpodface.data.db.PlayState

/** The slice of an episode (plus its download row) that the policies look at. */
data class PolicyEpisode(
    val id: Long,
    val pubDate: Long,
    val playState: PlayState = PlayState.UNPLAYED,
    val playedAt: Long? = null,
    val isStarred: Boolean = false,
    /** Tombstone (PLAN.md §6.4). */
    val autoDownloadBlocked: Boolean = false,
    val inQueue: Boolean = false,
    /** null = no downloads row. */
    val download: PolicyDownload? = null,
)

data class PolicyDownload(
    val state: DownloadState,
    val source: DownloadSource,
    val downloadedAt: Long? = null,
)

enum class DeleteReason { PLAYED, TOO_OLD, OVER_LIMIT }

data class Deletion(val episodeId: Long, val reason: DeleteReason)

internal const val HOUR_MS = 60L * 60 * 1000
internal const val DAY_MS = 24 * HOUR_MS
