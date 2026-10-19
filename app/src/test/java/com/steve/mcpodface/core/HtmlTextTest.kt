package com.steve.mcpodface.core

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HtmlTextTest {

    // ---- toPlainText: null / blank / plain input -------------------------------------------

    @Test fun `null input gives empty string`() {
        assertThat(HtmlText.toPlainText(null)).isEqualTo("")
    }

    @Test fun `blank input gives empty string`() {
        assertThat(HtmlText.toPlainText("  \n\t ")).isEqualTo("")
    }

    @Test fun `plain text without tags passes through`() {
        assertThat(HtmlText.toPlainText("Hello world")).isEqualTo("Hello world")
    }

    @Test fun `plain text still decodes entities`() {
        assertThat(HtmlText.toPlainText("Tom &amp; Jerry")).isEqualTo("Tom & Jerry")
    }

    // ---- removal of script, style and comments ---------------------------------------------

    @Test fun `script and style are removed with their content`() {
        val html = "<p>Hi</p><script>var x = '<b>';</script><style>p{color:red}</style>"
        assertThat(HtmlText.toPlainText(html)).isEqualTo("Hi")
    }

    @Test fun `uppercase script without closing tag drops the rest`() {
        assertThat(HtmlText.toPlainText("Text<SCRIPT>alert(1)")).isEqualTo("Text")
    }

    @Test fun `comments are removed with their content`() {
        assertThat(HtmlText.toPlainText("a<!-- hidden <p>x</p> -->b")).isEqualTo("ab")
    }

    @Test fun `unclosed comment drops the rest`() {
        assertThat(HtmlText.toPlainText("keep<!-- never closed")).isEqualTo("keep")
    }

    @Test fun `doctype declaration is removed`() {
        assertThat(HtmlText.toPlainText("<!DOCTYPE html>Hi")).isEqualTo("Hi")
    }

    // ---- line and paragraph structure ------------------------------------------------------

    @Test fun `br becomes a newline in all spellings`() {
        assertThat(HtmlText.toPlainText("line one<br>line two<br/>line three<BR />end"))
            .isEqualTo("line one\nline two\nline three\nend")
    }

    @Test fun `paragraphs are separated by one blank line`() {
        assertThat(HtmlText.toPlainText("<p>First</p><p>Second</p>")).isEqualTo("First\n\nSecond")
    }

    @Test fun `extra blank paragraphs collapse to one blank line`() {
        assertThat(HtmlText.toPlainText("<p>A</p>\n\n\n<p>B</p>")).isEqualTo("A\n\nB")
    }

    @Test fun `headings and divs are block elements`() {
        assertThat(HtmlText.toPlainText("<h2>Episode 5</h2><div>Body text</div>"))
            .isEqualTo("Episode 5\n\nBody text")
    }

    @Test fun `list items start with a bullet`() {
        assertThat(HtmlText.toPlainText("<ul><li>Alpha</li><li>Beta</li></ul>"))
            .isEqualTo("• Alpha\n\n• Beta")
    }

    @Test fun `timestamped chapter list keeps the en dash`() {
        val html = "<ul><li>12:34 &ndash; Intro</li><li>15:00 – Sponsor</li></ul>"
        assertThat(HtmlText.toPlainText(html))
            .isEqualTo("• 12:34 – Intro\n\n• 15:00 – Sponsor")
    }

    @Test fun `hr and self closing block tags separate paragraphs`() {
        assertThat(HtmlText.toPlainText("Intro<hr/>Outro")).isEqualTo("Intro\n\nOutro")
    }

    @Test fun `uppercase block tags are recognised`() {
        assertThat(HtmlText.toPlainText("<P>Hi</P><BR>there")).isEqualTo("Hi\n\nthere")
    }

    // ---- tag stripping ----------------------------------------------------------------------

    @Test fun `links keep their text and lose the markup`() {
        val html = "Visit <a href=\"https://example.com/\">our site</a> today."
        assertThat(HtmlText.toPlainText(html)).isEqualTo("Visit our site today.")
    }

    @Test fun `greater-than inside a quoted attribute does not end the tag`() {
        assertThat(HtmlText.toPlainText("<a title=\"a > b\" href='x'>link</a>")).isEqualTo("link")
    }

    @Test fun `nested inline tags are stripped`() {
        assertThat(HtmlText.toPlainText("<div><p>One <b>bold <i>italic</i></b></p></div>"))
            .isEqualTo("One bold italic")
    }

    @Test fun `unclosed tags are tolerated`() {
        assertThat(HtmlText.toPlainText("<p>Unclosed para<p>Second <b>bold"))
            .isEqualTo("Unclosed para\n\nSecond bold")
    }

    @Test fun `stray less-than signs are text`() {
        assertThat(HtmlText.toPlainText("a < b and c > d")).isEqualTo("a < b and c > d")
        assertThat(HtmlText.toPlainText("x<3 love")).isEqualTo("x<3 love")
    }

    // ---- entities ---------------------------------------------------------------------------

    @Test fun `decimal and hex numeric entities decode`() {
        assertThat(HtmlText.toPlainText("It&#8217;s &#x2019;fine&#x2019;"))
            .isEqualTo("It’s ’fine’")
    }

    @Test fun `common named entities decode`() {
        val html = "&mdash; &ndash; &hellip; &rsquo;&lsquo;&rdquo;&ldquo; &copy; &reg; &trade; &euro;&pound; &bull; &middot;"
        assertThat(HtmlText.toPlainText(html))
            .isEqualTo("— – … ’‘”“ © ® ™ €£ • ·")
    }

    @Test fun `latin-1 accented letters decode`() {
        assertThat(HtmlText.toPlainText("&Eacute;cole &uuml;ber &ntilde;"))
            .isEqualTo("École über ñ")
    }

    @Test fun `nbsp becomes a regular space`() {
        assertThat(HtmlText.toPlainText("a&nbsp;&nbsp;b")).isEqualTo("a b")
    }

    @Test fun `unknown entities are left as they are`() {
        assertThat(HtmlText.toPlainText("&foo; &bogus")).isEqualTo("&foo; &bogus")
    }

    @Test fun `entities are decoded only once`() {
        assertThat(HtmlText.toPlainText("&amp;lt; &amp;amp;")).isEqualTo("&lt; &amp;")
    }

    @Test fun `decoded angle brackets are not re-read as tags`() {
        assertThat(HtmlText.toPlainText("&lt;p&gt;not a tag&lt;/p&gt;")).isEqualTo("<p>not a tag</p>")
    }

    // ---- whitespace -------------------------------------------------------------------------

    @Test fun `runs of spaces and tabs collapse and each line is trimmed`() {
        assertThat(HtmlText.toPlainText("a \t  b   c")).isEqualTo("a b c")
        assertThat(HtmlText.toPlainText("  a  \n   b  ")).isEqualTo("a\nb")
    }

    // ---- toPlainText: performance -----------------------------------------------------------

    @Test fun `200 KB of show notes processes quickly`() {
        val chunk = "<p>Chapter <a href=\"https://example.com/x?a=1&amp;b=2\">link</a> &amp; <b>bold</b> 12:34 &ndash; Topic</p>\n<ul><li>Item one</li></ul>\n"
        val html = chunk.repeat(200_000 / chunk.length + 1)
        val start = System.nanoTime()
        val text = HtmlText.toPlainText(html)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertThat(text).contains("12:34 – Topic")
        assertThat(elapsedMs).isLessThan(2_000L)
    }

    @Test fun `pathological unclosed angle brackets stay linear`() {
        val html = "<a ".repeat(50_000)
        assertThat(HtmlText.toPlainText(html)).isEqualTo(html.trim())
    }

    // ---- summary ----------------------------------------------------------------------------

    @Test fun `short text is returned unchanged`() {
        assertThat(HtmlText.summary("short text")).isEqualTo("short text")
    }

    @Test fun `summary collapses all whitespace including newlines`() {
        assertThat(HtmlText.summary("a\n\n  b\tc")).isEqualTo("a b c")
    }

    @Test fun `text of exactly maxChars is not truncated`() {
        assertThat(HtmlText.summary("abcde", 5)).isEqualTo("abcde")
    }

    @Test fun `long text is cut at the last word boundary`() {
        assertThat(HtmlText.summary("the quick brown fox", 12)).isEqualTo("the quick…")
    }

    @Test fun `space exactly at maxChars is a valid cut`() {
        assertThat(HtmlText.summary("abc def", 3)).isEqualTo("abc…")
    }

    @Test fun `text without spaces is hard cut`() {
        assertThat(HtmlText.summary("abcdefghij", 4)).isEqualTo("abcd…")
    }

    @Test fun `zero maxChars gives only the ellipsis`() {
        assertThat(HtmlText.summary("abc", 0)).isEqualTo("…")
    }

    @Test fun `hard cut never splits a surrogate pair`() {
        assertThat(HtmlText.summary("ab😀cd", 3)).isEqualTo("ab…")
    }

    @Test fun `default limit is 300 characters and result fits`() {
        val long = "word ".repeat(200)
        val result = HtmlText.summary(long)
        assertThat(result.endsWith("…")).isTrue()
        assertThat(result.length - 1).isAtMost(300)
        assertThat(result.dropLast(1)).isEqualTo(result.dropLast(1).trimEnd())
    }

    @Test fun `summary of show notes is one line`() {
        val text = HtmlText.toPlainText("<p>Opening line.</p><ul><li>Point A</li><li>Point B</li></ul>")
        assertThat(HtmlText.summary(text)).isEqualTo("Opening line. • Point A • Point B")
    }
}
