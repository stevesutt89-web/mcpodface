package com.steve.mcpodface.data.transcripts

/** One piece of a transcript. Plain-text and HTML transcripts are a single segment with no timing. Pure Kotlin. */
data class TranscriptSegment(
    val startMs: Long?,
    val endMs: Long?,
    val speaker: String?,
    val text: String,
)

/**
 * Index of the timed segment being spoken at [positionMs], or null. Segments are in document order
 * and sorted by start. Null in a gap after a segment's end, and before the first segment.
 */
fun segmentIndexAt(segments: List<TranscriptSegment>, positionMs: Long): Int? {
    val index = segments.indexOfLast { it.startMs != null && it.startMs <= positionMs }
    if (index < 0) return null
    val end = segments[index].endMs
    return if (end != null && positionMs >= end) null else index
}
