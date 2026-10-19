package com.steve.mcpodface.data.chapters

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.roundToLong

/** One chapter the listener can see (Podcasting 2.0, PLAN.md §13). Pure Kotlin. */
data class Chapter(
    val startMs: Long,
    val title: String,
    val imageUrl: String? = null,
    val url: String? = null,
)

/** Within this many ms of a chapter's start, "previous chapter" goes to the one before it. */
const val CHAPTER_RESTART_THRESHOLD_MS = 3_000L

/**
 * Index into [chapters] (sorted by start, as [ChapterParser] returns them) of the chapter playing at
 * [positionMs], or null before the first chapter starts or when there are none.
 */
fun chapterIndexAt(chapters: List<Chapter>, positionMs: Long): Int? =
    chapters.indexOfLast { it.startMs <= positionMs }.takeIf { it >= 0 }

/**
 * Start of the chapter before the current one. More than [CHAPTER_RESTART_THRESHOLD_MS] into a chapter,
 * "previous" restarts it instead. Null when there is no chapter at or before [positionMs].
 */
fun previousChapterStartMs(chapters: List<Chapter>, positionMs: Long): Long? {
    val index = chapterIndexAt(chapters, positionMs) ?: return null
    val current = chapters[index]
    if (index == 0 || positionMs - current.startMs > CHAPTER_RESTART_THRESHOLD_MS) return current.startMs
    return chapters[index - 1].startMs
}

/** Start of the chapter after the current one (or the first chapter, before any has started). Null at the end. */
fun nextChapterStartMs(chapters: List<Chapter>, positionMs: Long): Long? {
    val next = chapterIndexAt(chapters, positionMs)?.plus(1) ?: 0
    return chapters.getOrNull(next)?.startMs
}

/**
 * Parses Podcasting 2.0 JSON chapters, `{"version":"1.2.0","chapters":[{"startTime":0,"title":"…"}]}`.
 * Lenient: entries without a usable startTime or title are skipped, and entries marked `toc: false`
 * are hidden. The result is sorted by start time.
 */
object ChapterParser {
    /** Null when [json] is not a JSON object with a `chapters` array (so the caller can treat it as a failure). */
    fun parseOrNull(json: String): List<Chapter>? {
        val root = runCatching { Json.parseToJsonElement(json) }.getOrNull() as? JsonObject ?: return null
        val entries = root["chapters"] as? JsonArray ?: return null
        return entries
            .mapNotNull { (it as? JsonObject)?.let(::chapterOrNull) }
            .sortedBy { it.startMs }
    }

    /** Lenient form of [parseOrNull]: an unusable document gives no chapters. */
    fun parse(json: String): List<Chapter> = parseOrNull(json).orEmpty()

    private fun chapterOrNull(entry: JsonObject): Chapter? {
        val startMs = seconds(entry["startTime"])?.let { (it * 1000.0).roundToLong() } ?: return null
        if (startMs < 0) return null
        val title = text(entry["title"]) ?: return null
        if (text(entry["toc"]).equals("false", ignoreCase = true)) return null
        return Chapter(
            startMs = startMs,
            title = title,
            imageUrl = text(entry["img"]),
            url = text(entry["url"]),
        )
    }

    private fun seconds(element: JsonElement?): Double? {
        val primitive = (element as? JsonPrimitive)?.takeIf { it !is JsonNull } ?: return null
        return primitive.doubleOrNull ?: primitive.content.toDoubleOrNull()
    }

    private fun text(element: JsonElement?): String? =
        (element as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.trim()?.takeIf { it.isNotEmpty() }
}
