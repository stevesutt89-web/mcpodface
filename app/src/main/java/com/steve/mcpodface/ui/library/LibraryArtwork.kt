package com.steve.mcpodface.ui.library

import android.content.Context
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.allowRgb565
import com.steve.mcpodface.core.di.ApplicationScope
import com.steve.mcpodface.data.db.AppDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pixel size podcast covers load at in the Library (and other [ShapedArtwork] tiles). One fixed
 * size means the warm-up below and the tiles share a single memory-cache entry per cover. 400px
 * covers a third of the screen width on QHD phones.
 */
private const val LIBRARY_ARTWORK_PX = 400

/** The request [ShapedArtwork] and [LibraryArtworkWarmer] both use, so they hit the same cache entry. */
internal fun libraryArtworkRequest(context: PlatformContext, url: String): ImageRequest =
    ImageRequest.Builder(context)
        .data(url)
        .size(LIBRARY_ARTWORK_PX)
        // Covers have no transparency; 565 halves the memory, so every subscription fits in the cache.
        .allowRgb565(true)
        .build()

/**
 * Decodes every subscribed podcast's cover into Coil's memory cache at app start, and again when
 * the subscriptions change, so the Library grid shows its artwork the moment the tab opens instead
 * of decoding each full-size cover from the disk cache while you watch.
 */
@Singleton
class LibraryArtworkWarmer @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope private val appScope: CoroutineScope,
    private val db: AppDatabase,
) {
    fun start() {
        appScope.launch {
            db.podcastDao().observeSubscribed()
                .map { podcasts -> podcasts.mapNotNull { it.artworkUrl?.takeIf(String::isNotBlank) }.toSet() }
                .distinctUntilChanged()
                .collect { urls ->
                    val loader = SingletonImageLoader.get(context)
                    // Already-cached covers are memory hits, so re-running after a change is cheap.
                    urls.forEach { loader.enqueue(libraryArtworkRequest(context, it)) }
                }
        }
    }
}
