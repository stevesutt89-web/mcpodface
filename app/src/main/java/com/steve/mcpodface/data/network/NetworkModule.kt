package com.steve.mcpodface.data.network

import com.steve.mcpodface.BuildConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/** Some hosts block OkHttp's default agent, so every request carries a real one (PLAN.md §5). */
const val USER_AGENT = "McPodface/${BuildConfig.VERSION_NAME} (Android podcast app)"

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    /** Shared base client. Derive per-use clients with newBuilder() so they share the pool. */
    @Provides @Singleton
    fun okHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .addInterceptor { chain ->
            val request = chain.request()
            if (request.header("User-Agent") != null) {
                chain.proceed(request)
            } else {
                chain.proceed(request.newBuilder().header("User-Agent", USER_AGENT).build())
            }
        }
        .build()
}
