package com.ahmadnayfeh.shasha.youtube

import android.content.Context
import com.ahmadnayfeh.shasha.youtube.newpipe.NewPipeVideoSource
import com.ahmadnayfeh.shasha.youtube.safety.ExplicitFilter
import com.ahmadnayfeh.shasha.youtube.safety.SafeVideoSource
import okhttp3.OkHttpClient

/** Entry point for the app. Create one [VideoSource] per process and share it. */
object YouTube {
    /**
     * The source of both apps, with the built-in explicit-content filter in front of the extractor ([SafeVideoSource]): the
     * only way the apps get a [VideoSource], so nothing sexual, indecent or suggestive reaches any screen of either, whatever
     * the profile. The filter's word lists are built on a background thread now, not on the first search.
     *
     * @param okHttpClient the app's shared client. The extractor reuses its connection pool but not its
     * interceptors (those could override the per-client User-Agents YouTube requires).
     */
    @Suppress("UNUSED_PARAMETER") // kept in the contract for future caches; nothing needs it yet
    fun create(context: Context, okHttpClient: OkHttpClient): VideoSource {
        ExplicitFilter.warmUp()
        return SafeVideoSource(NewPipeVideoSource(okHttpClient))
    }
}
