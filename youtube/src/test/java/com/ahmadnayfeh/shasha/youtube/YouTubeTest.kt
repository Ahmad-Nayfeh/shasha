package com.ahmadnayfeh.shasha.youtube

import com.ahmadnayfeh.shasha.youtube.playback.ROBOLECTRIC_SDK
import com.ahmadnayfeh.shasha.youtube.safety.SafeVideoSource
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The source both apps get from [YouTube.create] is the filtered one. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class YouTubeTest {

    @Test
    fun theAppsSourceIsTheFilteredOne_soNoScreenCanReachTheExtractorDirectly() {
        val source = YouTube.create(RuntimeEnvironment.getApplication(), OkHttpClient())
        assertThat(source).isInstanceOf(SafeVideoSource::class.java)
        // Offline: an explicit query is answered without a request.
        runBlocking {
            assertThat(source.search("Sexy twerking").items).isEmpty()
            assertThat(source.search("Sexy twerking").next).isNull()
            assertThat(source.suggestions("porn", "en")).isEmpty()
        }
    }
}
