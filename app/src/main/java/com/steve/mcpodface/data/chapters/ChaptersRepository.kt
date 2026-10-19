package com.steve.mcpodface.data.chapters

import android.content.Context
import com.steve.mcpodface.core.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Loads an episode's Podcasting 2.0 JSON chapters. Memory cache for the session, then the disk cache
 * (so it works offline once seen), then the network. Failures give an empty list and are not cached.
 */
@Singleton
class ChaptersRepository internal constructor(
    private val client: OkHttpClient,
    private val io: CoroutineDispatcher,
    cacheDirectory: File,
) {
    @Inject constructor(
        client: OkHttpClient,
        @IoDispatcher io: CoroutineDispatcher,
        @ApplicationContext context: Context,
    ) : this(client, io, File(context.cacheDir, "chapters"))

    private val disk = TextDiskCache(cacheDirectory)
    private val memory = ConcurrentHashMap<String, List<Chapter>>()

    suspend fun load(url: String): List<Chapter> = withContext(io) {
        memory[url]?.let { return@withContext it }

        disk.read(url)?.let { cached ->
            ChapterParser.parseOrNull(cached)?.let { chapters ->
                memory[url] = chapters
                return@withContext chapters
            }
        }

        val fetched = fetchText(client, url, ACCEPT) ?: return@withContext emptyList()
        val chapters = ChapterParser.parseOrNull(fetched.body)
            ?: return@withContext emptyList() // Not a chapters document (e.g. an HTML error page): try again later.
        disk.write(url, fetched.body)
        memory[url] = chapters
        chapters
    }

    private companion object {
        const val ACCEPT = "application/json, */*;q=0.5"
    }
}
