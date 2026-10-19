package com.steve.mcpodface.ui.podcastsettings

import com.steve.mcpodface.data.db.FeedType
import com.steve.mcpodface.data.db.PodcastSettingsEntity
import com.steve.mcpodface.data.db.QueueAdd
import com.steve.mcpodface.data.db.SortOrder
import com.steve.mcpodface.data.prefs.GlobalSettings
import com.steve.mcpodface.domain.policy.defaultSortOrder
import com.steve.mcpodface.ui.settings.SettingsOptions
import com.steve.mcpodface.ui.settings.nearestFloat
import com.steve.mcpodface.ui.settings.nearestInt

// Pure Kotlin: option lists, "Default (x)" labels and the choice -> PodcastSettingsEntity mapping for
// the per-podcast settings sheet (PLAN.md §8.4, §6.1). Kept free of Android and Compose imports.

/** What a podcast inherits from: the global settings, and the feed type (serial feeds sort oldest first). */
internal data class Inheritance(val global: GlobalSettings, val feedType: FeedType)

/**
 * A per-podcast override that is either set (a value) or inherited (null in the entity).
 * [options] starts with the "Default (x)" entry, which writes null, followed by the explicit values.
 * [snap] maps a stored value that is not offered (for example a hand-edited row) to the nearest offered one.
 */
internal class InheritableSetting<T : Any>(
    private val values: List<Pair<T, String>>,
    private val inheritedLabel: (Inheritance) -> String,
    private val read: (PodcastSettingsEntity) -> T?,
    private val write: (PodcastSettingsEntity, T?) -> PodcastSettingsEntity,
    private val snap: (T) -> T = { it },
) {
    fun options(context: Inheritance): List<Pair<T?, String>> =
        listOf<Pair<T?, String>>(null to "Default (${inheritedLabel(context)})") + values

    /** The stored override, or null when the podcast inherits the global value. */
    fun selected(entity: PodcastSettingsEntity): T? = read(entity)?.let(snap)

    fun label(entity: PodcastSettingsEntity, context: Inheritance): String {
        val all = options(context)
        val value = selected(entity)
        return all.firstOrNull { it.first == value }?.second ?: all.first().second
    }

    /** Returns [entity] with this setting replaced; null writes "inherit". Every other field is kept. */
    fun apply(entity: PodcastSettingsEntity, value: T?): PodcastSettingsEntity = write(entity, value)
}

/** A setting with no inherit option (its entity field is never null). */
internal class FixedSetting<T : Any>(
    val options: List<Pair<T, String>>,
    private val read: (PodcastSettingsEntity) -> T,
    private val write: (PodcastSettingsEntity, T) -> PodcastSettingsEntity,
    private val snap: (T) -> T = { it },
) {
    fun selected(entity: PodcastSettingsEntity): T = snap(read(entity))

    fun label(entity: PodcastSettingsEntity): String {
        val value = selected(entity)
        return options.firstOrNull { it.first == value }?.second ?: options.first().second
    }

    fun apply(entity: PodcastSettingsEntity, value: T): PodcastSettingsEntity = write(entity, value)
}

internal object PodcastSettingsOptions {
    private val keepLatestValues = listOf(1, 2, 3, 5, 10, 15, 20)
    private val skipValues = listOf(0, 15, 30, 45, 60, 90, 120)
    private val maxAgeValues: List<Int> = SettingsOptions.maxAgeDays.options.map { it.first }
    private val speedValues: List<Float> = SettingsOptions.defaultSpeed.options.map { it.first }

    private fun onOff(on: Boolean): String = if (on) "On" else "Off"

    private fun sortLabel(order: SortOrder): String = when (order) {
        SortOrder.NEWEST_FIRST -> "Newest first"
        SortOrder.OLDEST_FIRST -> "Oldest first"
    }

    /** Auto-download: Default (On/Off) follows the global switch. */
    val autoDownload = InheritableSetting<Boolean>(
        values = listOf(true to "On", false to "Off"),
        inheritedLabel = { onOff(it.global.autoDownload) },
        read = { it.autoDownload },
        write = { e, v -> e.copy(autoDownload = v) },
    )

    /** Keep latest N episodes, shown as plain numbers ("Default (5)"). */
    val keepLatest = InheritableSetting<Int>(
        values = keepLatestValues.map { it to it.toString() },
        inheritedLabel = { it.global.keepLatestCount.toString() },
        read = { it.keepLatestCount },
        write = { e, v -> e.copy(keepLatestCount = v) },
        snap = { nearestInt(it, keepLatestValues) },
    )

    /** Delete played episodes (On/Off), not the global delay. */
    val deletePlayed = InheritableSetting<Boolean>(
        values = listOf(true to "On", false to "Off"),
        inheritedLabel = { onOff(it.global.deletePlayed) },
        read = { it.deletePlayed },
        write = { e, v -> e.copy(deletePlayed = v) },
    )

    /** Delete episodes older than N days; 0 (Never) is stored as 0, not null. */
    val maxAgeDays = InheritableSetting<Int>(
        values = SettingsOptions.maxAgeDays.options,
        inheritedLabel = { SettingsOptions.maxAgeDays.label(it.global) },
        read = { it.maxAgeDays },
        write = { e, v -> e.copy(maxAgeDays = v) },
        snap = { nearestInt(it, maxAgeValues, off = 0) },
    )

    /** Wi-Fi only (false) or Wi-Fi and mobile data (true). */
    val allowCellular = InheritableSetting<Boolean>(
        values = SettingsOptions.downloadOn.options,
        inheritedLabel = { SettingsOptions.downloadOn.label(it.global) },
        read = { it.allowCellular },
        write = { e, v -> e.copy(allowCellular = v) },
    )

    /** Newest or oldest first. The default label depends on the feed type (serial feeds are oldest first). */
    val sortOrder = InheritableSetting<SortOrder>(
        values = listOf(SortOrder.NEWEST_FIRST to "Newest first", SortOrder.OLDEST_FIRST to "Oldest first"),
        inheritedLabel = { sortLabel(defaultSortOrder(it.feedType)) },
        read = { it.sortOrder },
        write = { e, v -> e.copy(sortOrder = v) },
    )

    /** Playback speed; the default label shows the global default speed. */
    val playbackSpeed = InheritableSetting<Float>(
        values = SettingsOptions.defaultSpeed.options,
        inheritedLabel = { SettingsOptions.defaultSpeed.label(it.global) },
        read = { it.playbackSpeed },
        write = { e, v -> e.copy(playbackSpeed = v) },
        snap = { nearestFloat(it, speedValues) },
    )

    /** Archive backfill (On/Off), inheriting the global backfill switch. */
    val backfill = InheritableSetting<Boolean>(
        values = listOf(true to "On", false to "Off"),
        inheritedLabel = { onOff(it.global.backfillEnabled) },
        read = { it.backfillEnabled },
        write = { e, v -> e.copy(backfillEnabled = v) },
    )

    /** Seconds to skip at the start of each episode. 0 is Off and is stored as 0 (never null). */
    val skipIntro = FixedSetting<Int>(
        options = skipOptions(),
        read = { it.skipIntroSec },
        write = { e, v -> e.copy(skipIntroSec = v) },
        snap = { nearestInt(it, skipValues, off = 0) },
    )

    /** Seconds to skip at the end of each episode. Same options as [skipIntro]. */
    val skipOutro = FixedSetting<Int>(
        options = skipOptions(),
        read = { it.skipOutroSec },
        write = { e, v -> e.copy(skipOutroSec = v) },
        snap = { nearestInt(it, skipValues, off = 0) },
    )

    /** Add new episodes to Up Next: Off, to the top, or to the bottom. */
    val autoAddToQueue = FixedSetting<QueueAdd>(
        options = listOf(QueueAdd.OFF to "Off", QueueAdd.TOP to "Top", QueueAdd.BOTTOM to "Bottom"),
        read = { it.autoAddToQueue },
        write = { e, v -> e.copy(autoAddToQueue = v) },
    )

    /** The new-episode notification switch. */
    fun applyNotifyNewEpisodes(entity: PodcastSettingsEntity, enabled: Boolean): PodcastSettingsEntity =
        entity.copy(notifyNewEpisodes = enabled)

    private fun skipOptions(): List<Pair<Int, String>> = listOf(
        0 to "Off",
        15 to "15 s",
        30 to "30 s",
        45 to "45 s",
        60 to "60 s",
        90 to "90 s",
        120 to "2 min",
    )
}
