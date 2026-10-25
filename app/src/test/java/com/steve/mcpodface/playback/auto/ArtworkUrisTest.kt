package com.steve.mcpodface.playback.auto

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.steve.mcpodface.BuildConfig
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ArtworkUrisTest {

    @Test
    fun `https url round trips through the content uri`() {
        val url = "https://example.com/art/Café 你好.jpg?w=512&q=a+b=c#frag"

        val uri = artworkUri(url)!!

        assertThat(uri.scheme).isEqualTo("content")
        assertThat(originalUrl(uri)).isEqualTo(url)
    }

    @Test
    fun `http url round trips too`() {
        val url = "http://cdn.example.org/podcast.png"

        assertThat(originalUrl(artworkUri(url)!!)).isEqualTo(url)
    }

    @Test
    fun `null and blank urls give null`() {
        assertThat(artworkUri(null)).isNull()
        assertThat(artworkUri("")).isNull()
        assertThat(artworkUri("   ")).isNull()
    }

    @Test
    fun `non http scheme passes through unchanged`() {
        val url = "content://some.other.app/images/1"

        assertThat(artworkUri(url)).isEqualTo(Uri.parse(url))
        assertThat(artworkUri("file:///data/local/art.png")).isEqualTo(Uri.parse("file:///data/local/art.png"))
    }

    @Test
    fun `artwork uri uses the applicationId authority`() {
        val uri = artworkUri("https://example.com/a.jpg")!!

        assertThat(uri.authority).isEqualTo(BuildConfig.APPLICATION_ID + ".artwork")
        assertThat(ARTWORK_AUTHORITY_SUFFIX).isEqualTo(".artwork")
    }

    @Test
    fun `originalUrl rejects a foreign authority`() {
        val uri = Uri.parse("content://com.other.app.artwork/artwork?u=https%3A%2F%2Fexample.com%2Fa.jpg")

        assertThat(originalUrl(uri)).isNull()
    }

    @Test
    fun `originalUrl rejects a non content scheme`() {
        val uri = Uri.parse(
            "https://${BuildConfig.APPLICATION_ID}.artwork/artwork?u=https%3A%2F%2Fexample.com%2Fa.jpg",
        )

        assertThat(originalUrl(uri)).isNull()
    }

    @Test
    fun `originalUrl rejects a non http target`() {
        val uri = Uri.Builder()
            .scheme("content")
            .authority(BuildConfig.APPLICATION_ID + ".artwork")
            .appendPath("artwork")
            .appendQueryParameter("u", "ftp://example.com/a.jpg")
            .build()

        assertThat(originalUrl(uri)).isNull()
    }

    @Test
    fun `originalUrl rejects a missing parameter`() {
        val uri = Uri.parse("content://${BuildConfig.APPLICATION_ID}.artwork/artwork")

        assertThat(originalUrl(uri)).isNull()
    }
}
