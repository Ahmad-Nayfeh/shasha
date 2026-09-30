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
