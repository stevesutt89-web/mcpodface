package com.steve.mcpodface.domain.policy

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

class PlayedRuleTest {
    private val hour = 3_600_000L

    @Test fun table() {
        data class Case(val pos: Long, val dur: Long, val pct: Int, val outro: Int, val expected: Boolean)
        val cases = listOf(
            Case(pos = (hour * 0.96).toLong(), dur = hour, pct = 95, outro = 0, expected = true),
            Case(pos = (hour * 0.95).toLong(), dur = hour, pct = 95, outro = 0, expected = false), // must be MORE than 95%
            Case(pos = (hour * 0.94).toLong(), dur = hour, pct = 95, outro = 0, expected = false),
            Case(pos = (hour * 0.91).toLong(), dur = hour, pct = 90, outro = 0, expected = true),
            Case(pos = (hour * 0.97).toLong(), dur = hour, pct = 98, outro = 0, expected = false),
            Case(pos = hour, dur = hour, pct = 95, outro = 0, expected = true),
            Case(pos = hour - 60_000, dur = hour, pct = 99, outro = 60, expected = true), // reached skip-outro point
            Case(pos = hour - 61_000, dur = hour, pct = 99, outro = 60, expected = false),
            Case(pos = 1_000, dur = 0, pct = 95, outro = 0, expected = false), // unknown duration
        )
        for (c in cases) {
            assertWithMessage(c.toString()).that(PlayedRule.isPlayed(c.pos, c.dur, c.pct, c.outro)).isEqualTo(c.expected)
        }
    }
}
