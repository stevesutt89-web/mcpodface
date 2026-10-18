package com.steve.mcpodface.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {
    @Upsert
    suspend fun upsert(download: DownloadEntity)

    @Query("SELECT * FROM downloads WHERE episodeId = :episodeId")
    suspend fun get(episodeId: Long): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE episodeId = :episodeId")
    fun observe(episodeId: Long): Flow<DownloadEntity?>

    @Query("SELECT * FROM downloads")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("DELETE FROM downloads WHERE episodeId = :episodeId")
    suspend fun delete(episodeId: Long)
}
