package com.ahmadnayfeh.shasha.sponsorblock

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * The client against a small HTTP server on 127.0.0.1: what the server is asked, and what each kind of answer
 * becomes (404 = no segments, anything else = a failure; a server that never answers is given up on).
 * Robolectric: the answer is read on a background-priority thread (android.os.Process).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class SponsorBlockClientTest {
    private val servers = mutableListOf<TinyServer>()

    @After
    fun tearDown() = servers.forEach { it.close() }

    private fun server(answer: (requestLine: String) -> String?) = TinyServer(answer).also { servers += it }

    private fun client(server: TinyServer, callTimeoutMs: Long = SponsorBlockClient.CALL_TIMEOUT_MS) =
        SponsorBlockClient(OkHttpClient(), "http://127.0.0.1:${server.port}/".toHttpUrl(), callTimeoutMs)

    @Test
    fun theServerIsAskedForThePrefixOnly_andItsAnswerIsFilteredHere() = runBlocking<Unit> {
        val server = server { http(200, ANSWER) }
        val segments = client(server).fetch("lC3h3Lm3q54")

        assertThat(segments.map { Triple(it.category, it.startMs, it.endMs) })
            .containsExactly(Triple(SponsorCategory.SPONSOR, 119_513L, 162_837L))
        val request = server.requests.single()
        assertThat(request).startsWith("GET /api/skipSegments/a16c?categories=")
        assertThat(request).contains("actionTypes=%5B%22skip%22%2C%22mute%22%5D")
        assertThat(request).doesNotContain("lC3h3Lm3q54")
    }

    @Test
    fun notFound_meansNoSegments() = runBlocking<Unit> {
        val server = server { http(404, "Not Found") }
        assertThat(client(server).fetch("lC3h3Lm3q54")).isEmpty()
    }

    @Test
    fun otherAnswers_areFailures() = runBlocking<Unit> {
        for (code in listOf(400, 429, 500, 503)) {
            val server = server { http(code, "[]") }
            assertFails("HTTP $code") { client(server).fetch("lC3h3Lm3q54") }
        }
        val garbage = server { http(200, "<html>maintenance</html>") }
        assertFails("not JSON") { client(garbage).fetch("lC3h3Lm3q54") }
    }

    @Test
    fun aServerThatNeverAnswers_isGivenUpOnInTime() = runBlocking<Unit> {
        val server = server { null }
        val started = System.nanoTime()
        assertFails("timeout") { client(server, callTimeoutMs = 600).fetch("lC3h3Lm3q54") }
        assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(5_000)
    }

    @Test
    fun anUnreachableServer_isAFailure() = runBlocking<Unit> {
        val closed = server { http(200, "[]") }.also { it.close() }
        assertFails("connection refused") { client(closed).fetch("lC3h3Lm3q54") }
    }

    @Test
    fun leavingThePlayer_cancelsTheRequest_withoutWaitingForItsTimeout() = runBlocking<Unit> {
        val server = server { null }
        val fetching = async(Dispatchers.Default) { client(server).fetch("lC3h3Lm3q54") }
        withTimeout(5_000) { while (server.requests.isEmpty()) delay(20) }
        val started = System.nanoTime()
        fetching.cancel()
        try {
            fetching.await()
            fail("cancelled")
        } catch (expected: CancellationException) {
            // The 10-second call timeout was not waited for.
            assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(2_000)
        }
    }

    private suspend fun assertFails(what: String, block: suspend () -> Unit) {
        try {
            block()
            fail("expected a failure: $what")
        } catch (expected: IOException) {
            // A failure the repository turns into "no segments this time".
        }
    }

    /** One canned answer per connection; null = accept, read the request and never answer. */
    private class TinyServer(private val answer: (requestLine: String) -> String?) : Closeable {
        private val socket = ServerSocket(0, 16, InetAddress.getLoopbackAddress())
        val port: Int = socket.localPort
        val requests = CopyOnWriteArrayList<String>()

        init {
            thread(isDaemon = true, name = "tiny-server") {
                while (!socket.isClosed) {
                    val connection = try {
                        socket.accept()
                    } catch (e: IOException) {
                        break
                    }
                    thread(isDaemon = true) {
                        connection.use { c ->
                            val reader = c.getInputStream().bufferedReader()
                            val line = reader.readLine() ?: return@use
                            while (reader.readLine()?.isNotEmpty() == true) Unit
                            requests += line
                            val response = answer(line)
                            if (response == null) {
                                runCatching { Thread.sleep(30_000) }
                                return@use
                            }
                            c.getOutputStream().apply {
                                write(response.toByteArray())
                                flush()
                            }
                        }
                    }
                }
            }
        }

        override fun close() = socket.close()
    }

    private companion object {
        val ANSWER = """
            [{"videoID":"WRKuBIJykIs","segments":[{"category":"intro","actionType":"skip","segment":[0,30.12],"UUID":"0adf","videoDuration":2405.161}]},
             {"videoID":"lC3h3Lm3q54","segments":[{"category":"sponsor","actionType":"skip","segment":[119.513,162.837],
              "UUID":"607daf4500d5b550943dc6858ca88c3859e160fad9bb6a26e88eee5825041007","videoDuration":465,"locked":1,"votes":22,"description":""}]}]
        """.trimIndent()

        fun http(code: Int, body: String): String {
            val bytes = body.toByteArray()
            return "HTTP/1.1 $code X\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n$body"
        }
    }
}
