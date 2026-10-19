package com.steve.mcpodface.data.transcripts

import android.content.Context
import com.steve.mcpodface.core.di.IoDispatcher
import com.steve.mcpodface.data.chapters.TextDiskCache
import com.steve.mcpodface.data.chapters.fetchText
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Loads an episode's transcript (PLAN.md §13). Memory cache for the session, then the disk cache, then
 * the network. The disk entry keeps the detected format on its first line so it parses the same way offline.
 * Failures give an empty list and are not cached.
 */
@Singleton
class TranscriptRepository internal constructor(
    private val client: OkHttpClient,
    private val io: CoroutineDispatcher,
    cacheDirectory: File,
) {
    @Inject constructor(
        client: OkHttpClient,
        @IoDispatcher io: CoroutineDispatcher,
        @ApplicationContext context: Context,
    ) : this(client, io, File(context.cacheDir, "transcripts"))

    private val disk = TextDiskCache(cacheDirectory)
    private val memory = ConcurrentHashMap<String, List<TranscriptSegment>>()

    suspend fun load(url: String): List<TranscriptSegment> = withContext(io) {
        memory[url]?.let { return@withContext it }

        disk.read(url)?.let { cached ->
            val formatName = cached.substringBefore('\n')
            val format = TranscriptFormat.entries.firstOrNull { it.name == formatName }
            if (format != null) {
                val segments = TranscriptParser.parse(format, cached.substringAfter('\n'))
                memory[url] = segments
                return@withContext segments
            }
        }

        val fetched = fetchText(client, url, ACCEPT) ?: return@withContext emptyList()
        val format = TranscriptParser.detect(fetched.contentType, url, fetched.body)
        val segments = TranscriptParser.parse(format, fetched.body)
        disk.write(url, format.name + "\n" + fetched.body)
        memory[url] = segments
        segments
    }

    private companion object {
        const val ACCEPT = "text/vtt, application/x-subrip, application/json, text/html, text/plain;q=0.8, */*;q=0.5"
    }
}
