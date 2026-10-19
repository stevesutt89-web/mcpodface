package com.steve.mcpodface.data.feed

import java.io.InputStream
import javax.inject.Inject

/** Parses RSS 2.0 podcast feeds with XmlPullParser (PLAN.md §5). */
class RssParser @Inject constructor() {
    /** @throws FeedParseException if the document is not a usable RSS feed. */
    fun parse(input: InputStream, feedUrl: String): ParsedFeed = TODO("Task 1.1")
}
