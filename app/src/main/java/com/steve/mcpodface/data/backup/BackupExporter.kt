package com.steve.mcpodface.data.backup

import androidx.room.withTransaction
import com.steve.mcpodface.BuildConfig
import com.steve.mcpodface.core.Clock
import com.steve.mcpodface.core.di.IoDispatcher
import com.steve.mcpodface.data.db.AppDatabase
import com.steve.mcpodface.data.prefs.DataStoreSettingsRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.FilterOutputStream
import java.io.OutputStream
import java.time.Instant
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import com.steve.mcpodface.data.db.PlaylistKind
import com.steve.mcpodface.data.playlist.SmartRules
import javax.inject.Inject

/**
 * Writes the backup zip (PLAN.md §9): manifest.json, data.json, subscriptions.opml and, optionally,
 * archive.jsonl.gz. The archive is streamed page by page, so memory stays flat however many
 * episodes there are.
 */
class BackupExporter @Inject constructor(
    private val db: AppDatabase,
    private val settings: DataStoreSettingsRepository,
    private val opml: OpmlExporter,
    private val clock: Clock,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    /**
     * Writes the zip to [out]. [out] is not closed, so the caller owns it. [onProgress] gets values in
     * 0..1 on the IO dispatcher. Runs on IO; cancel the calling coroutine to abort.
     */
    suspend fun export(out: OutputStream, includeArchive: Boolean, onProgress: (Float) -> Unit = {}) =
        withContext(io) {
            onProgress(0f)
            val now = clock.now()
            val data = snapshot()
            val archiveCount = if (includeArchive) db.episodeDao().countAll() else null
            val manifest = BackupManifest(
                formatVersion = BACKUP_FORMAT_VERSION,
                appVersion = BuildConfig.VERSION_NAME,
                createdAt = Instant.ofEpochMilli(now).toString(),
                counts = BackupCounts(
                    podcasts = data.podcasts.size,
                    episodes = data.episodes.size,
                    queue = data.queue.size,
                    history = data.history.size,
                    backfill = data.backfill.size,
                    archiveEpisodes = archiveCount,
                ),
            )

            // The zip gets a non-closing wrapper: ZipOutputStream.close() releases its deflater,
            // and the caller still owns `out`.
            ZipOutputStream(NonClosingOutputStream(out)).use { zip ->
                zip.writeEntry(BackupEntries.MANIFEST, BackupJson.encodeToString(BackupManifest.serializer(), manifest).toByteArray())
                zip.writeEntry(BackupEntries.DATA, BackupJson.encodeToString(BackupData.serializer(), data).toByteArray())

                zip.putNextEntry(ZipEntry(BackupEntries.OPML))
                opml.export(zip)
                zip.closeEntry()

                if (archiveCount != null) {
                    zip.putNextEntry(ZipEntry(BackupEntries.ARCHIVE))
                    writeArchive(zip, archiveCount, onProgress)
                    zip.closeEntry()
                }
            }
            onProgress(1f)
        }

    /** Reads all user state in one transaction, so data.json is a consistent snapshot. */
    private suspend fun snapshot(): BackupData = db.withTransaction {
        val podcastSettings = db.podcastSettingsDao().getAll().associateBy { it.podcastId }
        val podcasts = db.podcastDao().getSubscribed().map { p ->
            PodcastBackup(
                feedUrl = p.feedUrl,
                title = p.title,
                artworkUrl = p.artworkUrl,
                subscribedAt = p.subscribedAt,
                podcastIndexId = p.podcastIndexId,
                authUser = p.authUser,
                authPassword = null, // never written: backups may sit in cloud storage
                settings = podcastSettings[p.id]?.let { s ->
                    PodcastSettingsBackup(
                        autoDownload = s.autoDownload,
                        keepLatestCount = s.keepLatestCount,
                        deletePlayed = s.deletePlayed,
                        maxAgeDays = s.maxAgeDays,
                        allowCellular = s.allowCellular,
                        sortOrder = s.sortOrder?.name,
                        playbackSpeed = s.playbackSpeed,
                        skipIntroSec = s.skipIntroSec,
                        skipOutroSec = s.skipOutroSec,
                        notifyNewEpisodes = s.notifyNewEpisodes,
                        autoAddToQueue = s.autoAddToQueue.name,
                        backfillEnabled = s.backfillEnabled,
                    )
                },
            )
        }
        BackupData(
            formatVersion = BACKUP_FORMAT_VERSION,
            settings = settings.backupEntries(),
            podcasts = podcasts,
            episodes = db.episodeDao().backupStateRows().map { e ->
                EpisodeBackup(
                    feedUrl = e.feedUrl,
                    guid = e.guid,
                    enclosureUrl = e.enclosureUrl,
                    title = e.title,
                    pubDate = e.pubDate,
                    durationSec = e.durationSec,
                    playState = e.playState.name,
                    positionMs = e.positionMs,
                    playedAt = e.playedAt,
                    lastPlayedAt = e.lastPlayedAt,
                    isStarred = e.isStarred,
                    autoDownloadBlocked = e.autoDownloadBlocked,
                )
            },
            queue = db.queueDao().backupRows().map { QueueBackup(it.feedUrl, it.guid) },
            history = db.listeningSessionDao().backupRows().map { s ->
                HistoryBackup(s.feedUrl, s.guid, s.startedAt, s.listenedMs, s.speed)
            },
            playlists = playlistBackups(),
            backfill = db.backfillStateDao().backupRows().map { b ->
                BackfillBackup(
                    feedUrl = b.feedUrl,
                    status = b.status.name,
                    rssNextPageUrl = b.rssNextPageUrl,
                    rssPagingDone = b.rssPagingDone,
                    indexDone = b.indexDone,
                    indexCursor = b.indexCursor,
                    requestsMade = b.requestsMade,
                    episodesAdded = b.episodesAdded,
                    oldestKnownPubDate = b.oldestKnownPubDate,
                )
            },
        )
    }

    private suspend fun playlistBackups(): List<PlaylistBackup> {
        val feedById = HashMap<Long, String?>()
        suspend fun feedOf(podcastId: Long) = feedById.getOrPut(podcastId) { db.podcastDao().getById(podcastId)?.feedUrl }
        return db.playlistDao().getAll().map { pl ->
            val rules = if (pl.kind == PlaylistKind.SMART) SmartRules.fromJson(pl.rulesJson) else null
            PlaylistBackup(
                name = pl.name,
                kind = pl.kind.name,
                position = pl.position,
                items = db.playlistDao().getItems(pl.id).mapNotNull { item ->
                    val e = db.episodeDao().getById(item.episodeId) ?: return@mapNotNull null
                    feedOf(e.podcastId)?.let { QueueBackup(it, e.guid) }
                },
                rulesJson = rules?.copy(podcastIds = emptyList())?.toJson(),
                smartPodcastFeeds = rules?.podcastIds?.mapNotNull { feedOf(it) }.orEmpty(),
            )
        }
    }

    /**
     * Streams every episode row as gzipped JSON lines, one page at a time. The page size bounds memory;
     * the cursor is the last id seen.
     */
    private suspend fun writeArchive(zip: OutputStream, total: Int, onProgress: (Float) -> Unit) {
        var done = 0
        var afterId = 0L
        GZIPOutputStream(NonClosingOutputStream(zip)).bufferedWriter(Charsets.UTF_8).use { writer ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val page = db.episodeDao().archivePage(afterId, ARCHIVE_PAGE_SIZE)
                if (page.isEmpty()) break
                for (row in page) {
                    val line = ArchiveEpisodeLine(
                        feedUrl = row.feedUrl,
                        guid = row.guid,
                        title = row.title,
                        enclosureUrl = row.enclosureUrl,
                        pubDate = row.pubDate,
                        durationSec = row.durationSec,
                        description = row.descriptionHtml,
                    )
                    writer.write(BackupJson.encodeToString(ArchiveEpisodeLine.serializer(), line))
                    writer.write("\n")
                }
                done += page.size
                afterId = page.last().id
                if (total > 0) onProgress(ARCHIVE_PROGRESS_START + (1f - ARCHIVE_PROGRESS_START) * done / total)
            }
        }
    }

    private fun ZipOutputStream.writeEntry(name: String, bytes: ByteArray) {
        putNextEntry(ZipEntry(name))
        write(bytes)
        closeEntry()
    }

    private companion object {
        const val ARCHIVE_PAGE_SIZE = 1000
        /** Progress before the archive starts (manifest, data and OPML are done by then). */
        const val ARCHIVE_PROGRESS_START = 0.1f
    }
}

/** Passes writes through to [out] but ignores close, so the wrapped stream stays open for its owner. */
private class NonClosingOutputStream(out: OutputStream) : FilterOutputStream(out) {
    // FilterOutputStream writes byte by byte by default. Pass whole buffers through instead.
    override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)

    override fun close() = flush()
}
