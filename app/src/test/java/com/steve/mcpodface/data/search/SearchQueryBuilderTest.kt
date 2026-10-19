package com.steve.mcpodface.data.search

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.steve.mcpodface.data.db.AppDatabase
import com.steve.mcpodface.data.db.EpisodeEntity
import com.steve.mcpodface.data.db.PodcastEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SearchQueryBuilderTest {
    private lateinit var db: AppDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After fun tearDown() = db.close()

    private fun build(input: String) = SearchQueryBuilder.build(input)

    // Plain words

    @Test fun plainWordsAreAndedPrefixes() {
        assertThat(build("climate change")).isEqualTo("climate* change*")
    }

    @Test fun extraWhitespaceAndCaseAreNormalised() {
        assertThat(build("  Climate \t  CHANGE  ")).isEqualTo("climate* change*")
    }

    @Test fun singleWordIsOnePrefix() {
        assertThat(build("podcast")).isEqualTo("podcast*")
    }

    @Test fun digitsAreWordCharacters() {
        assertThat(build("episode 2026")).isEqualTo("episode* 2026*")
    }

    @Test fun lowerCaseOrIsAnOrdinaryWord() {
        assertThat(build("nasa or esa")).isEqualTo("nasa* or* esa*")
    }

    @Test fun unicodeLettersSurviveAndAreLowerCased() {
        assertThat(build("café")).isEqualTo("café*")
        assertThat(build("CAFÉ Résumé")).isEqualTo("café* résumé*")
    }

    // Phrases

    @Test fun quotedPhraseMatchesExactly() {
        assertThat(build("\"climate change\"")).isEqualTo("\"climate change\"")
    }

    @Test fun phraseWithDiacriticsKeepsItsWords() {
        assertThat(build("\"café au lait\"")).isEqualTo("\"café au lait\"")
    }

    @Test fun unterminatedQuoteRunsToTheEnd() {
        assertThat(build("\"climate ch")).isEqualTo("\"climate ch\"")
    }

    @Test fun phraseAndWordsCombine() {
        assertThat(build("news \"climate change\"")).isEqualTo("news* \"climate change\"")
    }

    @Test fun emptyPhraseIsIgnored() {
        assertThat(build("\"\" news")).isEqualTo("news*")
    }

    // Exclusions

    @Test fun exclusionAloneReturnsNull() {
        assertThat(build("-politics")).isNull()
        assertThat(build("-politics -sport")).isNull()
    }

    @Test fun exclusionIsMinusPrefixTerm() {
        assertThat(build("climate -politics")).isEqualTo("climate* -politics*")
        assertThat(build("a -b -c")).isEqualTo("a* -b* -c*")
    }

    @Test fun excludedPhraseIsReturnedSeparately() {
        assertThat(build("news -\"exact phrase\"")).isEqualTo("news*")
        val parsed = SearchQueryBuilder.parse("news -\"Exact  Phrase\"")!!
        assertThat(parsed.match).isEqualTo("news*")
        assertThat(parsed.excludedPhrases).containsExactly("exact phrase")
    }

    @Test fun excludedMultiWordWordIsAPhrase() {
        val parsed = SearchQueryBuilder.parse("news -well-known")!!
        assertThat(parsed.match).isEqualTo("news*")
        assertThat(parsed.excludedPhrases).containsExactly("well known")
    }

    @Test fun lonelyDashIsIgnored() {
        assertThat(build("news -")).isEqualTo("news*")
    }

    // OR

    @Test fun orJoinsTwoWords() {
        assertThat(build("nasa OR esa")).isEqualTo("nasa* OR esa*")
    }

    @Test fun orChainsWithoutSpillingIntoOtherGroups() {
        assertThat(build("Climate OR Change OR Space")).isEqualTo("climate* OR change* OR space*")
    }

    @Test fun dangingOrIsIgnored() {
        assertThat(build("a OR")).isEqualTo("a*")
        assertThat(build("OR a")).isEqualTo("a*")
    }

    @Test fun orOnlyQueryReturnsNull() {
        assertThat(build("OR OR OR")).isNull()
    }

    @Test fun orBeforeExclusionIsDropped() {
        assertThat(build("a OR -b")).isEqualTo("a* -b*")
    }

    @Test fun orWithPhrase() {
        assertThat(build("\"exact one\" OR two")).isEqualTo("\"exact one\" OR two*")
    }

    // Blank and punctuation-only input

    @Test fun blankInputIsNull() {
        assertThat(build("")).isNull()
        assertThat(build("   \t\n")).isNull()
    }

    @Test fun punctuationOnlyIsNull() {
        assertThat(build("*")).isNull()
        assertThat(build("(((")).isNull()
        assertThat(build(")))")).isNull()
        assertThat(build("\"")).isNull()
        assertThat(build("\"\"")).isNull()
        assertThat(build("-\"\"")).isNull()
        assertThat(build("^ : ;")).isNull()
    }

    @Test fun emojiIsDropped() {
        assertThat(build("🎧 podcast 🎙️")).isEqualTo("podcast*")
        assertThat(build("🎧")).isNull()
    }

    // FTS syntax is stripped, not passed through

    @Test fun nearWithDistanceKeepsOnlyTheNumber() {
        assertThat(build("NEAR/3")).isEqualTo("3*")
        assertThat(build("climate NEAR/3 change")).isEqualTo("climate* 3* change*")
    }

    @Test fun columnFilterLosesItsColon() {
        assertThat(build("title:x")).isEqualTo("title* x*")
    }

    @Test fun upperCaseAndNotKeywordsAreDropped() {
        assertThat(build("AND")).isNull()
        assertThat(build("NOT")).isNull()
        assertThat(build("NOT foo")).isEqualTo("foo*")
        assertThat(build("foo AND bar")).isEqualTo("foo* bar*")
    }

    @Test fun parenthesesAndAsterisksAreStripped() {
        assertThat(build("(climate) *change*")).isEqualTo("climate* change*")
    }

    @Test fun caretAndHyphenInsideWordSplit() {
        assertThat(build("^foo")).isEqualTo("foo*")
        assertThat(build("well-known")).isEqualTo("well* known*")
    }

    @Test fun sqlInjectionTextIsJustWords() {
        assertThat(build("'; DROP TABLE episodes; --")).isEqualTo("drop* table* episodes*")
        // Inside quotes everything is literal text, so the OR is an ordinary word there.
        assertThat(build("x\" OR 1=1 --")).isEqualTo("x* \"or 1 1\"")
    }

    @Test fun whitespaceInsideTabsAndNewlinesSeparates() {
        assertThat(build("tab\tseparated\nnewline")).isEqualTo("tab* separated* newline*")
    }

    // Limits

    @Test fun veryLongWordIsCapped() {
        val result = build("a".repeat(10_000))
        assertThat(result).isEqualTo("a".repeat(SearchQueryBuilder.MAX_TERM_LENGTH) + "*")
    }

    @Test fun manyWordsAreCapped() {
        val input = (1..100).joinToString(" ") { "w$it" }
        val result = build(input)!!
        assertThat(result.split(" ")).hasSize(SearchQueryBuilder.MAX_TERMS)
    }

    @Test fun parseReturnsPositiveTermsForHighlighting() {
        val parsed = SearchQueryBuilder.parse("Climate \"exact one\" -politics")!!
        assertThat(parsed.match).isEqualTo("climate* \"exact one\" -politics*")
        assertThat(parsed.terms).containsExactly("climate", "exact", "one").inOrder()
        assertThat(parsed.excludedPhrases).isEmpty()
    }

    @Test fun parseOfExclusionOnlyIsNull() {
        assertThat(SearchQueryBuilder.parse("-politics")).isNull()
    }

    // Every hostile input must run against a real FTS4 table without throwing.

    @Test fun everyHostileOutputRunsAgainstTheRealFts4Table() = runTest {
        val podcastId = db.podcastDao().insert(PodcastEntity(feedUrl = "https://x/feed", title = "X"))
        db.episodeDao().insertAll(
            listOf(
                EpisodeEntity(
                    podcastId = podcastId, guid = "g1", title = "Climate change", descriptionText = "NASA and ESA",
                    pubDate = 0, enclosureUrl = "https://x/1.mp3", firstSeenAt = 0,
                ),
                EpisodeEntity(
                    podcastId = podcastId, guid = "g2", title = "Café au lait", descriptionText = "title x episode",
                    pubDate = 0, enclosureUrl = "https://x/2.mp3", firstSeenAt = 0,
                ),
            ),
        )
        val hostile = listOf(
            "\"", "\"\"\"", "\"unterminated", "*", "**", "*\"*", "NEAR/3", "NEAR/0 NEAR", "title:x", "title:\"x\"",
            "AND", "NOT", "OR", "AND OR NOT NEAR", "(((", ")))", "()", "(a OR b) AND (c", "-", "--", "- -",
            "-\"", "-*", "🎧🎙️🔥", "\uD83D", "\uDE00 lone surrogate", "a".repeat(10_000),
            "w ".repeat(500), "'; DROP TABLE episode_fts; --", "x\" OR 1=1 --", "\" MATCH '' OR '1'='1",
            "\\ \u0000 \u0001 %", "café́ résumé", "a OR OR OR b", "NEAR(a b, 3)", "^^^ :::",
        )
        val outputs = hostile.mapNotNull { build(it) }
        assertThat(outputs).isNotEmpty()
        for (match in outputs) {
            val cursor = db.openHelper.readableDatabase.query(
                "SELECT rowid FROM episode_fts WHERE episode_fts MATCH ?", arrayOf(match),
            )
            cursor.close()
        }
    }

    @Test fun matchesFollowAndOrAndExclusionSemanticsOnRealTable() = runTest {
        val podcastId = db.podcastDao().insert(PodcastEntity(feedUrl = "https://x/feed", title = "X"))
        fun episode(n: Int, title: String) = EpisodeEntity(
            podcastId = podcastId, guid = "g$n", title = title, descriptionText = "",
            pubDate = 0, enclosureUrl = "https://x/$n.mp3", firstSeenAt = 0,
        )
        db.episodeDao().insertAll(
            listOf(
                episode(1, "alpha beta"),
                episode(2, "gamma delta"),
                episode(3, "alpha gamma"),
            ),
        )

        fun matching(input: String): List<Long> {
            val cursor = db.openHelper.readableDatabase.query(
                "SELECT rowid FROM episode_fts WHERE episode_fts MATCH ? ORDER BY rowid",
                arrayOf(SearchQueryBuilder.build(input)!!),
            )
            return buildList { while (cursor.moveToNext()) add(cursor.getLong(0)) }.also { cursor.close() }
        }

        val ids = db.episodeDao().getForPodcast(podcastId).associate { it.guid to it.id }
        assertThat(matching("alpha OR gamma")).containsExactly(ids["g1"], ids["g2"], ids["g3"]).inOrder()
        assertThat(matching("alpha -beta")).containsExactly(ids["g3"])
        assertThat(matching("alpha gamma OR delta")).containsExactly(ids["g3"])
        assertThat(matching("\"alpha beta\"")).containsExactly(ids["g1"])
        assertThat(matching("alp")).containsExactly(ids["g1"], ids["g3"]).inOrder()

        // Phrase exclusions are applied in SQL: "alpha -\"alpha gamma\"" keeps only episodes without that phrase.
        val parsed = SearchQueryBuilder.parse("alpha -\"alpha gamma\"")!!
        val cursor = db.openHelper.readableDatabase.query(
            "SELECT rowid FROM episode_fts WHERE episode_fts MATCH ? " +
                "AND rowid NOT IN (SELECT rowid FROM episode_fts WHERE episode_fts MATCH ?) ORDER BY rowid",
            arrayOf(parsed.match, "\"${parsed.excludedPhrases.single()}\""),
        )
        val kept = buildList { while (cursor.moveToNext()) add(cursor.getLong(0)) }
        cursor.close()
        assertThat(kept).containsExactly(ids["g1"])
    }
}
