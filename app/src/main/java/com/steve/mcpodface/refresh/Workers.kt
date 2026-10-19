package com.steve.mcpodface.refresh

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.steve.mcpodface.backfill.BackfillWorker
import com.steve.mcpodface.core.di.ApplicationScope
import com.steve.mcpodface.data.db.AppDatabase
import com.steve.mcpodface.data.db.DownloadState
import com.steve.mcpodface.data.feed.FeedRepository
import com.steve.mcpodface.data.playlist.PlaylistRepository
import com.steve.mcpodface.data.prefs.SettingsRepository
import com.steve.mcpodface.download.DownloadPaths
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Periodic feed refresh followed by policies and notifications (PLAN.md §5). */
@HiltWorker
class RefreshWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val coordinator: RefreshCoordinator,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        coordinator.refreshAllAndApply()
        return Result.success()
    }
}

/**
 * Every 6 hours: the cleanup policy (so "delete 24h after played" holds even with refresh set to
 * manual) plus orphan removal: files with no download row, and rows whose file is gone (§6.5).
 */
@HiltWorker
class CleanupWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val policyRunner: PolicyRunner,
    private val orphans: OrphanCleaner,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        orphans.clean()
        policyRunner.runCleanup()
        return Result.success()
    }
}

class OrphanCleaner @Inject constructor(
    private val db: AppDatabase,
    private val paths: DownloadPaths,
) {
    /** Returns (files deleted, rows removed). */
    suspend fun clean(): Pair<Int, Int> {
        val rows = db.downloadDao().getAll()
        var rowsRemoved = 0
        for (row in rows) {
            if (row.state == DownloadState.COMPLETED && (row.filePath == null || !File(row.filePath).exists())) {
                db.downloadDao().delete(row.episodeId)
                db.episodeDao().setAutoDownloadBlocked(row.episodeId, true)
                rowsRemoved++
            }
        }
        // Files that belong to no row. Partial files of rows still in flight are kept.
        val live = rows.mapNotNullTo(HashSet()) { it.episodeId }
        var filesDeleted = 0
        paths.root.listFiles()?.forEach { podcastDir ->
            podcastDir.listFiles()?.forEach { file ->
                val id = file.name.substringBefore('.').toLongOrNull()
                if (id == null || id !in live) {
                    if (file.delete()) filesDeleted++
                }
            }
        }
        return filesDeleted to rowsRemoved
    }
}

/**
 * Keeps the periodic work in line with Settings: refresh every N hours (or not at all when set to
 * manual) and cleanup every 6 hours. Started once from App.onCreate.
 */
@Singleton
class WorkScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val feedRepository: FeedRepository,
    private val coordinator: RefreshCoordinator,
    private val playlists: PlaylistRepository,
    @ApplicationScope private val scope: CoroutineScope,
) {
    fun start() {
        val workManager = WorkManager.getInstance(context)
        // One-time move of retired Listen Later items into Up Next; cheap once there is nothing to move.
        scope.launch { playlists.moveListenLaterToUpNext() }
        workManager.enqueueUniquePeriodicWork(
            CLEANUP_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<CleanupWorker>(6, TimeUnit.HOURS).build(),
        )
        scope.launch {
            settingsRepository.settings.map { it.refreshIntervalHours }.distinctUntilChanged().collect { hours ->
                if (hours <= 0) {
                    workManager.cancelUniqueWork(REFRESH_WORK)
                } else {
                    workManager.enqueueUniquePeriodicWork(
                        REFRESH_WORK,
                        ExistingPeriodicWorkPolicy.UPDATE,
                        PeriodicWorkRequestBuilder<RefreshWorker>(hours.toLong(), TimeUnit.HOURS)
                            .setConstraints(
                                Constraints.Builder()
                                    .setRequiredNetworkType(NetworkType.CONNECTED)
                                    .setRequiresBatteryNotLow(true)
                                    .build(),
                            )
                            .build(),
                    )
                }
            }
        }
        scope.launch {
            settingsRepository.settings
                .map { Triple(it.backfillEnabled, it.backfillWifiOnly, it.backfillChargingOnly) }
                .distinctUntilChanged()
                .collect { BackfillWorker.schedule(workManager, settingsRepository.settings.first()) }
        }
        // Pull-to-refresh and subscribe refresh through FeedRepository; apply policies after them too.
        scope.launch {
            feedRepository.refreshes.collect { results -> coordinator.afterRefresh(results) }
        }
    }

    companion object {
        const val REFRESH_WORK = "refresh"
        const val CLEANUP_WORK = "cleanup"
    }
}
