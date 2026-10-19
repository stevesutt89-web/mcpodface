package com.steve.mcpodface.data.search

/**
 * Turns what the user types into a safe SQLite FTS4 `MATCH` expression for `episode_fts` (PLAN.md §7.2).
 *
 * Rules:
 * - Plain words are ANDed, each a prefix match: `climate change` becomes `climate* change*`.
 * - `"quoted text"` is an exact phrase: `"climate change"`.
 * - `OR` (upper case only) joins neighbouring terms: `nasa OR esa` becomes `nasa* OR esa*`.
 *   Lower-case `or` is an ordinary word. OR binds tighter than the implicit AND, so `a b OR c` means `a AND (b OR c)`.
 * - `-word` excludes: `climate -politics` becomes `climate* -politics*`. FTS4 needs a positive term, so an
 *   exclusion-only query returns null.
 * - `-"phrase"` excludes a phrase. FTS4 cannot match it in the MATCH expression, so it is returned in
 *   [Parsed.excludedPhrases] and the caller must remove those episodes in SQL, for example with
 *   `rowid NOT IN (SELECT rowid FROM episode_fts WHERE episode_fts MATCH '"phrase"')`.
 *   [build] does not include phrase exclusions, so use [parse] when the input can contain them.
 * - Everything else is dropped. Terms are made only of Unicode letters and digits, so quotes, `*`, parentheses, `:`,
 *   `^`, `-` and the keywords AND, NOT and NEAR can never reach SQLite as syntax. Input made of several words
 *   (`well-known`, `title:x`) is split on the separators.
 * - An unterminated `"` runs to the end of the input, so a phrase being typed still matches.
 * - Terms are lower-cased. Blank input, or input with no positive term, returns null.
 * - Limits: at most [MAX_TERMS] terms are emitted, and each term is at most [MAX_TERM_LENGTH] code points.
 *
 * Note on FTS4 here: the `NOT` keyword is not an operator in this SQLite build, so exclusions use `-`.
 */
object SearchQueryBuilder {
    const val MAX_TERMS = 32
    const val MAX_TERM_LENGTH = 64

    /**
     * [match] is the FTS4 expression. [terms] are the lower-cased positive words, for snippet highlighting.
     * [excludedPhrases] are lower-cased, space-separated phrases the results must not contain.
     */
    data class Parsed(val match: String, val terms: List<String>, val excludedPhrases: List<String>)

    /** The FTS4 MATCH expression for [input], or null when there is nothing to search for. See [Parsed.excludedPhrases]. */
    fun build(input: String): String? = parse(input)?.match

    fun parse(input: String): Parsed? {
        val groups = mutableListOf<MutableList<String>>() // AND-ed groups; each group is OR-ed alternatives
        val negatives = mutableListOf<String>()
        val excludedPhrases = mutableListOf<String>()
        val highlight = LinkedHashSet<String>()
        var budget = MAX_TERMS
        var joinNext = false   // an upper-case OR is waiting to join the next positive term to the previous group
        var negate = false     // a '-' is waiting to mark the next item as an exclusion

        fun addPositive(alternative: String, words: List<String>) {
            if (budget <= 0) return
            budget--
            if (joinNext && groups.isNotEmpty()) groups.last().add(alternative) else groups.add(mutableListOf(alternative))
            joinNext = false
            highlight.addAll(words)
        }

        fun addWords(words: List<String>, exactPhrase: Boolean) {
            if (words.isEmpty()) return
            when {
                negate && (exactPhrase || words.size > 1) -> {
                    if (budget > 0) {
                        budget--
                        excludedPhrases.add(words.joinToString(" "))
                    }
                    joinNext = false
                }
                negate -> {
                    if (budget > 0) {
                        budget--
                        negatives.add("-${words.single()}*")
                    }
                    joinNext = false
                }
                exactPhrase || (words.size > 1 && joinNext) ->
                    addPositive(words.joinToString(" ", prefix = "\"", postfix = "\""), words)
                else ->
                    // Plain words are ANDed, each as its own prefix group.
                    for (w in words) addPositive("$w*", listOf(w))
            }
        }

        var i = 0
        while (i < input.length) {
            val c = input[i]
            when {
                c.isWhitespace() -> i++
                c == '-' && !negate -> { negate = true; i++ }
                c == '"' -> {
                    val close = input.indexOf('"', i + 1).let { if (it < 0) input.length else it }
                    addWords(words(input.substring(i + 1, close)), exactPhrase = true)
                    i = if (close < input.length) close + 1 else close
                    negate = false
                }
                else -> {
                    val start = i
                    while (i < input.length && !input[i].isWhitespace() && input[i] != '"') i++
                    val raw = input.substring(start, i)
                    if (raw == "OR" && !negate) {
                        joinNext = groups.isNotEmpty()
                    } else {
                        addWords(words(raw), exactPhrase = false)
                    }
                    negate = false
                }
            }
        }

        if (groups.isEmpty()) return null
        val positive = groups.joinToString(" ") { it.joinToString(" OR ") }
        val match = (listOf(positive) + negatives).joinToString(" ")
        return Parsed(match, highlight.toList(), excludedPhrases.toList())
    }

    /**
     * Splits [raw] into lower-cased words made of letters and digits only. FTS keywords (upper-case AND, NOT, NEAR)
     * are dropped, and each word is capped at [MAX_TERM_LENGTH] code points.
     */
    private fun words(raw: String): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var length = 0
        fun flush() {
            if (current.isNotEmpty()) {
                val word = current.toString()
                if (word !in KEYWORDS) out.add(word.lowercase())
            }
            current.setLength(0)
            length = 0
        }
        var i = 0
        while (i < raw.length) {
            val cp = raw.codePointAt(i)
            if (Character.isLetterOrDigit(cp)) {
                if (length < MAX_TERM_LENGTH) {
                    current.appendCodePoint(cp)
                    length++
                }
            } else {
                flush()
            }
            i += Character.charCount(cp)
        }
        flush()
        return out
    }

    private val KEYWORDS = setOf("AND", "NOT", "NEAR")
}
