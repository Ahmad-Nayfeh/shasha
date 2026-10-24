package com.ahmadnayfeh.shasha.mobile

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade

/** The phone app: Shasha («شاشة») for watching YouTube without ads, with downloads and a good player. */
open class MobileApp : Application(), SingletonImageLoader.Factory {
    val container: AppContainer by lazy { createContainer() }

    /** The screenshot tests build the app's objects over fake sources here; the app itself never overrides this. */
    protected open fun createContainer(): AppContainer = AppContainer(this)

    override fun onCreate() {
        super.onCreate()
        container.start()
        container.downloadNotifier.createChannels()
    }

    /**
     * The pictures (thumbnails, avatars): through the app's own HTTP client (its connections to YouTube's picture servers are
     * shared with everything else instead of a second pool), and faded in briefly instead of popping in while a list scrolls.
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { container.okHttp })) }
            .crossfade(IMAGE_FADE_MS)
            .build()

    private companion object {
        const val IMAGE_FADE_MS = 150
    }
}
