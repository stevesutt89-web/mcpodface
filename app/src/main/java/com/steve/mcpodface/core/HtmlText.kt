package com.steve.mcpodface.core

/**
 * Show notes HTML -> plain text, for the search index and list summaries (PLAN.md §4).
 * Pure Kotlin (no android.text.Html) so it runs in plain JVM tests and workers.
 */
object HtmlText {
    /**
     * Full plain text: tags removed, block elements become line breaks, entities decoded, whitespace tidied.
     * Some feeds double-encode their notes (`&lt;b&gt;`), which decodes to literal tags: those get a second pass.
     */
    fun toPlainText(html: String?): String {
        val once = strip(html)
        return if (DOUBLE_ENCODED_TAG.containsMatchIn(once)) strip(once) else once
    }

    /** Common show-notes tags appearing as text after one pass. */
    private val DOUBLE_ENCODED_TAG = Regex("""</?(b|i|em|strong|p|br|a|ul|ol|li|div|span|h[1-6]|blockquote|img)(\s[^<>]*)?/?>""", RegexOption.IGNORE_CASE)

    private fun strip(html: String?): String {
        val s = html?.takeIf { it.isNotBlank() } ?: return ""
        val lastGt = s.lastIndexOf('>')
        val out = StringBuilder(s.length)
        var textFrom = 0
        var i = 0
        while (true) {
            val lt = s.indexOf('<', i)
            if (lt < 0) break
            val end = consumeMarkup(s, lt, lastGt, out, textFrom)
            if (end < 0) {
                i = lt + 1 // a literal '<' (e.g. "a < b"); it stays part of the pending text
            } else {
                textFrom = end
                i = end
            }
        }
        appendText(out, s, textFrom, s.length)
        return tidy(out.toString())
    }

    /** The first ~[maxChars] characters of [plainText] on one line, cut at a word boundary with "…" if truncated. */
    fun summary(plainText: String, maxChars: Int = 300): String {
        val flat = collapseSpaces(plainText)
        val limit = maxOf(maxChars, 0)
        if (flat.length <= limit) return flat
        val space = flat.lastIndexOf(' ', limit)
        var cut = if (space > 0) space else limit
        if (cut > 0 && flat[cut - 1].isHighSurrogate()) cut--
        return flat.substring(0, cut) + ELLIPSIS
    }

    private const val ELLIPSIS = "…"
    private const val BULLET = "•"
    private const val MAX_ENTITY_LENGTH = 32

    private val BLOCK_TAGS = setOf(
        "p", "div", "ul", "ol", "li", "h1", "h2", "h3", "h4", "h5", "h6",
        "blockquote", "tr", "table", "section", "article", "pre", "hr",
    )
    private val CELL_TAGS = setOf("td", "th")
    private val SKIP_CONTENT_TAGS = setOf("script", "style")

    /** Entity names for U+00C0..U+00FF, in code point order. Declared before its use in [NAMED_ENTITIES]. */
    private val LATIN1_ENTITY_NAMES: List<String> = """
        Agrave Aacute Acirc Atilde Auml Aring AElig Ccedil Egrave Eacute Ecirc Euml Igrave Iacute Icirc Iuml
        ETH Ntilde Ograve Oacute Ocirc Otilde Ouml times Oslash Ugrave Uacute Ucirc Uuml Yacute THORN szlig
        agrave aacute acirc atilde auml aring aelig ccedil egrave eacute ecirc euml igrave iacute icirc iuml
        eth ntilde ograve oacute ocirc otilde ouml divide oslash ugrave uacute ucirc uuml yacute thorn yuml
    """.trimIndent().split(Regex("\\s+"))

    /** Named entities we decode. Unknown names are left in the text untouched. */
    private val NAMED_ENTITIES: Map<String, String> = HashMap<String, String>().apply {
        LATIN1_ENTITY_NAMES.forEachIndexed { index, name -> put(name, Char(0xC0 + index).toString()) }
        putAll(
            mapOf(
                "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
                "nbsp" to " ",
                "mdash" to "—", "ndash" to "–", "hellip" to "…",
                "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”",
                "copy" to "©", "reg" to "®", "trade" to "™",
                "euro" to "€", "pound" to "£", "cent" to "¢", "yen" to "¥",
                "sect" to "§", "deg" to "°", "plusmn" to "±",
                "laquo" to "«", "raquo" to "»",
                "bull" to BULLET, "middot" to "·",
            ),
        )
    }

    /**
     * Handles the markup starting at [lt] (a '<'). Text in [textFrom], [lt) is flushed to [out] first.
     * Returns the index just past the markup, or -1 when the '<' is literal text.
     */
    private fun consumeMarkup(s: String, lt: Int, lastGt: Int, out: StringBuilder, textFrom: Int): Int {
        if (s.startsWith("<!--", lt)) {
            appendText(out, s, textFrom, lt)
            val close = s.indexOf("-->", lt + 4)
            return if (close < 0) s.length else close + 3
        }
        val c1 = s.getOrNull(lt + 1) ?: return -1
        if (c1 == '!' || c1 == '?') {
            val gt = findTagEnd(s, lt, lastGt)
            if (gt < 0) return -1
            appendText(out, s, textFrom, lt)
            return gt + 1
        }
        val closing = c1 == '/'
        val nameStart = if (closing) lt + 2 else lt + 1
        if (!isAsciiLetter(s.getOrNull(nameStart))) return -1
        var nameEnd = nameStart
        while (nameEnd < s.length && (isAsciiLetter(s[nameEnd]) || s[nameEnd] in '0'..'9')) nameEnd++

        val gt = findTagEnd(s, lt, lastGt)
        if (gt < 0) return -1
        appendText(out, s, textFrom, lt)

        val name = s.substring(nameStart, nameEnd).lowercase()
        if (!closing && name in SKIP_CONTENT_TAGS) {
            // Drop everything up to and including the matching closing tag.
            val close = s.indexOf("</$name", gt + 1, ignoreCase = true)
            if (close < 0) return s.length
            val closeGt = findTagEnd(s, close, lastGt)
            return if (closeGt < 0) s.length else closeGt + 1
        }
        when {
            name == "br" -> out.append('\n')
            name == "li" && !closing -> out.append("\n\n").append(BULLET).append(' ')
            name in BLOCK_TAGS -> out.append("\n\n")
            name in CELL_TAGS -> out.append(' ')
        }
        return gt + 1
    }

    /**
     * Index of the '>' that closes the tag starting at [lt], honouring quoted attribute values.
     * An unbalanced quote falls back to the first '>'. Returns -1 if there is no '>' at all.
     */
    private fun findTagEnd(s: String, lt: Int, lastGt: Int): Int {
        if (lastGt <= lt) return -1
        var quote = NO_QUOTE
        for (i in lt + 1 until s.length) {
            val c = s[i]
            if (quote != NO_QUOTE) {
                if (c == quote) quote = NO_QUOTE
            } else if (c == '"' || c == '\'') {
                quote = c
            } else if (c == '>') {
                return i
            }
        }
        return s.indexOf('>', lt + 1)
    }

    /** Appends the text in [from, to), decoding entities once and turning tabs into spaces. */
    private fun appendText(out: StringBuilder, s: String, from: Int, to: Int) {
        var i = from
        while (i < to) {
            val c = s[i]
            when (c) {
                '&' -> {
                    val semi = findEntityEnd(s, i, to)
                    val decoded = if (semi > 0) decodeEntity(s.substring(i + 1, semi)) else null
                    if (decoded != null) {
                        out.append(decoded)
                        i = semi + 1
                    } else {
                        out.append('&')
                        i++
                    }
                }
                '\r' -> i++
                '\t' -> {
                    out.append(' ')
                    i++
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
    }

    private fun findEntityEnd(s: String, amp: Int, to: Int): Int {
        val limit = minOf(to, amp + MAX_ENTITY_LENGTH + 1)
        for (j in amp + 1 until limit) {
            val c = s[j]
            if (c == ';') return j
            if (c == '&' || c == '<' || c.isWhitespace()) return -1
        }
        return -1
    }

    /** Decodes the text between '&' and ';'. Returns null when it is not a known or valid entity. */
    private fun decodeEntity(name: String): String? {
        if (name.isEmpty()) return null
        if (name[0] == '#') {
            val hex = name.length > 1 && (name[1] == 'x' || name[1] == 'X')
            val code = if (hex) name.substring(2).toIntOrNull(16) else name.substring(1).toIntOrNull()
            if (code == null || code <= 0 || code > 0x10FFFF || code in 0xD800..0xDFFF) return null
            if (code < 0x10000) return code.toChar().toString()
            val v = code - 0x10000
            return String(charArrayOf((0xD800 + (v shr 10)).toChar(), (0xDC00 + (v and 0x3FF)).toChar()))
        }
        return NAMED_ENTITIES[name]
    }

    /** Collapses whitespace runs in each line, trims lines, keeps at most one blank line in a row, trims the whole. */
    private fun tidy(raw: String): String {
        val sb = StringBuilder(raw.length)
        var blankPending = false
        for (line in raw.split('\n')) {
            val text = collapseSpaces(line)
            if (text.isEmpty()) {
                blankPending = sb.isNotEmpty()
                continue
            }
            if (sb.isNotEmpty()) {
                sb.append('\n')
                if (blankPending) sb.append('\n')
            }
            sb.append(text)
            blankPending = false
        }
        return sb.toString()
    }

    /** Every whitespace run becomes one space; leading and trailing whitespace is dropped. */
    private fun collapseSpaces(text: String): String {
        val sb = StringBuilder(text.length)
        var pendingSpace = false
        for (c in text) {
            if (c.isWhitespace()) {
                pendingSpace = sb.isNotEmpty()
            } else {
                if (pendingSpace) {
                    sb.append(' ')
                    pendingSpace = false
                }
                sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun isAsciiLetter(c: Char?): Boolean = c != null && (c in 'a'..'z' || c in 'A'..'Z')

    private const val NO_QUOTE = '\u0000'
}
