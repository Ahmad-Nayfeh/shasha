package com.ahmadnayfeh.shasha.youtube.newpipe

import com.google.common.truth.Truth.assertThat
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

class BrowseTapsTest {

    private val taps = BrowseTaps()
    private val browse = "https://www.youtube.com/youtubei/v1/browse?prettyPrint=false"

    @Test
    fun theTapSeesTheBrowseAnswer_andMayReshapeIt() {
        val tap = BrowseTap(reshape = { it.uppercase() })
        val seen = taps.watch(tap) { taps.answered(browse, "answer") }

        assertThat(seen).isEqualTo("ANSWER")
        assertThat(tap.lastAnswer).isEqualTo("answer") // as it arrived
    }

    @Test
    fun theLatestAnswerIsTheOneKept() {
        val tap = BrowseTap()
        taps.watch(tap) {
            taps.answered(browse, "first")
            taps.answered(browse, "second")
        }
        assertThat(tap.lastAnswer).isEqualTo("second")
    }

    @Test
    fun anythingButBrowseIsLeftAlone() {
        val tap = BrowseTap(reshape = { "reshaped" })
        taps.watch(tap) {
            assertThat(taps.answered("https://www.youtube.com/youtubei/v1/next?prettyPrint=false", "next")).isEqualTo("next")
            assertThat(taps.answered("https://www.youtube.com/sw.js", "js")).isEqualTo("js")
        }
        assertThat(tap.lastAnswer).isNull()
    }

    @Test
    fun withoutATapNothingChanges() {
        assertThat(taps.answered(browse, "answer")).isEqualTo("answer")
    }

    @Test
    fun theTapEndsWithTheCall_alsoWhenItFails() {
        val tap = BrowseTap(reshape = { "reshaped" })
        assertThrows(IllegalStateException::class.java) { taps.watch(tap) { error("boom") } }
        assertThat(taps.answered(browse, "answer")).isEqualTo("answer")
        assertThat(tap.lastAnswer).isNull()
    }

    @Test
    fun aTapWatchesItsOwnThreadOnly() {
        val tap = BrowseTap(reshape = { "reshaped" })
        var otherThread: String? = null
        taps.watch(tap) {
            val done = CountDownLatch(1)
            thread {
                otherThread = taps.answered(browse, "answer")
                done.countDown()
            }
            done.await()
            assertThat(taps.answered(browse, "answer")).isEqualTo("reshaped")
        }
        assertThat(otherThread).isEqualTo("answer")
    }

    // ---- through the HTTP layer ----------------------------------------------------------------------------------------

    private lateinit var server: MockWebServer

    @Before
    fun startServer() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun stopServer() = server.shutdown()

    @Test
    fun theDownloaderHandsItsBrowseAnswersToTheWatchingTap() {
        val downloader = NewPipeDownloader(OkHttpClient())
        server.enqueue(MockResponse().setBody("""{"browse":1}"""))
        server.enqueue(MockResponse().setBody("""{"other":1}"""))
        server.enqueue(MockResponse().setBody("""{"browse":2}"""))

        val tap = BrowseTap(reshape = { "reshaped $it" })
        val answers = downloader.browseTaps.watch(tap) {
            listOf(
                downloader.get(server.url("/youtubei/v1/browse?prettyPrint=false").toString()).responseBody(),
                downloader.get(server.url("/youtubei/v1/next").toString()).responseBody(),
            )
        }
        assertThat(answers).containsExactly("""reshaped {"browse":1}""", """{"other":1}""").inOrder()
        assertThat(tap.lastAnswer).isEqualTo("""{"browse":1}""")

        // Nobody watching: the answer is the extractor's as it came.
        assertThat(downloader.get(server.url("/youtubei/v1/browse").toString()).responseBody()).isEqualTo("""{"browse":2}""")
    }
}
