package com.ahmadnayfeh.shasha.youtube.playback

import androidx.media3.common.C
import com.ahmadnayfeh.shasha.youtube.newpipe.NewPipeDownloader
import com.google.common.truth.Truth.assertThat
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Test
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper

/** How googlevideo URLs are rewritten (pure: no Android, no network). */
class GoogleVideoRequestsTest {

    private val base = "https://rr3---sn-4g5e6nsz.googlevideo.com/videoplayback?expire=1&itag=137&c=ANDROID&sig=AB%3D%3D"
    private var counter = 0L
    private val next = { counter++ }

    private fun plan(url: String = base, position: Long = 0, length: Long = C.LENGTH_UNSET.toLong(), quirks: GoogleVideoQuirks = GoogleVideoQuirks.DASH) =
        GoogleVideoRequests.plan(url, position, length, quirks, next)

    @Test
    fun dashRangeGoesIntoTheUrlWithRequestNumberAndPost() {
        val p = plan(position = 741, length = 556)
        assertThat(p.url).isEqualTo("$base&rn=0&range=741-1296")
        assertThat(p.rangeInUrl).isTrue()
        assertThat(p.headers).doesNotContainKey("Range")
        assertThat(p.method).isEqualTo("POST")
        assertThat(p.body).isEqualTo(byteArrayOf(0x78, 0))
        // The original query (including the signature) is untouched.
        assertThat(p.url).startsWith(base)
    }

    @Test
    fun requestNumberIncrementsPerRequest() {
        assertThat(plan(position = 0, length = 100).url).contains("&rn=0&")
        assertThat(plan(position = 100, length = 100).url).contains("&rn=1&")
    }

    @Test
    fun openEndedAndWholeResourceRanges() {
        assertThat(plan(position = 5000).url).isEqualTo("$base&rn=0&range=5000-")
        val whole = plan()
        assertThat(whole.url).isEqualTo("$base&rn=1")
        assertThat(whole.rangeInUrl).isFalse()
    }

    @Test
    fun existingRnOrRangeIsNotDuplicated() {
        val p = plan(url = "$base&rn=7&range=0-99", position = 0, length = 100)
        assertThat(p.url).isEqualTo("$base&rn=7&range=0-99")
        assertThat(counter).isEqualTo(0)
    }

    @Test
    fun progressiveUsesARangeHeader() {
        val p = plan(position = 1024, quirks = GoogleVideoQuirks.PROGRESSIVE)
        assertThat(p.url).isEqualTo("$base&rn=0")
        assertThat(p.headers["Range"]).isEqualTo("bytes=1024-")
        assertThat(p.rangeInUrl).isFalse()
        assertThat(p.method).isEqualTo("POST")
    }

    @Test
    fun liveLeavesTheUrlAloneAndUsesGet() {
        val segment = "https://rr19---sn-q.googlevideo.com/videoplayback/id/x.1/itag/269/source/yt_live_broadcast/sq/7894/file/seg.ts"
        val p = plan(url = segment, quirks = GoogleVideoQuirks.LIVE)
        assertThat(p.url).isEqualTo(segment)
        assertThat(p.method).isEqualTo("GET")
        assertThat(p.body).isNull()
    }

    @Test
    fun userAgentMatchesTheClientThatProducedTheUrl() {
        val localization = NewPipe.getPreferredLocalization()
        assertThat(plan().headers["User-Agent"]).isEqualTo(YoutubeParsingHelper.getAndroidUserAgent(localization))
        assertThat(plan(url = base.replace("c=ANDROID", "c=IOS")).headers["User-Agent"])
            .isEqualTo(YoutubeParsingHelper.getIosUserAgent(localization))
        assertThat(plan(url = base.replace("c=ANDROID", "c=VISIONOS")).headers["User-Agent"])
            .isEqualTo(YoutubeParsingHelper.getVisionOsUserAgent(localization))
        assertThat(plan().headers["User-Agent"]).startsWith("com.google.android.youtube/")
    }

    @Test
    fun webClientsGetABrowserUserAgentAndYoutubeOrigin() {
        val web = plan(url = base.replace("c=ANDROID", "c=WEB_EMBEDDED_PLAYER"))
        assertThat(web.headers["User-Agent"]).isEqualTo(NewPipeDownloader.USER_AGENT)
        assertThat(web.headers["Origin"]).isEqualTo("https://www.youtube.com")
        assertThat(web.headers["Referer"]).isEqualTo("https://www.youtube.com/")
        assertThat(plan().headers).doesNotContainKey("Origin")
    }

    @Test
    fun clientNameFromQueryOrPath() {
        assertThat(GoogleVideoRequests.clientName(base.toHttpUrl())).isEqualTo("ANDROID")
        assertThat(GoogleVideoRequests.clientName("https://x.googlevideo.com/videoplayback/id/1/c/ios/itag/5".toHttpUrl())).isEqualTo("IOS")
        assertThat(GoogleVideoRequests.clientName("https://x.googlevideo.com/videoplayback?itag=5".toHttpUrl())).isNull()
    }

    @Test
    fun otherUrlsArePlainGetsWithARangeHeader() {
        val subtitles = "https://www.youtube.com/api/timedtext?v=dQw4w9WgXcQ&lang=en&fmt=ttml"
        val p = plan(url = subtitles, position = 10, length = 20)
        assertThat(p.url).isEqualTo(subtitles)
        assertThat(p.method).isEqualTo("GET")
        assertThat(p.headers["Range"]).isEqualTo("bytes=10-29")
        assertThat(p.headers["User-Agent"]).isEqualTo(NewPipeDownloader.USER_AGENT)
        // A manifest host is not a media host.
        assertThat(plan(url = "https://manifest.googlevideo.com/api/manifest/hls_variant/x/file/index.m3u8").url)
            .doesNotContain("rn=")
        assertThat(counter).isEqualTo(0)
    }
}
