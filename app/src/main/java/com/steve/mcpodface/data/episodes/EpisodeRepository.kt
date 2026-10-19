package com.steve.mcpodface.data.episodes

import com.steve.mcpodface.core.Clock
import com.steve.mcpodface.data.db.EpisodeDao
import com.steve.mcpodface.data.db.EpisodeEntity
import com.steve.mcpodface.data.db.EpisodeListItem
import com.steve.mcpodface.data.db.PodcastDao
import com.steve.mcpodface.data.db.PodcastEntity
import com.steve.mcpodface.data.db.PlayState
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/** User actions on episodes and the list streams screens observe. */
@Singleton
class EpisodeRepository @Inject constructor(
    private val episodeDao: EpisodeDao,
    private val podcastDao: PodcastDao,
    private val clock: Clock,
) {
    /** Latest tab: last 30 days, at most 300 rows (PLAN.md §8.2). */
    fun observeLatest(unplayedOnly: Boolean, downloadedOnly: Boolean): Flow<List<EpisodeListItem>> =
        episodeDao.observeLatest(
            since = clock.now() - LATEST_WINDOW_MS,
            limit = LATEST_MAX_ROWS,
            unplayedOnly = unplayedOnly,
            downloadedOnly = downloadedOnly,
        )

    fun observeForPodcast(podcastId: Long, newestFirst: Boolean, hidePlayed: Boolean): Flow<List<EpisodeListItem>> =
        episodeDao.observeForPodcast(podcastId, newestFirst, hidePlayed)

    fun observeStarred(): Flow<List<EpisodeListItem>> = episodeDao.observeStarred()

    fun observeEpisode(id: Long): Flow<EpisodeEntity?> = episodeDao.observeById(id)

    fun observePodcast(id: Long): Flow<PodcastEntity?> = podcastDao.observeById(id)

    /** Mark played sets playedAt now (which starts the auto-delete delay); unplayed clears it (§6.0). */
    suspend fun setPlayed(episodeId: Long, played: Boolean) {
        if (played) {
            episodeDao.setPlayState(episodeId, PlayState.PLAYED, playedAt = clock.now(), positionMs = null)
        } else {
            episodeDao.setPlayState(episodeId, PlayState.UNPLAYED, playedAt = null, positionMs = 0)
        }
        // TODO(5.2): trigger the cleanup policy for this podcast.
    }

    suspend fun setStarred(episodeId: Long, starred: Boolean) = episodeDao.setStarred(episodeId, starred)

    suspend fun markAllPlayed(podcastId: Long) = episodeDao.markAllPlayed(podcastId, clock.now())

    companion object {
        const val LATEST_WINDOW_MS = 30L * 24 * 60 * 60 * 1000
        const val LATEST_MAX_ROWS = 300
    }
}
