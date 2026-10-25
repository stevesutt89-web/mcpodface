package com.steve.mcpodface.playback.auto

import android.net.Uri
import androidx.core.net.toUri
import com.steve.mcpodface.BuildConfig

/** Authority of the artwork provider is `${applicationId}` + this suffix (see AndroidManifest). */
const val ARTWORK_AUTHORITY_SUFFIX = ".artwork"

private const val ARTWORK_PATH = "artwork"
private const val ARTWORK_PARAM = "u"

/**
 * The artwork URI to give the car for a web artwork URL. Web (http/https) URLs become
 * `content://${applicationId}.artwork/artwork?u=<encoded url>`, served by ArtworkProvider, because
 * the car host loads `content://` artwork most reliably. Any other scheme passes through unchanged.
 */
fun artworkUri(url: String?): Uri? {
    if (url.isNullOrBlank()) return null
    val parsed = url.toUri()
    if (!isHttpUrl(parsed.scheme)) return parsed
    return Uri.Builder()
        .scheme("content")
        .authority(BuildConfig.APPLICATION_ID + ARTWORK_AUTHORITY_SUFFIX)
        .appendPath(ARTWORK_PATH)
        .appendQueryParameter(ARTWORK_PARAM, url)
        .build()
}

/** The original web URL behind one of our artwork URIs, or null if [uri] is not ours. */
internal fun originalUrl(uri: Uri): String? {
    if (uri.scheme != "content") return null
    if (uri.authority != BuildConfig.APPLICATION_ID + ARTWORK_AUTHORITY_SUFFIX) return null
    if (uri.pathSegments != listOf(ARTWORK_PATH)) return null
    val url = uri.getQueryParameter(ARTWORK_PARAM) ?: return null
    return url.takeIf { isHttpUrl(it.toUri().scheme) }
}

private fun isHttpUrl(scheme: String?): Boolean =
    scheme.equals("http", ignoreCase = true) || scheme.equals("https", ignoreCase = true)
