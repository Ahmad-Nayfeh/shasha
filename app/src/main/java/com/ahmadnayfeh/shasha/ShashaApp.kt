package com.ahmadnayfeh.shasha

import android.app.Application
import android.content.Context
import androidx.work.WorkManager
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import okio.Path.Companion.toOkioPath

class ShashaApp : Application(), SingletonImageLoader.Factory {
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        // The two-hourly feed sync (SPEC §2); an existing schedule is kept. androidx.startup initialized
        // WorkManager before onCreate; only JVM unit tests start without it (Robolectric runs no providers).
        if (WorkManager.isInitialized()) container.feedSyncScheduler.ensurePeriodic()
    }

    /**
     * Thumbnails go through the shared OkHttp client. Fixed cache sizes suit a TV: 64 MB of decoded
     * pictures (a few screens of cards, so scrolling back does not decode again) and 150 MB on disk (the
     * same thumbnails come back in the feed, search and history, always under the same URL).
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { container.okHttpClient })) }
            .memoryCache { MemoryCache.Builder().maxSizeBytes(MEMORY_CACHE_BYTES).build() }
            .diskCache { DiskCache.Builder().directory(cacheDir.resolve("thumbnails").toOkioPath()).maxSizeBytes(DISK_CACHE_BYTES).build() }
            .crossfade(250)
            .build()

    private companion object {
        const val MEMORY_CACHE_BYTES = 64L * 1024 * 1024
        const val DISK_CACHE_BYTES = 150L * 1024 * 1024
    }
}

/** The process-wide [AppContainer]. */
val Context.appContainer: AppContainer
    get() = (applicationContext as ShashaApp).container
