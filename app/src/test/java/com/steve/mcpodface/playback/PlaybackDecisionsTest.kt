package com.steve.mcpodface.playback

import com.google.common.truth.Truth.assertThat
import com.steve.mcpodface.data.db.PlayState
import org.junit.Test

class PlaybackDecisionsTest {
    private val now = 1_000_000_000L
    private val hour = 3_600_000L

    private fun start(
        pos: Long = 0,
        state: PlayState = PlayState.UNPLAYED,
        lastPlayedAt: Long? = null,
        rewind: Boolean = true,
        rewindSec: Int = 5,
        longPauseMin: Int = 1,
        intro: Int = 0,
        duration: Long? = hour,
    ) = PlaybackDecisions.startPosition(pos, state, lastPlayedAt, now, rewind, rewindSec, longPauseMin, intro, duration)

    @Test fun freshEpisodeStartsAtZero() = assertThat(start()).isEqualTo(0)

    @Test fun freshEpisodeSkipsIntro() = assertThat(start(intro = 45)).isEqualTo(45_000)

    @Test fun resumesAtSavedPosition() =
        assertThat(start(pos = 600_000, state = PlayState.IN_PROGRESS, lastPlayedAt = now - 30_000)).isEqualTo(600_000)

    @Test fun skipIntroIgnoredWhenResuming() =
        assertThat(start(pos = 600_000, state = PlayState.IN_PROGRESS, lastPlayedAt = now - 30_000, intro = 45)).isEqualTo(600_000)

    @Test fun rewindsFiveSecondsAfterOneMinutePauseByDefault() =
        assertThat(start(pos = 600_000, state = PlayState.IN_PROGRESS, lastPlayedAt = now - 60_000)).isEqualTo(595_000)

    @Test fun noRewindAfterShortPause() =
        assertThat(start(pos = 600_000, state = PlayState.IN_PROGRESS, lastPlayedAt = now - 59_000)).isEqualTo(600_000)

    @Test fun rewindAmountIsConfigurable() =
        assertThat(start(pos = 600_000, state = PlayState.IN_PROGRESS, lastPlayedAt = now - hour, rewindSec = 30)).isEqualTo(570_000)

    @Test fun longPauseLengthIsConfigurable() {
        assertThat(start(pos = 600_000, state = PlayState.IN_PROGRESS, lastPlayedAt = now - 14 * 60_000, longPauseMin = 15))
            .isEqualTo(600_000)
        assertThat(start(pos = 600_000, state = PlayState.IN_PROGRESS, lastPlayedAt = now - 15 * 60_000, longPauseMin = 15))
            .isEqualTo(595_000)
    }

    @Test fun noRewindWhenSettingOff() =
        assertThat(start(pos = 600_000, state = PlayState.IN_PROGRESS, lastPlayedAt = now - hour, rewind = false)).isEqualTo(600_000)

    @Test fun rewindNeverGoesNegative() =
        assertThat(start(pos = 1_000, state = PlayState.IN_PROGRESS, lastPlayedAt = now - hour)).isEqualTo(0)

    @Test fun playedEpisodeReplaysFromStart() =
        assertThat(start(pos = 3_500_000, state = PlayState.PLAYED, intro = 10)).isEqualTo(10_000)

    @Test fun positionAtTheVeryEndRestarts() =
        assertThat(start(pos = hour - 500, state = PlayState.IN_PROGRESS)).isEqualTo(0)

    @Test fun unknownDurationKeepsPosition() =
        assertThat(start(pos = 99_000_000, state = PlayState.IN_PROGRESS, lastPlayedAt = now, duration = null)).isEqualTo(99_000_000)
}
