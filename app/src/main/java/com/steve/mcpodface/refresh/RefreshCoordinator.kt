package com.steve.mcpodface.refresh

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.steve.mcpodface.MainActivity
import com.steve.mcpodface.R
import com.steve.mcpodface.data.db.AppDatabase
import com.steve.mcpodface.data.db.QueueAdd
import com.steve.mcpodface.data.feed.FeedRepository
import com.steve.mcpodface.data.feed.RefreshResult
import com.steve.mcpodface.data.prefs.SettingsRepository
import com.steve.mcpodface.data.queue.QueueRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Everything that follows a feed refresh (PLAN.md §5, §6.5): auto-download per podcast, one global
 * cleanup pass, auto-add new episodes to Up Next, and one grouped "New episodes" notification.
 * Used by the periodic [RefreshWorker] and by pull-to-refresh (via FeedRepository's results).
 */
@Singleton
class RefreshCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val feedRepository: FeedRepository,
    private val policyRunner: PolicyRunner,
    private val queue: QueueRepository,
    private val settingsRepository: SettingsRepository,
    private val db: AppDatabase,
) {
    private val mutex = Mutex()

    /** Refreshes every subscription and then applies policies. For the background worker. */
    suspend fun refreshAllAndApply() {
        val results = feedRepository.refreshAll(announce = false)
        afterRefresh(results)
    }

    /** Applies policies, queueing and notifications for a finished refresh. */
    suspend fun afterRefresh(results: List<RefreshResult>) = mutex.withLock {
        val updated = results.filterIsInstance<RefreshResult.Updated>()
        for (r in results) policyRunner.runDownloadPolicy(r.podcastId)
        policyRunner.runCleanup()

        val newEpisodes = updated.flatMap { r -> r.newEpisodeIds.map { r.podcastId to it } }
        if (newEpisodes.isEmpty()) return@withLock
        for ((podcastId, episodeId) in newEpisodes) {
            when (db.podcastSettingsDao().get(podcastId)?.autoAddToQueue) {
                QueueAdd.TOP -> queue.addFirst(episodeId)
                QueueAdd.BOTTOM -> queue.addLast(episodeId)
                else -> Unit
            }
        }
        notifyNewEpisodes(newEpisodes)
    }

    private suspend fun notifyNewEpisodes(newEpisodes: List<Pair<Long, Long>>) {
        if (!settingsRepository.settings.first().notifyNewEpisodes) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val lines = newEpisodes.mapNotNull { (podcastId, episodeId) ->
            if (db.podcastSettingsDao().get(podcastId)?.notifyNewEpisodes == false) return@mapNotNull null
            val podcast = db.podcastDao().getById(podcastId) ?: return@mapNotNull null
            val episode = db.episodeDao().getById(episodeId) ?: return@mapNotNull null
            podcast.title to episode.title
        }
        if (lines.isEmpty()) return

        ensureChannel()
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = if (lines.size == 1) lines[0].first else "${lines.size} new episodes"
        val text = if (lines.size == 1) lines[0].second else lines.map { it.first }.distinct().joinToString(", ")
        val style = NotificationCompat.InboxStyle().also { s ->
            lines.take(6).forEach { (podcast, episode) -> s.addLine("$podcast · $episode") }
            if (lines.size > 6) s.setSummaryText("+${lines.size - 6} more")
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(style)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification) }
    }

    private fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "New episodes", NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
    }

    companion object {
        private const val CHANNEL_ID = "new_episodes"
        private const val NOTIFICATION_ID = 3001
    }
}
