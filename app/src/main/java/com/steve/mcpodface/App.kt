package com.steve.mcpodface

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import dagger.hilt.android.HiltAndroidApp
import okhttp3.OkHttpClient
import com.steve.mcpodface.refresh.WorkScheduler
import com.steve.mcpodface.widget.WidgetUpdater
import javax.inject.Inject

@HiltAndroidApp
class App : Application(), Configuration.Provider, SingletonImageLoader.Factory {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var okHttpClient: OkHttpClient
    @Inject lateinit var workScheduler: WorkScheduler
    @Inject lateinit var widgetUpdater: WidgetUpdater

    override fun onCreate() {
        super.onCreate()
        workScheduler.start()
        widgetUpdater.start()
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    /** Artwork loads through the shared OkHttp client (real User-Agent) with a 250 MB disk cache. */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { okHttpClient })) }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("artwork"))
                    .maxSizeBytes(250L * 1024 * 1024)
                    .build()
            }
            .build()
}
