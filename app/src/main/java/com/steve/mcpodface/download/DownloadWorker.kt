package com.steve.mcpodface.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.steve.mcpodface.data.db.DownloadDao
import com.steve.mcpodface.data.db.DownloadState
import com.steve.mcpodface.data.db.EpisodeDao
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * One episode download, uniquely named `dl-<episodeId>` (PLAN.md §6.7). Runs in the foreground
 * with a progress notification, at most [MAX_PARALLEL] at a time. Network rules come from the
 * work constraints set by [DownloadManager]: if Wi-Fi drops, WorkManager stops this worker and
 * reruns it later, and the download resumes from its `.part` file.
 */
@HiltWorker
class DownloadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val downloader: EpisodeDownloader,
    private val downloadDao: DownloadDao,
    private val episodeDao: EpisodeDao,
) : CoroutineWorker(context, params) {

    private val episodeId get() = inputData.getLong(KEY_EPISODE_ID, -1)

    override suspend fun doWork(): Result {
        if (episodeId < 0) return Result.failure()
        val title = episodeDao.getById(episodeId)?.title ?: "Episode"
        // Foreground keeps long downloads alive; it can be refused when we start from the
        // background on Android 12+, in which case we still try within the normal worker window.
        runCatching { setForeground(foregroundInfo(title, 0, null)) }

        return try {
            gate.withPermit { runDownload(title) }
        } catch (e: CancellationException) {
            // Stopped: usually the network constraint went away. Keep the .part for resuming.
            withContext(NonCancellable) {
                downloadDao.get(episodeId)?.let {
                    downloadDao.updateState(episodeId, DownloadState.PAUSED_NETWORK, "Waiting for network", it.attempts)
                }
            }
            throw e
        }
    }

    private suspend fun runDownload(title: String): Result {
        val outcome = downloader.download(episodeId) { bytes, total ->
            setProgressAsync(workDataOf(KEY_BYTES to bytes, KEY_TOTAL to (total ?: -1L)))
            runCatching { setForegroundAsync(foregroundInfo(title, bytes, total)) }
        }
        val attempts = runAttemptCount + 1
        return when (outcome) {
            is DownloadOutcome.Completed -> Result.success()
            is DownloadOutcome.Failed -> {
                downloadDao.updateState(episodeId, DownloadState.FAILED, outcome.message, attempts)
                Result.failure()
            }
            is DownloadOutcome.Retryable -> if (attempts >= MAX_ATTEMPTS) {
                downloadDao.updateState(episodeId, DownloadState.FAILED, outcome.message, attempts)
                Result.failure()
            } else {
                downloadDao.updateState(episodeId, DownloadState.QUEUED, outcome.message, attempts)
                Result.retry()
            }
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo("Episode", 0, null)

    private fun foregroundInfo(title: String, bytes: Long, total: Long?): ForegroundInfo {
        ensureChannel(applicationContext)
        val percent = if (total != null && total > 0) ((bytes * 100) / total).toInt() else 0
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading")
            .setContentText(title)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
            .setProgress(100, percent, total == null)
            .build()
        return ForegroundInfo(
            NOTIFICATION_BASE + (episodeId % 1000).toInt(),
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    companion object {
        const val KEY_EPISODE_ID = "episodeId"
        const val KEY_BYTES = "bytes"
        const val KEY_TOTAL = "total"
        const val MAX_ATTEMPTS = 5
        const val MAX_PARALLEL = 2
        private const val CHANNEL_ID = "downloads"
        private const val NOTIFICATION_BASE = 4000

        /** Global download concurrency (PLAN.md §6.7). */
        private val gate = Semaphore(MAX_PARALLEL)

        fun uniqueName(episodeId: Long) = "dl-$episodeId"

        fun ensureChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW),
                )
            }
        }
    }
}
