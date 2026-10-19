package com.steve.mcpodface.ui.episode

import android.content.Context
import android.content.Intent
import com.steve.mcpodface.ui.player.formatClock

/**
 * The text a share sends (PLAN.md §13, task 9.7): "Episode — Podcast", then "(at 12:34)" when shared
 * from a position over 0, then the link on its own line. The link gets `#t=MM:SS` at that position.
 * The builder is plain Kotlin; only [shareEpisodeText] touches Android.
 */
fun buildEpisodeShareText(title: String, podcastTitle: String, link: String, positionMs: Long?): String {
    val timestamp = positionMs?.takeIf { it > 0L }?.let(::formatClock)
    val headline = buildString {
        append(title).append(" — ").append(podcastTitle)
        if (timestamp != null) append(" (at ").append(timestamp).append(")")
    }
    val url = if (timestamp == null) link else withTimestamp(link, timestamp)
    return "$headline\n$url"
}

/**
 * Adds `t=<timestamp>` to [link]. A link without a fragment gets `#t=`. A link that already has one
 * keeps it, and the time goes into the query before it (`?t=` or `&t=`), since a second `#` is invalid.
 */
internal fun withTimestamp(link: String, timestamp: String): String {
    val hash = link.indexOf('#')
    if (hash < 0) return "$link#t=$timestamp"
    val base = link.substring(0, hash)
    val fragment = link.substring(hash)
    val separator = if (base.contains('?')) "&" else "?"
    return "$base${separator}t=$timestamp$fragment"
}

/** Opens the Android share sheet with [text] as plain text. */
fun Context.shareEpisodeText(text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    startActivity(Intent.createChooser(intent, null))
}
