package com.steve.mcpodface.download

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.steve.mcpodface.core.di.IoDispatcher
import com.steve.mcpodface.data.db.DownloadDao
import com.steve.mcpodface.data.db.DownloadEntity
import com.steve.mcpodface.data.db.DownloadSource
import com.steve.mcpodface.data.db.DownloadState
import com.steve.mcpodface.data.db.EpisodeDao
import com.steve.mcpodface.data.db.FeedType
import com.steve.mcpodface.data.db.PodcastDao
import com.steve.mcpodface.data.db.PodcastSettingsDao
import com.steve.mcpodface.data.prefs.SettingsRepository
import com.steve.mcpodface.domain.policy.resolve
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one place that starts, cancels and deletes downloads (PLAN.md §6.4, §6.6, §6.7). Screens,
 * policies and workers all go through it so files, rows and tombstones stay consistent.
 */
@Singleton
class DownloadManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val downloadDao: DownloadDao,
    private val episodeDao: EpisodeDao,
    private val podcastDao: PodcastDao,
    private val podcastSettingsDao: PodcastSettingsDao,
    private val settingsRepository: SettingsRepository,
    private val paths: DownloadPaths,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    private val workManager get() = WorkManager.getInstance(context)

    /**
     * Queues a download. MANUAL clears the episode's tombstone (§6.4). [allowCellular] overrides
     * the podcast's effective setting (the "Download now on mobile data" choice, §6.6).
     */
    suspend fun enqueue(episodeId: Long, source: DownloadSource, allowCellular: Boolean? = null) {
        val episode = episodeDao.getById(episodeId) ?: return
        val existing = downloadDao.get(episodeId)
        if (existing?.state == DownloadState.COMPLETED) return
        if (source == DownloadSource.MANUAL) episodeDao.setAutoDownloadBlocked(episodeId, false)

        val global = settingsRepository.settings.first()
        val podcast = podcastDao.getById(episode.podcastId)
        val effective = resolve(global, podcastSettingsDao.get(episode.podcastId), podcast?.feedType ?: FeedType.EPISODIC)
        val cellular = allowCellular ?: effective.allowCellular

        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workDataOf(DownloadWorker.KEY_EPISODE_ID to episodeId))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (cellular) NetworkType.CONNECTED else NetworkType.UNMETERED)
                    .setRequiresCharging(source == DownloadSource.AUTO && global.downloadOnlyWhileCharging)
                    .setRequiresStorageNotLow(true)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()

        downloadDao.upsert(
            DownloadEntity(
                episodeId = episodeId,
                state = DownloadState.QUEUED,
                // A manual request upgrades an auto one; it never downgrades.
                source = if (existing?.source == DownloadSource.MANUAL) DownloadSource.MANUAL else source,
                bytesDownloaded = existing?.bytesDownloaded ?: 0,
                totalBytes = existing?.totalBytes ?: episode.enclosureLength,
                workId = request.id.toString(),
                attempts = 0,
            ),
        )
        // Re-queue replaces a failed/paused attempt; a running one is left alone.
        val policy = if (existing == null || existing.state == DownloadState.FAILED || existing.state == DownloadState.PAUSED_NETWORK) {
            ExistingWorkPolicy.REPLACE
        } else {
            ExistingWorkPolicy.KEEP
        }
        workManager.enqueueUniqueWork(DownloadWorker.uniqueName(episodeId), policy, request)
    }

    /** Stops a queued or running download and removes its partial file. Counts as a delete (§6.4). */
    suspend fun cancel(episodeId: Long) {
        workManager.cancelUniqueWork(DownloadWorker.uniqueName(episodeId))
        delete(episodeId)
    }

    /**
     * Deletes an episode's file(s) and download row and tombstones it so auto-download won't fetch
     * it again (§6.4). Used by the cleanup policy, the Downloads tab and "Remove download".
     */
    suspend fun delete(episodeId: Long, tombstone: Boolean = true) = withContext(io) {
        val row = downloadDao.get(episodeId)
        val episode = episodeDao.getById(episodeId)
        row?.filePath?.let { File(it).delete() }
        if (episode != null) {
            paths.finalFile(episode).delete()
            paths.partFile(episode).delete()
        }
        downloadDao.delete(episodeId)
        if (tombstone && episode != null) episodeDao.setAutoDownloadBlocked(episodeId, true)
    }

    suspend fun deleteAll(episodeIds: Collection<Long>) = episodeIds.forEach { delete(it) }

    /** Re-runs a FAILED download with the same source. */
    suspend fun retry(episodeId: Long) {
        val row = downloadDao.get(episodeId) ?: return
        enqueue(episodeId, row.source)
    }

    /**
     * True when a manual download should ask "Download now on mobile data / Wait for Wi-Fi"
     * (§6.6): cellular isn't allowed for this podcast and we're on a metered network right now.
     */
    suspend fun needsCellularPrompt(episodeId: Long): Boolean {
        val episode = episodeDao.getById(episodeId) ?: return false
        val global = settingsRepository.settings.first()
        val podcast = podcastDao.getById(episode.podcastId)
        val effective = resolve(global, podcastSettingsDao.get(episode.podcastId), podcast?.feedType ?: FeedType.EPISODIC)
        return !effective.allowCellular && isMetered()
    }

    private fun isMetered(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    companion object {
        const val TAG = "download"
    }
}
