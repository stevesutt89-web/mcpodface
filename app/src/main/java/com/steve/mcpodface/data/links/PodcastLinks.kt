package com.steve.mcpodface.data.links

// Pure Kotlin: no Android imports, so the parsing is unit-tested on the JVM.

/** What a link opened in, or shared to, McPodface points at. */
sealed interface PodcastLink {
    /** A feed URL. [fallbackUrl] is tried if [url] fails (an `http` copy of a guessed `https` URL). */
    data class Feed(val url: String, val fallbackUrl: String? = null) : PodcastLink

    /** An Apple Podcasts page; its feed URL comes from the iTunes lookup API. */
    data class ApplePodcasts(val id: Long) : PodcastLink
}

/** Schemes that wrap a feed URL: `podcast://host/feed.xml`, `feed:https://…`, `itpc://…`, `pcast://…`. */
private val FEED_SCHEMES = setOf("podcast", "pcast", "feed", "itpc", "podcasts")

private val URL_IN_TEXT = Regex("""(?i)\b(?:https?|podcasts?|pcast|feed|itpc):(?://)?[^\s<>"']+""")
private val APPLE_ID = Regex("""(?i)[/?&]id=?(\d{5,})""")

/**
 * Finds a podcast link in [text]: a VIEW intent's URI or the text of a share (which may wrap the
 * URL in other words). Returns null when there is no usable URL.
 */
fun parsePodcastLink(text: String): PodcastLink? {
    val raw = URL_IN_TEXT.find(text.trim())?.value?.trimEnd('.', ',', ')', ']', ';', '!') ?: return null
    val colon = raw.indexOf(':')
    val scheme = raw.substring(0, colon).lowercase()
    val rest = raw.substring(colon + 1)

    if (scheme in FEED_SCHEMES) {
        val inner = rest.removePrefix("//")
        // feed:https://example.com/rss or podcast://https://… carry the full URL.
        if (inner.startsWith("http://", ignoreCase = true) || inner.startsWith("https://", ignoreCase = true)) {
            return PodcastLink.Feed(inner)
        }
        if (inner.isBlank()) return null
        return PodcastLink.Feed("https://$inner", fallbackUrl = "http://$inner")
    }

    val afterScheme = rest.removePrefix("//")
    val host = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#').lowercase()
    if (host.isBlank()) return null
    return when {
        host == "podcasts.apple.com" || host == "itunes.apple.com" ->
            APPLE_ID.find(afterScheme)?.groupValues?.get(1)?.toLongOrNull()?.let { PodcastLink.ApplePodcasts(it) }
        host == "subscribeonandroid.com" || host == "www.subscribeonandroid.com" -> {
            // subscribeonandroid.com/<feed host and path>
            val inner = afterScheme.substringAfter('/', missingDelimiterValue = "")
            when {
                inner.isBlank() -> null
                inner.startsWith("http://", true) || inner.startsWith("https://", true) -> PodcastLink.Feed(inner)
                else -> PodcastLink.Feed("https://$inner", fallbackUrl = "http://$inner")
            }
        }
        // Anything else is taken as a feed URL (a shared RSS link); a web page fails to parse later.
        else -> PodcastLink.Feed("$scheme://$afterScheme")
    }
}
