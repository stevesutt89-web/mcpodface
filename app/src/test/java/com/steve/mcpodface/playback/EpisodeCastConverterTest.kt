package com.steve.mcpodface.playback

import androidx.core.net.toUri
import androidx.core.os.bundleOf
import androidx.media3.common.MediaItem
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EpisodeCastConverterTest {
    private fun item(uri: String, remote: String? = "https://cdn.example.com/ep.m4a", type: String? = "audio/x-m4a") =
        MediaItem.Builder()
            .setMediaId("42")
            .setUri(uri)
            .setRequestMetadata(
                MediaItem.RequestMetadata.Builder()
                    .setMediaUri(remote?.toUri())
                    .setExtras(bundleOf(EXTRA_ENCLOSURE_TYPE to type))
                    .build(),
            )
            .build()

    @Test fun downloadedFileIsSwappedForTheWebUrl() {
        val cast = forCast(item("file:///data/user/0/app/files/downloads/42.m4a"))
        assertThat(cast.localConfiguration!!.uri.toString()).isEqualTo("https://cdn.example.com/ep.m4a")
        assertThat(cast.localConfiguration!!.mimeType).isEqualTo("audio/x-m4a")
        assertThat(cast.mediaId).isEqualTo("42")
    }

    @Test fun streamingUrlIsKept() {
        val cast = forCast(item("https://feeds.example.com/redirect/ep.mp3"))
        assertThat(cast.localConfiguration!!.uri.toString()).isEqualTo("https://feeds.example.com/redirect/ep.mp3")
    }

    @Test fun missingOrNonAudioTypeFallsBackToMp3() {
        assertThat(forCast(item("https://x.example/a", type = null)).localConfiguration!!.mimeType).isEqualTo("audio/mpeg")
        assertThat(forCast(item("https://x.example/a", type = "text/html")).localConfiguration!!.mimeType).isEqualTo("audio/mpeg")
    }
}
