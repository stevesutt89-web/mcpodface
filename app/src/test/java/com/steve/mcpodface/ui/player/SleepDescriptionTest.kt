package com.steve.mcpodface.ui.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SleepDescriptionTest {

    @Test
    fun `off says sleep timer only`() {
        assertThat(sleepDescription(SleepTimerUi.Off)).isEqualTo("Sleep timer")
    }

    @Test
    fun `end of episode is spoken`() {
        assertThat(sleepDescription(SleepTimerUi.EndOfEpisode))
            .isEqualTo("Sleep timer, stops at end of episode")
    }

    @Test
    fun `remaining time is spoken with the clock value`() {
        // 14 minutes 32 seconds
        assertThat(sleepDescription(SleepTimerUi.Remaining(14 * 60_000L + 32_000L)))
            .isEqualTo("Sleep timer, 14:32 left")
    }
}
