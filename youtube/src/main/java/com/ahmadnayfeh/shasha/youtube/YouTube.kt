package com.ahmadnayfeh.shasha.youtube

import android.content.Context
import com.ahmadnayfeh.shasha.youtube.newpipe.NewPipeVideoSource
import okhttp3.OkHttpClient

/** Entry point for the app. Create one [VideoSource] per process and share it. */
object YouTube {
    /**
     * @param okHttpClient the app's shared client. The extractor reuses its connection pool but not its
     * interceptors (those could override the per-client User-Agents YouTube requires).
     */
    @Suppress("UNUSED_PARAMETER") // kept in the contract for future caches; nothing needs it yet
    fun create(context: Context, okHttpClient: OkHttpClient): VideoSource = NewPipeVideoSource(okHttpClient)
}
