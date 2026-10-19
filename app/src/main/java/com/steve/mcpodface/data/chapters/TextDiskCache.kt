package com.steve.mcpodface.data.chapters

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Small text cache in a directory (the app's cacheDir, so the OS may clear it). One file per URL,
 * named by its SHA-256. Used by the chapter and transcript repositories so content works offline once seen.
 */
internal class TextDiskCache(private val directory: File) {

    fun read(url: String): String? = runCatching {
        fileFor(url).takeIf { it.isFile }?.readText(Charsets.UTF_8)
    }.getOrNull()

    /** Writes via a temp file and rename, so a crash never leaves a half-written entry. Failures are ignored. */
    fun write(url: String, text: String) {
        runCatching {
            directory.mkdirs()
            val target = fileFor(url)
            val temp = File(directory, target.name + ".tmp")
            temp.writeText(text, Charsets.UTF_8)
            if (!temp.renameTo(target)) {
                target.delete()
                temp.renameTo(target)
            }
        }
    }

    private fun fileFor(url: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8))
        return File(directory, digest.joinToString("") { "%02x".format(it) })
    }
}

/** A downloaded text body and its Content-Type header, if the server sent one. */
internal class FetchedText(val contentType: String?, val body: String)

/**
 * GETs [url] and returns its body. Null on any failure (bad URL, network error, non-2xx, blank body),
 * so callers can show an empty result without caching the failure.
 */
internal fun fetchText(client: OkHttpClient, url: String, accept: String): FetchedText? = try {
    val request = Request.Builder().url(url).header("Accept", accept).build()
    client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) {
            null
        } else {
            val body = response.body.string()
            if (body.isBlank()) null else FetchedText(response.header("Content-Type"), body)
        }
    }
} catch (e: IOException) {
    null
} catch (e: IllegalArgumentException) {
    null
}
