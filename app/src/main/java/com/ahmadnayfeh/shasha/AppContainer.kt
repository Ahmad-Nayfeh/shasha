package com.ahmadnayfeh.shasha

import android.content.Context
import com.ahmadnayfeh.shasha.data.StreamRepository
import com.ahmadnayfeh.shasha.youtube.VideoSource
import com.ahmadnayfeh.shasha.youtube.YouTube
import com.ahmadnayfeh.shasha.youtube.playback.YouTubeMediaSourceFactory
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Manual dependency injection: one instance per process, owned by [ShashaApp].
 * Everything is created lazily so the first frame is not delayed by extractor initialisation.
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    /**
     * The one HTTP client of the app (thumbnails, and later SponsorBlock and GitHub). The YouTube module
     * reuses its connection pool but applies its own per-client headers.
     */
    val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
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

    val videoSource: VideoSource by lazy { YouTube.create(appContext, okHttpClient) }

    val mediaSourceFactory: YouTubeMediaSourceFactory by lazy { YouTubeMediaSourceFactory(appContext, okHttpClient) }

    val streams: StreamRepository by lazy { StreamRepository(videoSource) }

    private companion object {
        const val USER_AGENT = "Shasha/${BuildConfig.VERSION_NAME} (Android TV)"
    }
}
