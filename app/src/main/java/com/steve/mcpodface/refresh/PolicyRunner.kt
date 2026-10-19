package com.steve.mcpodface.refresh

import com.steve.mcpodface.core.Clock
import com.steve.mcpodface.data.db.AppDatabase
import com.steve.mcpodface.data.db.DownloadSource
import com.steve.mcpodface.data.db.PodcastEntity
import com.steve.mcpodface.data.prefs.GlobalSettings
import com.steve.mcpodface.data.prefs.SettingsRepository
import com.steve.mcpodface.domain.policy.CleanupPolicy
import com.steve.mcpodface.domain.policy.Deletion
import com.steve.mcpodface.domain.policy.DownloadPolicy
import com.steve.mcpodface.domain.policy.EffectiveSettings
import com.steve.mcpodface.domain.policy.PolicyDownload
import com.steve.mcpodface.domain.policy.PolicyEpisode
import com.steve.mcpodface.domain.policy.resolve
import com.steve.mcpodface.download.DownloadManager
import com.steve.mcpodface.playback.PlayerController
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Applies the pure policies in domain/policy to real data (PLAN.md §6.5): loads a podcast's
 * episodes, downloads and queue into [PolicyEpisode]s, then starts or deletes downloads through
 * [DownloadManager] (which also sets the §6.4 tombstone on delete).
 */
@Singleton
class PolicyRunner @Inject constructor(
    private val db: AppDatabase,
    private val settingsRepository: SettingsRepository,
    private val downloadManager: DownloadManager,
    private val playerController: PlayerController,
    private val clock: Clock,
) {
    /** Enqueues the AUTO downloads this podcast should have. Returns how many were queued. */
    suspend fun runDownloadPolicy(podcastId: Long): Int {
        val podcast = db.podcastDao().getById(podcastId)?.takeIf { it.isSubscribed } ?: return 0
        val settings = effective(podcast, settingsRepository.settings.first())
        val ids = DownloadPolicy.select(policyEpisodes(podcastId), settings, clock.now())
        ids.forEach { downloadManager.enqueue(it, DownloadSource.AUTO) }
        return ids.size
    }

    /** One cleanup pass over every podcast that has downloads. Returns the deletions made. */
    suspend fun runCleanup(): List<Deletion> {
        val global = settingsRepository.settings.first()
        val playing = playerController.nowPlaying.value?.episodeId
        val now = clock.now()
        val deletions = mutableListOf<Deletion>()
        for (podcastId in db.downloadDao().podcastIdsWithDownloads()) {
            val podcast = db.podcastDao().getById(podcastId) ?: continue
            deletions += CleanupPolicy.select(policyEpisodes(podcastId), effective(podcast, global), now, playing)
        }
        deletions.forEach { downloadManager.delete(it.episodeId) }
        return deletions
    }

    /**
     * What a cleanup pass *would* delete if [transform] were applied to the global settings, for
     * the "This will remove 7 episodes (412 MB)" preview (§6.5). Returns (episode count, bytes).
     */
    suspend fun previewCleanup(transform: (GlobalSettings) -> GlobalSettings): Pair<Int, Long> {
        val global = transform(settingsRepository.settings.first())
        val playing = playerController.nowPlaying.value?.episodeId
        var count = 0
        var bytes = 0L
        for (podcastId in db.downloadDao().podcastIdsWithDownloads()) {
            val podcast = db.podcastDao().getById(podcastId) ?: continue
            val episodes = policyEpisodes(podcastId)
            val deleting = CleanupPolicy.select(episodes, effective(podcast, global), clock.now(), playing)
                .mapTo(HashSet()) { it.episodeId }
            count += deleting.size
            bytes += db.downloadDao().getCompleted().filter { it.episodeId in deleting }
                .sumOf { it.totalBytes ?: it.bytesDownloaded }
        }
        return count to bytes
    }

    private suspend fun effective(podcast: PodcastEntity, global: GlobalSettings): EffectiveSettings =
        resolve(global, db.podcastSettingsDao().get(podcast.id), podcast.feedType)

    private suspend fun policyEpisodes(podcastId: Long): List<PolicyEpisode> {
        val downloads = db.downloadDao().getForPodcast(podcastId).associateBy { it.episodeId }
        val queued = db.queueDao().getAll().mapTo(HashSet()) { it.episodeId }
        return db.episodeDao().getForPodcast(podcastId).map { e ->
            PolicyEpisode(
                id = e.id,
                pubDate = e.pubDate,
                playState = e.playState,
                playedAt = e.playedAt,
                isStarred = e.isStarred,
                autoDownloadBlocked = e.autoDownloadBlocked,
                inQueue = e.id in queued,
                download = downloads[e.id]?.let { PolicyDownload(it.state, it.source, it.downloadedAt) },
            )
        }
    }
}
