package com.steve.mcpodface.playback.auto

import android.net.Uri

/**
 * The artwork URI to give the car for a web artwork URL. Task A3 turns this into a
 * `content://${applicationId}.artwork/artwork?u=<encoded url>` URI served by ArtworkProvider,
 * since the car host loads `content://` artwork most reliably. For now it passes the URL through.
 */
fun artworkUri(url: String?): Uri? = url?.let(Uri::parse)
