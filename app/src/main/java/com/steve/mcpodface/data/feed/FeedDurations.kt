package com.steve.mcpodface.data.feed

import kotlin.math.roundToInt

/** itunes:duration parsing: "SS", "MM:SS", "HH:MM:SS", with optional decimals. */
object FeedDurations {

    private val SECONDS = Regex("""^\d+(?:\.\d+)?$""")
    private val MINUTES_SECONDS = Regex("""^(\d+):(\d{1,2}(?:\.\d+)?)$""")
    private val HOURS_MINUTES_SECONDS = Regex("""^(\d+):(\d{1,2}):(\d{1,2}(?:\.\d+)?)$""")

    /** Whole seconds (decimals rounded), or null if blank or not a duration. */
    fun parse(s: String?): Int? {
        val text = s?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val seconds: Double = when {
            SECONDS.matches(text) -> text.toDouble()
            MINUTES_SECONDS.matches(text) -> {
                val (minutes, secs) = MINUTES_SECONDS.find(text)!!.destructured
                if (secs.toDouble() >= 60) return null
                minutes.toDouble() * 60 + secs.toDouble()
            }
            HOURS_MINUTES_SECONDS.matches(text) -> {
                val (hours, minutes, secs) = HOURS_MINUTES_SECONDS.find(text)!!.destructured
                if (minutes.toInt() >= 60 || secs.toDouble() >= 60) return null
                hours.toDouble() * 3600 + minutes.toInt() * 60 + secs.toDouble()
            }
            else -> return null
        }
        if (seconds > Int.MAX_VALUE) return null
        return seconds.roundToInt()
    }
}
