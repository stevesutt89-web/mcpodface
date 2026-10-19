package com.steve.mcpodface.data.transcripts

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.roundToLong

enum class TranscriptFormat { SRT, VTT, JSON, HTML, TEXT }

/**
 * Parses episode transcripts (PLAN.md §13): SRT, WebVTT, Podcasting 2.0 JSON, and plain text or HTML
 * as one untimed block. Lenient: unusable cues and segments are skipped. Pure Kotlin.
 */
object TranscriptParser {

    /**
     * Picks the format from the response Content-Type, then the URL's extension, then the body itself.
     * [contentType] may be null (cached entries do not keep it).
     */
    fun detect(contentType: String?, url: String, body: String): TranscriptFormat {
        val type = contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        when {
            "json" in type -> return TranscriptFormat.JSON
            type == "text/vtt" -> return TranscriptFormat.VTT
            type in SRT_TYPES -> return TranscriptFormat.SRT
            "html" in type -> return TranscriptFormat.HTML
        }
        val path = url.substringBefore('#').substringBefore('?').lowercase()
        when {
            path.endsWith(".json") -> return TranscriptFormat.JSON
            path.endsWith(".vtt") -> return TranscriptFormat.VTT
            path.endsWith(".srt") -> return TranscriptFormat.SRT
            path.endsWith(".html") || path.endsWith(".htm") -> return TranscriptFormat.HTML
        }
        return sniff(body)
    }

    fun parse(format: TranscriptFormat, body: String): List<TranscriptSegment> = when (format) {
        TranscriptFormat.SRT, TranscriptFormat.VTT -> parseCues(body)
        TranscriptFormat.JSON -> parseJson(body)
        TranscriptFormat.HTML -> plainBlock(stripHtml(body))
        TranscriptFormat.TEXT -> plainBlock(body)
    }

    /** Parses SRT or VTT cues. Works for both: SRT's numeric index lines are simply ignored. */
    fun parseCues(body: String): List<TranscriptSegment> {
        val blocks = body.replace("\r\n", "\n").replace('\r', '\n').split(BLANK_LINE)
        return blocks.mapNotNull { block ->
            val lines = block.lines()
            val timingIndex = lines.indexOfFirst { "-->" in it }
            if (timingIndex < 0) return@mapNotNull null
            val timing = TIMING.find(lines[timingIndex]) ?: return@mapNotNull null
            val raw = lines.drop(timingIndex + 1).joinToString("\n")
            val text = cleanCueText(raw)
            if (text.isEmpty()) return@mapNotNull null
            TranscriptSegment(
                startMs = timestampMs(timing.groupValues[1]),
                endMs = timestampMs(timing.groupValues[2]),
                speaker = VOICE_TAG.find(raw)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() },
                text = text,
            )
        }
    }

    /** Podcasting 2.0 JSON: `{"segments":[{"startTime":1.5,"endTime":4,"body":"…","speaker":"…"}]}`. */
    fun parseJson(body: String): List<TranscriptSegment> {
        val root = runCatching { Json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return emptyList()
        val segments = root["segments"] as? JsonArray ?: return emptyList()
        return segments.mapNotNull { element ->
            val segment = element as? JsonObject ?: return@mapNotNull null
            val text = text(segment["body"])?.let(::normaliseWhitespace) ?: return@mapNotNull null
            TranscriptSegment(
                startMs = seconds(segment["startTime"]),
                endMs = seconds(segment["endTime"]),
                speaker = text(segment["speaker"]),
                text = text,
            )
        }
    }

    private fun sniff(body: String): TranscriptFormat {
        val head = body.trimStart('﻿', ' ', '\t', '\r', '\n')
        return when {
            head.startsWith("WEBVTT") -> TranscriptFormat.VTT
            head.startsWith("{") || head.startsWith("[") -> TranscriptFormat.JSON
            head.startsWith("<") -> TranscriptFormat.HTML
            SRT_COMMA_TIMING.containsMatchIn(body) -> TranscriptFormat.SRT
            TIMING.containsMatchIn(body) -> TranscriptFormat.VTT
            else -> TranscriptFormat.TEXT
        }
    }

    private fun plainBlock(text: String): List<TranscriptSegment> {
        val clean = normaliseWhitespace(text)
        return if (clean.isEmpty()) emptyList() else listOf(TranscriptSegment(null, null, null, clean))
    }

    /** Tags removed, voice tags and entities handled, and each line trimmed; the cue reads as one run of text. */
    private fun cleanCueText(raw: String): String =
        normaliseWhitespace(decodeEntities(TAG.replace(raw, "")))

    private fun stripHtml(html: String): String {
        var text = SCRIPT_OR_STYLE.replace(html, "")
        text = BLOCK_BREAK.replace(text, "\n")
        text = decodeEntities(TAG.replace(text, ""))
        return text
    }

    /** Trims each line, drops blank ones and joins the rest with spaces; runs of spaces collapse. */
    private fun normaliseWhitespace(text: String): String =
        text.lines().map { it.trim().replace(SPACES, " ") }.filter { it.isNotEmpty() }.joinToString(" ")

    private fun decodeEntities(text: String): String = ENTITY.replace(text) { match ->
        val value = match.groupValues[1]
        when {
            value.startsWith("#x") || value.startsWith("#X") -> value.drop(2).toIntOrNull(16)?.let(::codePoint)
            value.startsWith("#") -> value.drop(1).toIntOrNull()?.let(::codePoint)
            else -> NAMED_ENTITIES[value]
        } ?: match.value
    }

    private fun codePoint(code: Int): String? =
        if (code in 0..0x10FFFF) String(Character.toChars(code)) else null

    private fun timestampMs(raw: String): Long {
        val normalised = raw.replace(',', '.')
        val clock = normalised.substringBefore('.')
        val fraction = normalised.substringAfter('.', "").padEnd(3, '0').take(3)
        val seconds = clock.split(':').fold(0L) { total, part -> total * 60 + part.toLong() }
        return seconds * 1000L + fraction.toLong()
    }

    private fun text(element: JsonElement?): String? =
        (element as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.trim()?.takeIf { it.isNotEmpty() }

    private fun seconds(element: JsonElement?): Long? {
        val primitive = (element as? JsonPrimitive)?.takeIf { it !is JsonNull } ?: return null
        val value = primitive.doubleOrNull ?: primitive.content.toDoubleOrNull() ?: return null
        return if (value < 0) null else (value * 1000.0).roundToLong()
    }

    private val BLANK_LINE = Regex("\n\\s*\n")
    private val TIMING = Regex("""((?:\d+:)?\d{1,2}:\d{2}[.,]\d{1,3})\s*-->\s*((?:\d+:)?\d{1,2}:\d{2}[.,]\d{1,3})""")
    private val SRT_COMMA_TIMING = Regex("""\d{1,2}:\d{2}:\d{2},\d{1,3}\s*-->""")
    private val VOICE_TAG = Regex("""<v(?:\.[\w.]+)?\s+([^>]+)>""")
    private val TAG = Regex("""<[^>]*>""")
    private val SCRIPT_OR_STYLE = Regex("""<(script|style|head)\b[^>]*>.*?</\1\s*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val BLOCK_BREAK = Regex("""<br\s*/?>|</(p|div|li|h[1-6]|tr)\s*>""", RegexOption.IGNORE_CASE)
    private val ENTITY = Regex("""&(#[xX][0-9a-fA-F]+|#\d+|[a-zA-Z]+);""")
    private val SPACES = Regex("""\s+""")
    private val SRT_TYPES = setOf("text/srt", "text/x-subrip", "application/x-subrip", "application/srt")
    private val NAMED_ENTITIES = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
    )
}
