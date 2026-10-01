package com.ahmadnayfeh.shasha.remote

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.Socket
import java.net.URI

/** Binds a real CIO socket on 127.0.0.1 and talks plain HTTP to it. */
class RealSocketTest {
    private val loopback = InetAddress.getByName("127.0.0.1")
    private val controller = FakeController()
    private val servers = mutableListOf<RemoteServer>()

    private val errors = java.util.concurrent.CopyOnWriteArrayList<Throwable>()

    private fun server() = RemoteServer(controller) { TOKEN }.also { it.onError = errors::add; servers += it }

    @After
    fun tearDown() = servers.forEach { it.stop() }

    private fun get(port: Int, path: String, bearer: String? = null): Pair<Int, String> {
        val conn = URI("http://127.0.0.1:$port$path").toURL().openConnection() as HttpURLConnection
        conn.connectTimeout = 3_000
        conn.readTimeout = 5_000
        bearer?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
        return try {
            val code = conn.responseCode
            val stream = if (code >= 400) conn.errorStream else conn.inputStream
            code to (stream?.use { it.readBytes().decodeToString() } ?: "") +
                "\n" + conn.getHeaderField("Content-Type")
        } finally {
            conn.disconnect()
        }
    }

    @Test
    fun servesTheApiAndThePage_overARealSocket() {
        val server = server()
        val port = server.start(loopback, 0)
        assertThat(port).isGreaterThan(0)
        assertThat(server.boundPort).isEqualTo(port)
        assertThat(server.isRunning).isTrue()

        val (code, body) = get(port, "/api/status", TOKEN)
        assertThat(code).isEqualTo(200)
        assertThat(body).contains("\"profileName\":\"أحمد\"")
        assertThat(body).contains("application/json; charset=UTF-8")

        assertThat(get(port, "/api/status").first).isEqualTo(401)
        val page = get(port, "/?t=$TOKEN")
        assertThat(page.first).isEqualTo(200)
        assertThat(page.second).contains("dir=\"rtl\"")
        assertThat(page.second).contains("text/html; charset=UTF-8")
    }

    @Test
    fun stop_closesTheSocket_andRestartWorks() {
        val server = server()
        val port = server.start(loopback, 0)
        server.stop()
        assertThat(server.isRunning).isFalse()
        assertThrows(ConnectException::class.java) { Socket(loopback, port).close() }
        server.stop() // idempotent
        val again = server.start(loopback, port)
        assertThat(again).isEqualTo(port)
        assertThat(get(port, "/api/status", TOKEN).first).isEqualTo(200)
    }

    @Test
    fun refusesTheWildcardAddress() {
        assertThrows(IllegalArgumentException::class.java) { server().start(InetAddress.getByName("0.0.0.0"), 0) }
    }

    @Test
    fun portInUse_throwsTheBindException_andLeavesTheServerStopped() {
        val port = server().start(loopback, 0)
        repeat(10) {
            val second = server()
            val error = runCatching { second.start(loopback, port) }.exceptionOrNull()
            assertThat(error).isInstanceOf(java.net.BindException::class.java)
            assertThat(second.isRunning).isFalse()
            assertThat(second.start(loopback, 0)).isNotEqualTo(port)
            second.stop()
        }
    }

    @Test
    fun portInUse_isNotAnUncaughtException() {
        // On Android an exception that escapes to a thread's handler kills the app.
        val uncaught = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught += e }
        try {
            val port = server().start(loopback, 0)
            assertThrows(java.net.BindException::class.java) { server().start(loopback, port) }
            Thread.sleep(300)
            // kotlinx-coroutines-test also records stray coroutine exceptions and fails the next runTest.
            kotlinx.coroutines.test.runTest { }
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
        assertThat(uncaught).isEmpty()
        assertThat(errors).isEmpty() // start() already reported it by throwing
    }

    @Test
    fun controllerFailures_reachOnError() {
        controller.failure = IllegalStateException("boom")
        val port = server().start(loopback, 0)
        assertThat(get(port, "/api/status", TOKEN).first).isEqualTo(500)
        assertThat(errors.map { it.message }).containsExactly("boom")
    }

    @Test
    fun startTwice_isAnError() {
        val server = server()
        server.start(loopback, 0)
        assertThrows(IllegalStateException::class.java) { server.start(loopback, 0) }
    }

    private fun login(port: Int): String {
        val conn = URI("http://127.0.0.1:$port/api/parent/login").toURL().openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Authorization", "Bearer $TOKEN")
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.use { it.write("""{"pin":"1234"}""".toByteArray()) }
        return Regex("\"session\":\"([^\"]+)\"").find(conn.inputStream.use { it.readBytes().decodeToString() })!!.groupValues[1]
    }

    private fun parentCall(port: Int, session: String): Int {
        val check = URI("http://127.0.0.1:$port/api/parent/subscriptions").toURL().openConnection() as HttpURLConnection
        check.setRequestProperty("Authorization", "Bearer $TOKEN")
        check.setRequestProperty(PARENT_SESSION_HEADER, session)
        return try {
            check.responseCode
        } finally {
            check.disconnect()
        }
    }

    @Test
    fun parentSessions_dieWithTheServer() {
        val server = server()
        val port = server.start(loopback, 0)
        val session = login(port)
        server.stop()
        server.start(loopback, port)
        assertThat(parentCall(port, session)).isEqualTo(403)
    }

    @Test
    fun movingToAnotherPort_keepsTheParentSessions_onlyWhenAskedTo() {
        val server = server()
        val port = server.start(loopback, 0)
        val session = login(port)
        // The parent chose a new port: the page follows the server there and stays logged in.
        server.stop(endSessions = false)
        val moved = server.start(loopback, 0)
        assertThat(parentCall(moved, session)).isEqualTo(200)
        // Any other stop (network change, the remote switched off) ends them, even while stopped.
        server.stop(endSessions = false)
        server.stop()
        val again = server.start(loopback, 0)
        assertThat(parentCall(again, session)).isEqualTo(403)
    }
}
