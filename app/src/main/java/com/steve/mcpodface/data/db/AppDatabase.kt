package com.steve.mcpodface.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Schemas are exported to app/schemas. Every version bump needs a real Migration (or AutoMigration)
 * registered in DatabaseModule. Never use destructive migration (PLAN.md §4).
 */
@Database(
    version = 1,
    exportSchema = true,
    entities = [
        PodcastEntity::class,
        PodcastSettingsEntity::class,
        EpisodeEntity::class,
        EpisodeFts::class,
        DownloadEntity::class,
        BackfillStateEntity::class,
        QueueItemEntity::class,
        ListeningSessionEntity::class,
        RecentSearchEntity::class,
        CatalogCacheEntity::class,
    ],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun podcastDao(): PodcastDao
    abstract fun podcastSettingsDao(): PodcastSettingsDao
    abstract fun episodeDao(): EpisodeDao
    abstract fun downloadDao(): DownloadDao
    abstract fun backfillStateDao(): BackfillStateDao
    abstract fun queueDao(): QueueDao
    abstract fun listeningSessionDao(): ListeningSessionDao
    abstract fun recentSearchDao(): RecentSearchDao
    abstract fun catalogCacheDao(): CatalogCacheDao
    abstract fun searchDao(): SearchDao

    companion object {
        const val NAME = "mcpodface.db"
    }
}
