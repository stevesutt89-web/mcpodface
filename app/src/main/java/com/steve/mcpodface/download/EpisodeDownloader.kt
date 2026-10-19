package com.steve.mcpodface.download

import android.content.Context
import android.os.Environment
import com.steve.mcpodface.core.Clock
import com.steve.mcpodface.data.db.DownloadDao
import com.steve.mcpodface.data.db.DownloadState
import com.steve.mcpodface.data.db.EpisodeDao
import com.steve.mcpodface.data.db.EpisodeEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Where downloaded audio lives: app-private external storage, no permission needed (PLAN.md §6.7). */
@Singleton
class DownloadPaths @Inject constructor(@ApplicationContext private val context: Context) {
    /** Root of all downloads. Overridable in tests. */
    var root: File = context.getExternalFilesDir(Environment.DIRECTORY_PODCASTS) ?: File(context.filesDir, "podcasts")

    fun finalFile(episode: EpisodeEntity): File =
        File(File(root, episode.podcastId.toString()), "${episode.id}.${extensionFor(episode)}")

    fun partFile(episode: EpisodeEntity): File = File(finalFile(episode).path + ".part")

    companion object {
        /** File extension from the enclosure URL, else the MIME type, else mp3. */
        fun extensionFor(episode: EpisodeEntity): String {
            val fromUrl = episode.enclosureUrl.substringBefore('?').substringBefore('#')
                .substringAfterLast('/').substringAfterLast('.', "").lowercase()
            if (fromUrl in KNOWN_EXTENSIONS) return fromUrl
            return when (episode.enclosureType?.lowercase()?.substringBefore(';')?.trim()) {
                "audio/mp4", "audio/x-m4a", "audio/m4a", "audio/aac+mp4" -> "m4a"
                "audio/aac", "audio/aacp" -> "aac"
                "audio/ogg" -> "ogg"
                "audio/opus" -> "opus"
                "audio/wav", "audio/x-wav" -> "wav"
                "audio/flac" -> "flac"
                "video/mp4" -> "mp4"
                else -> "mp3"
            }
        }

        private val KNOWN_EXTENSIONS = setOf("mp3", "m4a", "aac", "ogg", "opus", "wav", "flac", "mp4", "m4v")
    }
}

sealed interface DownloadOutcome {
    data class Completed(val file: File, val bytes: Long) : DownloadOutcome
    /** Network or server trouble; the .part file is kept so the next attempt resumes. */
    data class Retryable(val message: String) : DownloadOutcome
    /** Not worth retrying (episode gone, 404/410, disk full…). */
    data class Failed(val message: String) : DownloadOutcome
}

/**
 * Downloads one episode to `<podcastId>/<episodeId>.<ext>`, writing to a `.part` file first and
 * resuming it with an HTTP Range request after an interruption (PLAN.md §6.6, §6.7). Updates the
 * downloads row as it goes. Cancellation (e.g. Wi-Fi lost) leaves the `.part` in place.
 */
class EpisodeDownloader @Inject constructor(
    private val client: OkHttpClient,
    private val paths: DownloadPaths,
    private val episodeDao: EpisodeDao,
    private val downloadDao: DownloadDao,
    private val clock: Clock,
) {
    suspend fun download(episodeId: Long, onProgress: (bytes: Long, total: Long?) -> Unit = { _, _ -> }): DownloadOutcome {
        val episode = episodeDao.getById(episodeId) ?: return DownloadOutcome.Failed("Episode no longer exists")
        val target = paths.finalFile(episode)
        val part = paths.partFile(episode)
        target.parentFile?.mkdirs()

        val alreadyHave = if (part.exists()) part.length() else 0L
        val request = Request.Builder().url(episode.enclosureUrl).apply {
            if (alreadyHave > 0) header("Range", "bytes=$alreadyHave-")
        }.build()

        try {
            client.newCall(request).execute().use { response ->
                when {
                    response.code == 416 && alreadyHave > 0 -> return finish(episode, part, target) // .part is complete
                    response.code == 404 || response.code == 410 -> return DownloadOutcome.Failed("HTTP ${response.code}")
                    !response.isSuccessful -> return DownloadOutcome.Retryable("HTTP ${response.code}")
                }
                val resuming = response.code == 206 && alreadyHave > 0
                if (!resuming && part.exists()) part.delete() // server ignored the Range: start over
                val body = response.body
                val contentLength = body.contentLength().takeIf { it >= 0 }
                val total = contentLength?.let { if (resuming) it + alreadyHave else it }
                var written = if (resuming) alreadyHave else 0L
                setProgress(episodeId, written, total)

                body.byteStream().use { input ->
                    FileOutputStream(part, resuming).use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var lastReport = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            written += n
                            val now = clock.now()
                            if (now - lastReport >= PROGRESS_INTERVAL_MS) {
                                lastReport = now
                                onProgress(written, total)
                                setProgress(episodeId, written, total)
                            }
                        }
                    }
                }
                if (total != null && written < total) return DownloadOutcome.Retryable("Connection closed early")
                return finish(episode, part, target)
            }
        } catch (e: IOException) {
            val message = e.message ?: e.javaClass.simpleName
            return if (message.contains("ENOSPC", ignoreCase = true) || message.contains("No space", ignoreCase = true)) {
                DownloadOutcome.Failed("Not enough storage space")
            } else {
                DownloadOutcome.Retryable(message)
            }
        } catch (e: IllegalArgumentException) {
            return DownloadOutcome.Failed("Invalid episode URL")
        }
    }

    private suspend fun finish(episode: EpisodeEntity, part: File, target: File): DownloadOutcome {
        if (target.exists()) target.delete()
        if (!part.renameTo(target)) return DownloadOutcome.Retryable("Could not save the file")
        val existing = downloadDao.get(episode.id)
        if (existing != null) {
            downloadDao.upsert(
                existing.copy(
                    state = DownloadState.COMPLETED,
                    filePath = target.path,
                    bytesDownloaded = target.length(),
                    totalBytes = target.length(),
                    downloadedAt = clock.now(),
                    error = null,
                ),
            )
        }
        return DownloadOutcome.Completed(target, target.length())
    }

    private suspend fun setProgress(episodeId: Long, written: Long, total: Long?) {
        downloadDao.updateProgress(episodeId, written, total)
    }

    companion object {
        private const val BUFFER_SIZE = 64 * 1024
        private const val PROGRESS_INTERVAL_MS = 500L
    }
}
