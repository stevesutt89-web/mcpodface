package com.steve.mcpodface.core

/**
 * Show notes HTML -> plain text, for the search index and list summaries (PLAN.md §4).
 * Pure Kotlin (no android.text.Html) so it runs in plain JVM tests and workers.
 */
object HtmlText {
    /** Full plain text: tags removed, block elements become line breaks, entities decoded, whitespace tidied. */
    fun toPlainText(html: String?): String = TODO("Task 1.2")

    /** The first ~[maxChars] characters of [plainText] on one line, cut at a word boundary with "…" if truncated. */
    fun summary(plainText: String, maxChars: Int = 300): String = TODO("Task 1.2")
}
