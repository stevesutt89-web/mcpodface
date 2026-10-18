package com.steve.mcpodface.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface CatalogCacheDao {
    @Upsert
    suspend fun upsert(entry: CatalogCacheEntity)

    @Query("SELECT * FROM catalog_cache WHERE [key] = :key")
    suspend fun get(key: String): CatalogCacheEntity?

    @Query("DELETE FROM catalog_cache WHERE fetchedAt < :before")
    suspend fun deleteOlderThan(before: Long)
}
