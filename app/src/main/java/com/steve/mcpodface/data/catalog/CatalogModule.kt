package com.steve.mcpodface.data.catalog

import com.steve.mcpodface.BuildConfig
import com.steve.mcpodface.core.Clock
import com.steve.mcpodface.data.network.ITUNES_BASE_URL
import com.steve.mcpodface.data.network.ITunesApi
import com.steve.mcpodface.data.network.PODCAST_INDEX_BASE_URL
import com.steve.mcpodface.data.network.PodcastIndexApi
import com.steve.mcpodface.data.network.PodcastIndexAuthInterceptor
import com.steve.mcpodface.data.network.PodcastIndexCredentials
import com.steve.mcpodface.data.network.podcastIndexJson
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class CatalogModule {

    @Binds abstract fun catalogRepository(impl: DefaultCatalogRepository): CatalogRepository

    companion object {
        @Provides @Singleton
        fun podcastIndexCredentials(): PodcastIndexCredentials =
            PodcastIndexCredentials(BuildConfig.PODCASTINDEX_KEY, BuildConfig.PODCASTINDEX_SECRET)

        @Provides @Singleton
        fun podcastIndexApi(
            client: OkHttpClient,
            credentials: PodcastIndexCredentials,
            clock: Clock,
        ): PodcastIndexApi = Retrofit.Builder()
            .baseUrl(PODCAST_INDEX_BASE_URL)
            .client(client.newBuilder().addInterceptor(PodcastIndexAuthInterceptor(credentials, clock)).build())
            .addConverterFactory(podcastIndexJson.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(PodcastIndexApi::class.java)

        @Provides @Singleton
        fun iTunesApi(client: OkHttpClient): ITunesApi = Retrofit.Builder()
            .baseUrl(ITUNES_BASE_URL)
            .client(client.newBuilder().build())
            .addConverterFactory(podcastIndexJson.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(ITunesApi::class.java)
    }
}
