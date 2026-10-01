package com.ahmadnayfeh.shasha

import android.app.Application
import android.content.Context
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.WorkManager
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.ahmadnayfeh.shasha.lock.GuardService
import com.ahmadnayfeh.shasha.policy.spendingTime
import com.ahmadnayfeh.shasha.remote.RemoteService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.Path.Companion.toOkioPath

class ShashaApp : Application(), SingletonImageLoader.Factory {
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        // androidx.startup initialized WorkManager and the process lifecycle before onCreate; only JVM unit
        // tests start without them (Robolectric runs no providers), and they start what they test themselves.
        if (!WorkManager.isInitialized()) return
        // The two-hourly feed sync (SPEC §2); an existing schedule is kept.
        container.feedSyncScheduler.ensurePeriodic()
        startPolicy()
        // SPEC §4's guard, whenever its ADB permissions are granted (also when a job or the boot started us).
        GuardService.start(this)
        // SPEC §5's phone remote, when switched on (the default once the first start is done).
        RemoteService.startIfWanted(this)
    }

    /**
     * The current profile is read from disk off the main thread (the first screen waits for it only if it
     * comes first), then the session state and the usage tracker start: time counts while the app is
     * visible (at least STARTED, as the player plays) and a profile's screens are shown ([spendingTime]).
     */
    private fun startPolicy() {
        container.appScope.launch {
            withContext(Dispatchers.IO) { container.currentProfile }
            // The parent's settings (the warning, the lock) before the session's first answer.
            container.awaitSettings()
            container.startPolicy(spendingTime(ProcessLifecycleOwner.get().lifecycle, container.profileScreensShown))
        }
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
