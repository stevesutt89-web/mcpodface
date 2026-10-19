package com.steve.mcpodface.data.backup

import androidx.room.withTransaction
import com.steve.mcpodface.core.di.ApplicationScope
import com.steve.mcpodface.core.di.IoDispatcher
import com.steve.mcpodface.data.db.AppDatabase
import com.steve.mcpodface.data.db.BackfillStateEntity
import com.steve.mcpodface.data.db.BackfillStatus
import com.steve.mcpodface.data.db.EpisodeEntity
import com.steve.mcpodface.data.db.EpisodeOrigin
import com.steve.mcpodface.data.db.ListeningSessionEntity
import com.steve.mcpodface.data.db.PlayState
import com.steve.mcpodface.data.db.PodcastEntity
import com.steve.mcpodface.data.db.PlaylistKind
import com.steve.mcpodface.data.db.PodcastSettingsEntity
import com.steve.mcpodface.data.playlist.PlaylistRepository
import com.steve.mcpodface.data.playlist.SmartRules
import com.steve.mcpodface.data.db.QueueAdd
import com.steve.mcpodface.data.db.SortOrder
import com.steve.mcpodface.data.feed.EnclosureUrls
import com.steve.mcpodface.data.feed.FeedRepository
import com.steve.mcpodface.data.feed.ParsedEpisode
import com.steve.mcpodface.data.prefs.DataStoreSettingsRepository
import com.steve.mcpodface.refresh.RefreshCoordinator
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import java.io.InputStream
import java.net.URI
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream
import javax.inject.Inject

enum class ImportMode {
    /** Keep what's on this phone and add the backup to it (default). */
    MERGE,

    /** Wipe podcasts, episodes and history first, then restore the backup. */
    REPLACE,
}

sealed interface ImportResult {
    data class Ok(val podcasts: Int, val episodeStates: Int, val archiveEpisodes: Int, val queue: Int, val history: Int) : ImportResult
    data class Failed(val message: String) : ImportResult
}

/**
 * Restores a [BackupExporter] zip (PLAN.md §9), entry by entry in the order the exporter writes
 * them: data.json (podcasts and settings first), then archive.jsonl.gz streamed through the
 * merger in batches, then episode state, queue, history and backfill cursors. Works in batched
 * transactions; afterwards a full refresh and the policies run, which start the downloads.
 */
class BackupImporter @Inject constructor(
    private val db: AppDatabase,
    private val settings: DataStoreSettingsRepository,
    private val feeds: FeedRepository,
    private val refresh: dagger.Lazy<RefreshCoordinator>,
    private val playlistRepository: PlaylistRepository,
    @ApplicationScope private val appScope: CoroutineScope,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    /** feedUrl -> podcast id, filled as podcasts are restored or created. */
    private class Ids {
        val podcasts = HashMap<String, Long>()
        val episodes = HashMap<String, EpisodeIndex>()
    }

    /** Lookup of one podcast's episodes by guid, then by normalised enclosure URL. */
    private class EpisodeIndex(episodes: List<EpisodeEntity>) {
        val byGuid = HashMap<String, EpisodeEntity>()
        val byEnclosure = HashMap<String, EpisodeEntity>()

        init { episodes.forEach(::add) }

        fun add(e: EpisodeEntity) {
            byGuid.putIfAbsent(e.guid, e)
            byEnclosure.putIfAbsent(EnclosureUrls.normalize(e.enclosureUrl), e)
        }

        fun replace(e: EpisodeEntity) {
            byGuid[e.guid] = e
            byEnclosure[EnclosureUrls.normalize(e.enclosureUrl)] = e
        }

        fun find(guid: String, enclosureUrl: String?): EpisodeEntity? =
            byGuid[guid] ?: enclosureUrl?.let { byEnclosure[EnclosureUrls.normalize(it)] }
    }

    suspend fun import(
        input: InputStream,
        mode: ImportMode = ImportMode.MERGE,
        refreshAfter: Boolean = true,
        onProgress: (Float) -> Unit = {},
    ): ImportResult = withContext(io) {
        val ids = Ids()
        var data: BackupData? = null
        var sawManifest = false
        var archiveCount = 0
        try {
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    when (entry.name) {
                        BackupEntries.MANIFEST -> {
                            val m = BackupJson.decodeFromString(BackupManifest.serializer(), zip.readBytes().decodeToString())
                            if (m.formatVersion > BACKUP_FORMAT_VERSION) {
                                return@withContext ImportResult.Failed("This backup is from a newer version of McPodface. Update the app first.")
                            }
                            sawManifest = true
                        }
                        BackupEntries.DATA -> {
                            val d = BackupJson.decodeFromString(BackupData.serializer(), zip.readBytes().decodeToString())
                            if (d.formatVersion > BACKUP_FORMAT_VERSION) {
                                return@withContext ImportResult.Failed("This backup is from a newer version of McPodface. Update the app first.")
                            }
                            if (mode == ImportMode.REPLACE) clearForReplace()
                            restorePodcasts(d.podcasts, ids)
                            settings.restoreEntries(d.settings)
                            data = d
                            onProgress(0.1f)
                        }
                        BackupEntries.ARCHIVE -> archiveCount = restoreArchive(zip, ids, onProgress)
                        else -> Unit // subscriptions.opml is for other apps
                    }
                }
            }
        } catch (e: SerializationException) {
            return@withContext ImportResult.Failed("The backup file is damaged (${e.message}).")
        } catch (e: java.io.IOException) {
            return@withContext ImportResult.Failed("Couldn't read the backup file (${e.message}).")
        }
        val d = data
        if (!sawManifest || d == null) return@withContext ImportResult.Failed("This isn't a McPodface backup.")

        val states = restoreEpisodeStates(d.episodes, ids, mode)
        onProgress(0.85f)
        val queued = restoreQueue(d.queue, ids, mode)
        val history = restoreHistory(d.history, ids, mode)
        restoreBackfill(d.backfill, ids)
        restorePlaylists(d.playlists, ids)
        onProgress(1f)

        if (refreshAfter) appScope.launch { refresh.get().refreshAllAndApply() }
        ImportResult.Ok(d.podcasts.size, states, archiveCount, queued, history)
    }

    private suspend fun clearForReplace() = db.withTransaction {
        db.queueDao().clear()
        db.listeningSessionDao().clearHistory()
        db.podcastDao().deleteAll() // cascades to episodes, settings, downloads and backfill state
    }

    private suspend fun restorePodcasts(podcasts: List<PodcastBackup>, ids: Ids) = db.withTransaction {
        val dao = db.podcastDao()
        for (b in podcasts) {
            val existing = dao.getByFeedUrl(b.feedUrl)
            val id = if (existing == null) {
                dao.insert(
                    PodcastEntity(
                        feedUrl = b.feedUrl, title = b.title, artworkUrl = b.artworkUrl,
                        isSubscribed = true, subscribedAt = b.subscribedAt, podcastIndexId = b.podcastIndexId,
                        authUser = b.authUser, authPassword = b.authPassword,
                    ),
                )
            } else {
                dao.update(
                    existing.copy(
                        isSubscribed = true,
                        subscribedAt = existing.subscribedAt ?: b.subscribedAt,
                        podcastIndexId = existing.podcastIndexId ?: b.podcastIndexId,
                        authUser = existing.authUser ?: b.authUser,
                        authPassword = existing.authPassword ?: b.authPassword,
                    ),
                )
                existing.id
            }
            ids.podcasts[b.feedUrl] = id
            b.settings?.let { db.podcastSettingsDao().upsert(it.toEntity(id)) }
        }
    }

    /** Streams the archive, merging [ARCHIVE_BATCH] lines at a time per podcast. */
    private suspend fun restoreArchive(zip: ZipInputStream, ids: Ids, onProgress: (Float) -> Unit): Int {
        var count = 0
        val pending = LinkedHashMap<String, MutableList<ParsedEpisode>>()
        suspend fun flush() {
            for ((feedUrl, episodes) in pending) {
                val podcastId = podcastIdFor(feedUrl, ids)
                feeds.mergeBackfill(podcastId, episodes, EpisodeOrigin.FEED_PAGED)
                ids.episodes.remove(feedUrl) // re-read lazily, now with the new rows
            }
            pending.clear()
        }
        // Not closed: closing would close the zip stream underneath.
        val reader = GZIPInputStream(zip).bufferedReader()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isBlank()) continue
            val a = BackupJson.decodeFromString(ArchiveEpisodeLine.serializer(), line)
            pending.getOrPut(a.feedUrl) { mutableListOf() } += a.toParsed()
            count++
            if (count % ARCHIVE_BATCH == 0) {
                flush()
                onProgress((0.1f + count / 100_000f * 0.6f).coerceAtMost(0.7f))
            }
        }
        flush()
        return count
    }

    /**
     * Applies each backup state to the matching episode, creating the row from the backup's
     * metadata if this phone has never seen it. Merge keeps whichever side was played more recently
     * and never un-stars or un-blocks.
     */
    private suspend fun restoreEpisodeStates(states: List<EpisodeBackup>, ids: Ids, mode: ImportMode): Int {
        var applied = 0
        for (batch in states.chunked(STATE_BATCH)) {
            db.withTransaction {
                val updates = ArrayList<Pair<String, EpisodeEntity>>(batch.size)
                for (b in batch) {
                    val episode = episodeFor(b.feedUrl, b.guid, b.enclosureUrl, ids) {
                        ParsedEpisode(
                            guid = b.guid, title = b.title, descriptionHtml = null, pubDate = b.pubDate,
                            durationSec = b.durationSec, enclosureUrl = b.enclosureUrl, enclosureType = null,
                            enclosureLength = null, artworkUrl = null, season = null, episodeNumber = null,
                            chaptersUrl = null, transcriptUrl = null,
                        )
                    } ?: continue
                    updates += b.feedUrl to mergeState(episode, b, mode)
                    applied++
                }
                db.episodeDao().updateAll(updates.map { it.second })
                // Keep the in-memory index in step, in case the backup lists an episode twice.
                for ((feedUrl, u) in updates) ids.episodes[feedUrl]?.replace(u)
            }
        }
        return applied
    }

    private suspend fun restoreQueue(queue: List<QueueBackup>, ids: Ids, mode: ImportMode): Int {
        val restored = queue.mapNotNull { q -> episodeFor(q.feedUrl, q.guid, null, ids)?.id }
        val current = if (mode == ImportMode.MERGE) db.queueDao().getAll().sortedBy { it.position }.map { it.episodeId } else emptyList()
        val combined = (current + restored).distinct()
        db.queueDao().replaceAll(combined)
        return restored.size
    }

    private suspend fun restoreHistory(history: List<HistoryBackup>, ids: Ids, mode: ImportMode): Int {
        val existing = if (mode == ImportMode.MERGE) {
            db.listeningSessionDao().getAll().mapTo(HashSet()) { it.episodeId to it.startedAt }
        } else {
            emptySet()
        }
        var added = 0
        db.withTransaction {
            for (h in history) {
                val id = episodeFor(h.feedUrl, h.guid, null, ids)?.id ?: continue
                if ((id to h.startedAt) in existing) continue
                db.listeningSessionDao().insert(ListeningSessionEntity(episodeId = id, startedAt = h.startedAt, listenedMs = h.listenedMs, speed = h.speed))
                added++
            }
        }
        return added
    }

    /** Backfill cursors, so the new phone doesn't redo pages it already has. A local COMPLETE wins. */
    private suspend fun restoreBackfill(rows: List<BackfillBackup>, ids: Ids) = db.withTransaction {
        for (b in rows) {
            val podcastId = ids.podcasts[b.feedUrl] ?: db.podcastDao().getByFeedUrl(b.feedUrl)?.id ?: continue
            val local = db.backfillStateDao().get(podcastId)
            if (local?.status == BackfillStatus.COMPLETE) continue
            db.backfillStateDao().upsert(
                BackfillStateEntity(
                    podcastId = podcastId,
                    status = enumOr(b.status, BackfillStatus.PENDING).let { if (it == BackfillStatus.IN_PROGRESS) BackfillStatus.PENDING else it },
                    rssNextPageUrl = b.rssNextPageUrl, rssPagingDone = b.rssPagingDone, indexDone = b.indexDone,
                    indexCursor = b.indexCursor, requestsMade = b.requestsMade, episodesAdded = b.episodesAdded,
                    oldestKnownPubDate = b.oldestKnownPubDate,
                ),
            )
        }
    }

    /**
     * Playlists are matched by kind and name: Listen Later merges into this phone's, and a manual
     * playlist with the same name gets the backup's episodes appended. Smart playlists that already
     * exist by name are left alone.
     */
    private suspend fun restorePlaylists(playlists: List<PlaylistBackup>, ids: Ids) {
        val dao = db.playlistDao()
        for (b in playlists) {
            val kind = enumOr(b.kind, PlaylistKind.MANUAL)
            val existing = dao.getAll().firstOrNull { it.kind == kind && (kind == PlaylistKind.LISTEN_LATER || it.name == b.name) }
            when (kind) {
                PlaylistKind.SMART -> if (existing == null) {
                    val rules = SmartRules.fromJson(b.rulesJson)
                    val podcastIds = b.smartPodcastFeeds.mapNotNull { ids.podcasts[it] ?: db.podcastDao().getByFeedUrl(it)?.id }
                    playlistRepository.createSmart(b.name, rules.copy(podcastIds = podcastIds))
                }
                else -> {
                    val id = existing?.id ?: if (kind == PlaylistKind.LISTEN_LATER) playlistRepository.listenLaterId() else playlistRepository.createManual(b.name)
                    for (item in b.items) {
                        val episodeId = episodeFor(item.feedUrl, item.guid, null, ids)?.id ?: continue
                        playlistRepository.add(id, episodeId)
                    }
                }
            }
        }
    }

    /** The podcast for [feedUrl]; an unsubscribed stub if the backup only has its episodes. */
    private suspend fun podcastIdFor(feedUrl: String, ids: Ids): Long = ids.podcasts.getOrPut(feedUrl) {
        db.podcastDao().getByFeedUrl(feedUrl)?.id
            ?: db.podcastDao().insert(PodcastEntity(feedUrl = feedUrl, title = stubTitle(feedUrl), isSubscribed = false))
    }

    private suspend fun episodeFor(
        feedUrl: String,
        guid: String,
        enclosureUrl: String?,
        ids: Ids,
        create: (() -> ParsedEpisode)? = null,
    ): EpisodeEntity? {
        val podcastId = podcastIdFor(feedUrl, ids)
        val index = ids.episodes.getOrPut(feedUrl) { EpisodeIndex(db.episodeDao().getForPodcast(podcastId)) }
        index.find(guid, enclosureUrl)?.let { return it }
        if (create == null) return null
        feeds.mergeBackfill(podcastId, listOf(create()), EpisodeOrigin.FEED_PAGED)
        val fresh = EpisodeIndex(db.episodeDao().getForPodcast(podcastId))
        ids.episodes[feedUrl] = fresh
        return fresh.find(guid, enclosureUrl)
    }

    companion object {
        const val ARCHIVE_BATCH = 1_000
        const val STATE_BATCH = 500

        /** Merge rule for one episode's user state. Replace mode takes the backup as is. */
        internal fun mergeState(local: EpisodeEntity, b: EpisodeBackup, mode: ImportMode): EpisodeEntity {
            val backupState = enumOr(b.playState, PlayState.UNPLAYED)
            val backupNewer = mode == ImportMode.REPLACE || (b.lastPlayedAt ?: b.playedAt ?: 0) >= (local.lastPlayedAt ?: local.playedAt ?: 0)
            val base = if (backupNewer) {
                local.copy(playState = backupState, positionMs = b.positionMs, playedAt = b.playedAt, lastPlayedAt = b.lastPlayedAt)
            } else {
                local
            }
            return if (mode == ImportMode.REPLACE) {
                base.copy(isStarred = b.isStarred, autoDownloadBlocked = b.autoDownloadBlocked)
            } else {
                base.copy(isStarred = local.isStarred || b.isStarred, autoDownloadBlocked = local.autoDownloadBlocked || b.autoDownloadBlocked)
            }
        }

        private inline fun <reified E : Enum<E>> enumOr(name: String?, fallback: E): E =
            name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: fallback

        private fun stubTitle(feedUrl: String): String = runCatching { URI(feedUrl).host }.getOrNull() ?: feedUrl

        private fun ArchiveEpisodeLine.toParsed() = ParsedEpisode(
            guid = guid, title = title, descriptionHtml = description, pubDate = pubDate, durationSec = durationSec,
            enclosureUrl = enclosureUrl, enclosureType = null, enclosureLength = null, artworkUrl = null,
            season = null, episodeNumber = null, chaptersUrl = null, transcriptUrl = null,
        )

        private fun PodcastSettingsBackup.toEntity(podcastId: Long) = PodcastSettingsEntity(
            podcastId = podcastId,
            autoDownload = autoDownload,
            keepLatestCount = keepLatestCount,
            deletePlayed = deletePlayed,
            maxAgeDays = maxAgeDays,
            allowCellular = allowCellular,
            sortOrder = sortOrder?.let { enumOr<SortOrder>(it, SortOrder.NEWEST_FIRST) },
            playbackSpeed = playbackSpeed,
            skipIntroSec = skipIntroSec,
            skipOutroSec = skipOutroSec,
            notifyNewEpisodes = notifyNewEpisodes,
            autoAddToQueue = enumOr(autoAddToQueue, QueueAdd.OFF),
            backfillEnabled = backfillEnabled,
        )
    }
}
