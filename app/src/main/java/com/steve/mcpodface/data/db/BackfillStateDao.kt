package com.steve.mcpodface.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface BackfillStateDao {
    @Upsert
    suspend fun upsert(state: BackfillStateEntity)

    @Query("SELECT * FROM backfill_state WHERE podcastId = :podcastId")
    suspend fun get(podcastId: Long): BackfillStateEntity?

    @Query("SELECT * FROM backfill_state WHERE podcastId = :podcastId")
    fun observe(podcastId: Long): Flow<BackfillStateEntity?>

    @Query("SELECT * FROM backfill_state")
    suspend fun getAll(): List<BackfillStateEntity>

    /** Backfill cursors of subscribed podcasts, keyed by feed URL (PLAN.md §9). */
    @Query(
        """
        SELECT p.feedUrl AS feedUrl, b.status AS status, b.rssNextPageUrl AS rssNextPageUrl,
               b.rssPagingDone AS rssPagingDone, b.indexDone AS indexDone,
               b.indexCursor AS indexCursor, b.requestsMade AS requestsMade,
               b.episodesAdded AS episodesAdded, b.oldestKnownPubDate AS oldestKnownPubDate
        FROM backfill_state b
        INNER JOIN podcasts p ON p.id = b.podcastId
        WHERE p.isSubscribed = 1
        """,
    )
    suspend fun backupRows(): List<BackfillBackupRow>

    /** Every archive row, for the overall progress in Settings → Search & archive. */
    @Query("SELECT * FROM backfill_state")
    fun observeAll(): Flow<List<BackfillStateEntity>>
}

/** Backfill cursor for one podcast. Transient fields (retry timing, last error) are left out. */
data class BackfillBackupRow(
    val feedUrl: String,
    val status: BackfillStatus,
    val rssNextPageUrl: String?,
    val rssPagingDone: Boolean,
    val indexDone: Boolean,
    val indexCursor: String?,
    val requestsMade: Int,
    val episodesAdded: Int,
    val oldestKnownPubDate: Long?,
)
