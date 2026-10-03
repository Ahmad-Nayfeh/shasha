package com.ahmadnayfeh.shasha.youtube.newpipe

import com.google.common.truth.Truth.assertThat
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException

class NewPipeDownloaderTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun url(path: String = "/") = server.url(path).toString()

    @Test
    fun defaultUserAgentIsReplacedByTheExtractors() {
        val downloader = NewPipeDownloader(OkHttpClient())
        server.enqueue(MockResponse().setBody("a"))
        server.enqueue(MockResponse().setBody("b"))

        downloader.get(url())
        downloader.get(url(), mapOf("User-Agent" to listOf("com.google.android.youtube/21.03.36")))

        assertThat(server.takeRequest().getHeader("User-Agent")).isEqualTo(NewPipeDownloader.USER_AGENT)
        assertThat(server.takeRequest().getHeader("User-Agent")).isEqualTo("com.google.android.youtube/21.03.36")
    }

    @Test
    fun cookiesAreMergedNotOverwritten() {
        val downloader = NewPipeDownloader(OkHttpClient(), cookies = { "PREF=f2=8000000; a=1" })
        server.enqueue(MockResponse())

        downloader.get(url(), mapOf("Cookie" to listOf("SOCS=CAE; a=2")))

        assertThat(server.takeRequest().getHeader("Cookie")).isEqualTo("PREF=f2=8000000; a=2; SOCS=CAE")
    }

    @Test
    fun mergeCookiesEdgeCases() {
        assertThat(NewPipeDownloader.mergeCookies(null, null)).isNull()
        assertThat(NewPipeDownloader.mergeCookies("", " ; ")).isNull()
        assertThat(NewPipeDownloader.mergeCookies("a=1", null)).isEqualTo("a=1")
        assertThat(NewPipeDownloader.mergeCookies(null, "b=2;c=3")).isEqualTo("b=2; c=3")
        assertThat(NewPipeDownloader.mergeCookies("a=1; b=2", "b=3")).isEqualTo("a=1; b=3")
    }

    // ---- Restricted Mode: PREF=f2=8000000 on YouTube's hosts ----

    private fun cookieOf(downloader: NewPipeDownloader, extractorCookie: String? = null): String? {
        server.enqueue(MockResponse())
        downloader.get(url(), extractorCookie?.let { mapOf("Cookie" to listOf(it)) })
        return server.takeRequest().getHeader("Cookie")
    }

    @Test
    fun youtubeRequestsAskForRestrictedMode() {
        val downloader = NewPipeDownloader(OkHttpClient(), restrictedModeFor = { true })
        assertThat(cookieOf(downloader)).isEqualTo("PREF=f2=8000000")
    }

    @Test
    fun restrictedModeIsMergedWithOurCookiesAndTheExtractors() {
        val downloader = NewPipeDownloader(OkHttpClient(), cookies = { "a=1; b=2" }, restrictedModeFor = { true })
        assertThat(cookieOf(downloader, "SOCS=CAE; b=3")).isEqualTo("a=1; b=3; SOCS=CAE; PREF=f2=8000000")
    }

    @Test
    fun aPrefCookieTheExtractorSendsKeepsItsOtherFields() {
        val downloader = NewPipeDownloader(OkHttpClient(), restrictedModeFor = { true })
        assertThat(cookieOf(downloader, "SOCS=CAE; PREF=hl=en&tz=UTC")).isEqualTo("SOCS=CAE; PREF=hl=en&tz=UTC&f2=8000000")
        assertThat(cookieOf(downloader, "PREF=f2=8000000&hl=en")).isEqualTo("PREF=f2=8000000&hl=en")
        assertThat(cookieOf(downloader, "PREF=f6=400&f2=40000000&hl=en")).isEqualTo("PREF=f6=400&f2=48000000&hl=en")
    }

    @Test
    fun aPrefCookieOfOursIsCombinedToo() {
        val downloader = NewPipeDownloader(OkHttpClient(), cookies = { "PREF=hl=ar" }, restrictedModeFor = { true })
        assertThat(cookieOf(downloader)).isEqualTo("PREF=hl=ar&f2=8000000")
    }

    @Test
    fun otherHostsAreLeftAlone() {
        // The default asks YouTube's hosts only: this server is 127.0.0.1.
        val downloader = NewPipeDownloader(OkHttpClient())
        assertThat(cookieOf(downloader)).isNull()
        assertThat(cookieOf(downloader, "SOCS=CAE")).isEqualTo("SOCS=CAE")
    }

    @Test
    fun aHostTheFlagRefusesGetsNoRestrictedModeCookie() {
        val downloader = NewPipeDownloader(OkHttpClient(), restrictedModeFor = { false })
        assertThat(cookieOf(downloader, "SOCS=CAE")).isEqualTo("SOCS=CAE")
    }

    @Test
    fun youtubeHosts() {
        for (host in listOf("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com", "suggestqueries-clients6.youtube.com")) {
            assertThat(NewPipeDownloader.isYouTubeHost(host)).isTrue()
        }
        for (host in listOf("", "notyoutube.com", "youtube.com.evil.example", "evil-youtube.com", "youtu.be", "googlevideo.com", "rr1---sn-x.googlevideo.com", "127.0.0.1", "localhost")) {
            assertThat(NewPipeDownloader.isYouTubeHost(host)).isFalse()
        }
    }

    @Test
    fun withRestrictedModeTable() {
        val f = NewPipeDownloader.Companion::withRestrictedMode
        assertThat(f(null)).isEqualTo("PREF=f2=8000000")
        assertThat(f("")).isEqualTo("PREF=f2=8000000")
        assertThat(f(" ; ")).isEqualTo("PREF=f2=8000000")
        assertThat(f("SOCS=CAE")).isEqualTo("SOCS=CAE; PREF=f2=8000000")
        assertThat(f("a=1; b=2")).isEqualTo("a=1; b=2; PREF=f2=8000000")
        // An existing PREF: its fields stay, f2 gets the flag.
        assertThat(f("PREF=hl=en")).isEqualTo("PREF=hl=en&f2=8000000")
        assertThat(f("PREF=f2=8000000&hl=en")).isEqualTo("PREF=f2=8000000&hl=en")
        assertThat(f("PREF=hl=en&f2=8000000")).isEqualTo("PREF=hl=en&f2=8000000")
        assertThat(f("a=1; PREF=f2=400&tz=UTC; b=2")).isEqualTo("a=1; PREF=f2=8000400&tz=UTC; b=2")
        assertThat(f("PREF=f2=a")).isEqualTo("PREF=f2=800000a")
        assertThat(f("PREF=f2=FFFFFFF")).isEqualTo("PREF=f2=fffffff") // the flag is in there already
        // Not a number: replaced. Empty value, empty PREF: filled.
        assertThat(f("PREF=f2=zzz&hl=ar")).isEqualTo("PREF=f2=8000000&hl=ar")
        assertThat(f("PREF=f2=")).isEqualTo("PREF=f2=8000000")
        assertThat(f("PREF=")).isEqualTo("PREF=f2=8000000")
        assertThat(f("PREF")).isEqualTo("PREF=f2=8000000")
        // Only a cookie named PREF counts.
        assertThat(f("XPREF=hl=en; PREFX=1")).isEqualTo("XPREF=hl=en; PREFX=1; PREF=f2=8000000")
        // Whitespace and the order of the other cookies.
        assertThat(f("  a=1 ;PREF=hl=en;  b=2 ")).isEqualTo("a=1; PREF=hl=en&f2=8000000; b=2")
        // Applied twice, nothing changes.
        assertThat(f(f("PREF=hl=en; a=1"))).isEqualTo(f("PREF=hl=en; a=1"))
    }

    @Test
    fun http429BecomesReCaptcha() {
        val downloader = NewPipeDownloader(OkHttpClient())
        server.enqueue(MockResponse().setResponseCode(429))

        assertThrows(ReCaptchaException::class.java) { downloader.get(url()) }
    }

    @Test
    fun http403IsReturnedAndTracedPerOperation() {
        val downloader = NewPipeDownloader(OkHttpClient())
        server.enqueue(MockResponse().setResponseCode(403).setBody("Forbidden"))
        server.enqueue(MockResponse().setBody("ok"))

        downloader.beginTrace()
        val response = downloader.get(url())
        assertThat(response.responseCode()).isEqualTo(403)
        assertThat(downloader.blockedStatusSinceTrace()).isEqualTo(403)

        downloader.beginTrace()
        downloader.get(url())
        assertThat(downloader.blockedStatusSinceTrace()).isNull()
    }

    @Test
    fun postSendsBodyAndResponseKeepsHeadersAndFinalUrl() {
        val downloader = NewPipeDownloader(OkHttpClient())
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", url("/final")))
        server.enqueue(MockResponse().addHeader("X-Test", "1").setBody("{\"ok\":true}"))

        val response = downloader.execute(
            Request.newBuilder().post(url("/start"), "{\"q\":1}".toByteArray()).addHeader("Content-Type", "application/json").build(),
        )

        val recorded = server.takeRequest()
        assertThat(recorded.method).isEqualTo("POST")
        assertThat(recorded.body.readUtf8()).isEqualTo("{\"q\":1}")
        assertThat(recorded.getHeader("Content-Type")).startsWith("application/json")
        assertThat(response.responseBody()).isEqualTo("{\"ok\":true}")
        assertThat(response.getHeader("X-Test")).isEqualTo("1")
        assertThat(response.latestUrl()).endsWith("/final")
    }

    @Test
    fun postWithoutBodyStillWorks() {
        val downloader = NewPipeDownloader(OkHttpClient())
        server.enqueue(MockResponse())

        downloader.execute(Request.newBuilder().post(url(), null).build())

        assertThat(server.takeRequest().bodySize).isEqualTo(0)
    }

    @Test
    fun appInterceptorsAreNotApplied() {
        val appClient = OkHttpClient.Builder()
            .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("User-Agent", "ShashaApp").header("X-App", "1").build()) }
            .build()
        val downloader = NewPipeDownloader(appClient)
        server.enqueue(MockResponse())

        downloader.get(url())

        val recorded = server.takeRequest()
        assertThat(recorded.getHeader("User-Agent")).isEqualTo(NewPipeDownloader.USER_AGENT)
        assertThat(recorded.getHeader("X-App")).isNull()
    }
}
