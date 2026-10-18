package com.steve.mcpodface.data.db

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides @Singleton
    fun database(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.NAME)
            // Register migrations here: .addMigrations(MIGRATION_1_2)
            .build()

    @Provides fun podcastDao(db: AppDatabase) = db.podcastDao()
    @Provides fun podcastSettingsDao(db: AppDatabase) = db.podcastSettingsDao()
    @Provides fun episodeDao(db: AppDatabase) = db.episodeDao()
    @Provides fun downloadDao(db: AppDatabase) = db.downloadDao()
    @Provides fun backfillStateDao(db: AppDatabase) = db.backfillStateDao()
    @Provides fun queueDao(db: AppDatabase) = db.queueDao()
    @Provides fun listeningSessionDao(db: AppDatabase) = db.listeningSessionDao()
    @Provides fun recentSearchDao(db: AppDatabase) = db.recentSearchDao()
    @Provides fun catalogCacheDao(db: AppDatabase) = db.catalogCacheDao()
}
