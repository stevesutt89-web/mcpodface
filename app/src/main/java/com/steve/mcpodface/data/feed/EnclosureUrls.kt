package com.steve.mcpodface.data.feed

/**
 * Normalizes enclosure URLs so the same audio file matches across feed versions and sources:
 * drops the scheme, query and fragment, lower-cases the host, and strips analytics prefixes
 * (podtrac, chartable, podsights, OP3…) that wrap the real URL (PLAN.md §7.4).
 */
object EnclosureUrls {

    // Each pattern matches one tracking hop at the start of a scheme-less URL.
    private val trackingPrefixes = listOf(
        Regex("""^(dts\.|www\.)?podtrac\.com/pts/redirect\.[a-z0-9]+/""", RegexOption.IGNORE_CASE),
        Regex("""^(dts\.|www\.)?podtrac\.com/redirect\.[a-z0-9]+/""", RegexOption.IGNORE_CASE),
        Regex("""^chtbl\.com/track/[^/]+/""", RegexOption.IGNORE_CASE),
        Regex("""^chrt\.fm/track/[^/]+/""", RegexOption.IGNORE_CASE),
        Regex("""^pdst\.fm/e/""", RegexOption.IGNORE_CASE),
        Regex("""^op3\.dev/e(,[^/]*)?/""", RegexOption.IGNORE_CASE),
        Regex("""^media\.blubrry\.com/[^/]+/""", RegexOption.IGNORE_CASE),
        Regex("""^pfx\.vpixl\.com/[^/]+/""", RegexOption.IGNORE_CASE),
        Regex("""^arttrk\.com/p/[^/]+/""", RegexOption.IGNORE_CASE),
        Regex("""^mgln\.ai/e/[^/]+/""", RegexOption.IGNORE_CASE),
        Regex("""^prfx\.byspotify\.com/e/""", RegexOption.IGNORE_CASE),
        Regex("""^verifi\.podscribe\.com/rss/p/""", RegexOption.IGNORE_CASE),
        Regex("""^claritaspod\.com/measure/""", RegexOption.IGNORE_CASE),
    )

    private val scheme = Regex("""^[a-z][a-z0-9+.-]*://""", RegexOption.IGNORE_CASE)

    fun normalize(url: String): String {
        var s = url.trim().replace(scheme, "")
        s = s.substringBefore('#').substringBefore('?')
        while (true) {
            val prefix = trackingPrefixes.firstOrNull { it.containsMatchIn(s) } ?: break
            s = s.replaceFirst(prefix, "").replace(scheme, "")
        }
        val slash = s.indexOf('/')
        return if (slash < 0) s.lowercase() else s.substring(0, slash).lowercase() + s.substring(slash)
    }
}
