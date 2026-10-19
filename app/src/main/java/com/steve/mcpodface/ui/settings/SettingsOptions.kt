package com.steve.mcpodface.ui.settings

import com.steve.mcpodface.data.prefs.GlobalSettings
import kotlin.math.abs

// Pure Kotlin: option lists, labels and the choice -> settings mapping for the Settings screen.
// Kept free of Android and Compose imports so it can be unit-tested on the JVM.

/**
 * One choice setting: the options offered, the value that is current for a given [GlobalSettings]
 * (a stored value that matches no option is shown as the nearest one) and how a pick is written back.
 */
internal class ChoiceSetting<T>(
    val options: List<Pair<T, String>>,
    private val read: (GlobalSettings) -> T,
    private val write: (GlobalSettings, T) -> GlobalSettings,
) {
    fun selected(settings: GlobalSettings): T = read(settings)

    fun label(settings: GlobalSettings): String {
        val value = read(settings)
        return options.firstOrNull { it.first == value }?.second ?: options.first().second
    }

    fun apply(settings: GlobalSettings, value: T): GlobalSettings = write(settings, value)
}

/** Nearest value in [values] to [value]. [off] (a "never"/"manual" sentinel) is only chosen on an exact match. */
internal fun nearestInt(value: Int, values: List<Int>, off: Int? = null): Int {
    if (value in values) return value
    val candidates = values.filter { it != off }.ifEmpty { values }
    return candidates.minBy { abs(it - value) }
}

internal fun nearestFloat(value: Float, values: List<Float>): Float =
    values.minBy { abs(it - value) }

/** Auto-delete for played episodes. NEVER maps to deletePlayed = false; the others set a delay in hours. */
internal enum class PlayedDeletion(val label: String, val delayHours: Int?) {
    NEVER("Never", null),
    IMMEDIATELY("Immediately", 0),
    AFTER_24_HOURS("After 24 hours", 24),
    AFTER_3_DAYS("After 3 days", 72),
    AFTER_1_WEEK("After 1 week", 168);

    fun apply(settings: GlobalSettings): GlobalSettings =
        if (delayHours == null) {
            settings.copy(deletePlayed = false)
        } else {
            settings.copy(deletePlayed = true, deletePlayedDelayHours = delayHours)
        }

    companion object {
        fun of(settings: GlobalSettings): PlayedDeletion {
            if (!settings.deletePlayed) return NEVER
            val delays = entries.mapNotNull { it.delayHours }
            val nearest = nearestInt(settings.deletePlayedDelayHours, delays)
            return entries.first { it.delayHours == nearest }
        }
    }
}

internal object SettingsOptions {
    private fun intChoice(
        values: List<Int>,
        label: (Int) -> String,
        get: (GlobalSettings) -> Int,
        set: (GlobalSettings, Int) -> GlobalSettings,
        off: Int? = null,
    ) = ChoiceSetting(
        options = values.map { it to label(it) },
        read = { settings -> nearestInt(get(settings), values, off) },
        write = set,
    )

    val keepLatest = intChoice(
        values = listOf(1, 2, 3, 5, 10, 15, 20),
        label = { if (it == 1) "1 episode" else "$it episodes" },
        get = { it.keepLatestCount },
        set = { s, v -> s.copy(keepLatestCount = v) },
    )

    val downloadOn = ChoiceSetting(
        options = listOf(false to "Wi-Fi only", true to "Wi-Fi and mobile data"),
        read = { it.allowCellularDownload },
        write = { s, v -> s.copy(allowCellularDownload = v) },
    )

    val deletePlayed = ChoiceSetting(
        options = PlayedDeletion.entries.map { it to it.label },
        read = { PlayedDeletion.of(it) },
        write = { s, v -> v.apply(s) },
    )

    val maxAgeDays = intChoice(
        values = listOf(7, 14, 30, 90, 0),
        label = {
            when (it) {
                7 -> "1 week"
                14 -> "2 weeks"
                30 -> "1 month"
                90 -> "3 months"
                else -> "Never"
            }
        },
        get = { it.maxAgeDays },
        set = { s, v -> s.copy(maxAgeDays = v) },
        off = 0,
    )

    val skipBack = intChoice(
        values = listOf(5, 10, 15, 30),
        label = { "$it seconds" },
        get = { it.skipBackSec },
        set = { s, v -> s.copy(skipBackSec = v) },
    )

    val skipForward = intChoice(
        values = listOf(10, 15, 30, 45, 60),
        label = { "$it seconds" },
        get = { it.skipForwardSec },
        set = { s, v -> s.copy(skipForwardSec = v) },
    )

    private val speedValues = listOf(0.8f, 1.0f, 1.2f, 1.3f, 1.5f, 1.8f, 2.0f)

    val defaultSpeed = ChoiceSetting(
        options = listOf(
            0.8f to "0.8×",
            1.0f to "1.0×",
            1.2f to "1.2×",
            1.3f to "1.3×",
            1.5f to "1.5×",
            1.8f to "1.8×",
            2.0f to "2.0×",
        ),
        read = { nearestFloat(it.defaultSpeed, speedValues) },
        write = { s, v -> s.copy(defaultSpeed = v) },
    )

    val markPlayed = intChoice(
        values = listOf(90, 95, 98),
        label = { "$it%" },
        get = { it.markPlayedPercent },
        set = { s, v -> s.copy(markPlayedPercent = v) },
    )

    val refreshInterval = intChoice(
        values = listOf(1, 2, 4, 12, 0),
        label = {
            when (it) {
                0 -> "Manually only"
                1 -> "Every hour"
                else -> "Every $it hours"
            }
        },
        get = { it.refreshIntervalHours },
        set = { s, v -> s.copy(refreshIntervalHours = v) },
        off = 0,
    )

    val apiDailyBudget = intChoice(
        values = listOf(25, 50, 100, 200, 500),
        label = { "$it requests" },
        get = { it.apiDailyBudget },
        set = { s, v -> s.copy(apiDailyBudget = v) },
    )
}
