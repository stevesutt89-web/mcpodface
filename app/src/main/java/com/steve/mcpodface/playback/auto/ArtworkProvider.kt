package com.steve.mcpodface.playback.auto

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.steve.mcpodface.core.Clock
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Hilt graph access for a ContentProvider, which Hilt cannot inject. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ArtworkProviderEntryPoint {
    fun okHttpClient(): OkHttpClient
    fun clock(): Clock
}

/**
 * Read-only provider that serves podcast artwork to Android Auto (task A3). Each
 * `content://${applicationId}.artwork/artwork?u=<url>` resolves to a ~512 px JPEG, cached in
 * `cacheDir/car-artwork/`. Exported because the car host, another app, reads it.
 */
class ArtworkProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    /** Called on a binder thread, so blocking network I/O here is fine. */
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("ArtworkProvider is read-only")
        val url = originalUrl(uri) ?: throw FileNotFoundException("Not an artwork URI: $uri")
        val context = requireNotNull(context)
        val file = cachedArtwork(context, url)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String = "image/jpeg"

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri =
        throw UnsupportedOperationException("ArtworkProvider is read-only")

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("ArtworkProvider is read-only")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("ArtworkProvider is read-only")

    private fun cachedArtwork(context: Context, url: String): File {
        val dir = File(context.cacheDir, CACHE_DIR).apply { mkdirs() }
        val target = File(dir, "${sha256Hex(url)}.jpg")
        val graph = EntryPointAccessors.fromApplication(context.applicationContext, ArtworkProviderEntryPoint::class.java)
        // Touched on every read, so trim() drops the least recently used images.
        if (target.isFile && target.length() > 0) return target.also { it.setLastModified(graph.clock().now()) }

        val bitmap = fetchBitmap(graph.okHttpClient(), url) ?: throw FileNotFoundException("Artwork unavailable: $url")
        try {
            // Write to a temp file, then rename, so a concurrent reader never sees a partial JPEG.
            val tmp = File.createTempFile("artwork", ".tmp", dir)
            try {
                tmp.outputStream().use { out ->
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) {
                        throw FileNotFoundException("Could not encode artwork: $url")
                    }
                }
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } finally {
                tmp.delete()
            }
        } finally {
            bitmap.recycle()
        }
        trim(dir)
        return target
    }

    /** Keeps the cache to the newest [MAX_FILES] images (about 30 KB each). */
    private fun trim(dir: File) {
        val files = dir.listFiles { f -> f.name.endsWith(".jpg") } ?: return
        if (files.size <= MAX_FILES) return
        files.sortedByDescending { it.lastModified() }.drop(MAX_FILES).forEach { it.delete() }
    }

    /** Downloads and decodes [url], or returns null if it is not a small enough image. */
    private fun fetchBitmap(client: OkHttpClient, url: String): Bitmap? {
        val bytes = client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body ?: return null
            if (body.contentLength() > MAX_BYTES) return null
            readBounded(body.byteStream()) ?: return null
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val decoded = BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize(maxOf(bounds.outWidth, bounds.outHeight))
            },
        ) ?: return null
        // JPEG has no alpha, so flatten transparent pixels onto white rather than black.
        val flat = Bitmap.createBitmap(decoded.width, decoded.height, Bitmap.Config.ARGB_8888)
        Canvas(flat).apply {
            drawColor(Color.WHITE)
            drawBitmap(decoded, 0f, 0f, null)
        }
        decoded.recycle()
        return flat
    }

    private fun readBounded(input: java.io.InputStream): ByteArray? = input.use { stream ->
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0L
        while (true) {
            val read = stream.read(buffer)
            if (read == -1) break
            total += read
            if (total > MAX_BYTES) return null
            out.write(buffer, 0, read)
        }
        out.toByteArray()
    }

    /** Power-of-two sample size that keeps the longest side at or under [MAX_SIDE] px. */
    private fun sampleSize(longestSide: Int): Int {
        var sample = 1
        while (longestSide / sample > MAX_SIDE) sample *= 2
        return sample
    }

    private fun sha256Hex(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val CACHE_DIR = "car-artwork"
        const val MAX_BYTES = 10L * 1024 * 1024
        const val MAX_SIDE = 512
        const val JPEG_QUALITY = 85
        const val MAX_FILES = 500
    }
}
