package com.steve.mcpodface.domain.policy

import com.steve.mcpodface.data.db.FeedType
import com.steve.mcpodface.data.db.PodcastSettingsEntity
import com.steve.mcpodface.data.db.SortOrder
import com.steve.mcpodface.data.prefs.GlobalSettings

/** Global settings with one podcast's overrides applied (PLAN.md §6.1). */
data class EffectiveSettings(
    val autoDownload: Boolean,
    val keepLatestCount: Int,
    val deletePlayed: Boolean,
    val deletePlayedDelayHours: Int,
    val maxAgeDays: Int,
    val allowCellular: Boolean,
    val sortOrder: SortOrder,
    val countManualTowardLimit: Boolean,
)

/** Each non-null override wins; otherwise the global value applies. Sort defaults by feed type. */
fun resolve(global: GlobalSettings, override: PodcastSettingsEntity?, feedType: FeedType): EffectiveSettings =
    EffectiveSettings(
        autoDownload = override?.autoDownload ?: global.autoDownload,
        keepLatestCount = (override?.keepLatestCount ?: global.keepLatestCount).coerceIn(1, 20),
        deletePlayed = override?.deletePlayed ?: global.deletePlayed,
        deletePlayedDelayHours = global.deletePlayedDelayHours,
        maxAgeDays = override?.maxAgeDays ?: global.maxAgeDays,
        allowCellular = override?.allowCellular ?: global.allowCellularDownload,
        sortOrder = override?.sortOrder ?: defaultSortOrder(feedType),
        countManualTowardLimit = global.countManualTowardLimit,
    )

fun defaultSortOrder(feedType: FeedType): SortOrder =
    if (feedType == FeedType.SERIAL) SortOrder.OLDEST_FIRST else SortOrder.NEWEST_FIRST
