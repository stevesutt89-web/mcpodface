package com.steve.mcpodface.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface RecentSearchDao {
    @Upsert
    suspend fun upsert(search: RecentSearchEntity)

    @Query("SELECT * FROM recent_searches ORDER BY lastUsedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 10): Flow<List<RecentSearchEntity>>

    @Query("DELETE FROM recent_searches")
    suspend fun clear()

    /** Keeps only the [keep] most recent searches. */
    @Query("DELETE FROM recent_searches WHERE `query` NOT IN (SELECT `query` FROM recent_searches ORDER BY lastUsedAt DESC LIMIT :keep)")
    suspend fun trimTo(keep: Int)
}
