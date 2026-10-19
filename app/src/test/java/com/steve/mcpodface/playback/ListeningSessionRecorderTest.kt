package com.steve.mcpodface.playback

import com.google.common.truth.Truth.assertThat
import com.steve.mcpodface.core.Clock
import org.junit.Test

class ListeningSessionRecorderTest {
    private var now = 1_000_000L
    private val recorder = ListeningSessionRecorder(Clock { now })

    @Test fun recordsPlayToStop() {
        recorder.start(7, 1.5f)
        now += 60_000
        val s = recorder.stop()!!
        assertThat(s.episodeId).isEqualTo(7)
        assertThat(s.startedAt).isEqualTo(1_000_000L)
        assertThat(s.listenedMs).isEqualTo(60_000)
        assertThat(s.speed).isEqualTo(1.5f)
    }

    @Test fun dropsVeryShortSessions() {
        recorder.start(7, 1f)
        now += 4_999
        assertThat(recorder.stop()).isNull()
    }

    @Test fun stopWithoutStartIsNull() {
        assertThat(recorder.stop()).isNull()
    }

    @Test fun restartingSameEpisodeKeepsOriginalStart() {
        recorder.start(7, 1f)
        now += 10_000
        recorder.start(7, 1f)
        now += 10_000
        assertThat(recorder.stop()!!.listenedMs).isEqualTo(20_000)
    }

    @Test fun stopEndsTheSession() {
        recorder.start(7, 1f)
        now += 10_000
        recorder.stop()
        assertThat(recorder.isRecording).isFalse()
        assertThat(recorder.stop()).isNull()
    }
}
