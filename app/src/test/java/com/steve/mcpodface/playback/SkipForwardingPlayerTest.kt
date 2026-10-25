package com.steve.mcpodface.playback

import androidx.media3.common.PlaybackException
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SkipForwardingPlayerTest {

    @Test fun lostConnectionGetsAPlainMessage() {
        for (code in listOf(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT)) {
            val raw = PlaybackException("Source error", null, code)
            val shown = SkipForwardingPlayer.friendlyError(raw)
            assertThat(shown.message).isEqualTo(SkipForwardingPlayer.NO_CONNECTION_MESSAGE)
            assertThat(shown.errorCode).isEqualTo(code)
            assertThat(shown.cause).isSameInstanceAs(raw)
        }
    }

    @Test fun otherErrorsAreUnchanged() {
        val raw = PlaybackException("Bad file", null, PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED)
        assertThat(SkipForwardingPlayer.friendlyError(raw)).isSameInstanceAs(raw)
    }
}
