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

    private fun server() = RemoteServer(controller) { TOKEN }.also { servers += it }

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
    fun portInUse_throws_andLeavesTheServerStopped() {
        val port = server().start(loopback, 0)
        val second = server()
        val error = runCatching { second.start(loopback, port) }.exceptionOrNull()
        assertThat(error).isNotNull()
        assertThat(second.isRunning).isFalse()
        assertThat(second.start(loopback, 0)).isNotEqualTo(port)
    }

    @Test
    fun startTwice_isAnError() {
        val server = server()
        server.start(loopback, 0)
        assertThrows(IllegalStateException::class.java) { server.start(loopback, 0) }
    }

    @Test
    fun parentSessions_dieWithTheServer() {
        val server = server()
        val port = server.start(loopback, 0)
        val conn = URI("http://127.0.0.1:$port/api/parent/login").toURL().openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Authorization", "Bearer $TOKEN")
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.use { it.write("""{"pin":"1234"}""".toByteArray()) }
        val session = Regex("\"session\":\"([^\"]+)\"").find(conn.inputStream.use { it.readBytes().decodeToString() })!!.groupValues[1]
        server.stop()
        server.start(loopback, port)
        val check = URI("http://127.0.0.1:$port/api/parent/subscriptions").toURL().openConnection() as HttpURLConnection
        check.setRequestProperty("Authorization", "Bearer $TOKEN")
        check.setRequestProperty(PARENT_SESSION_HEADER, session)
        assertThat(check.responseCode).isEqualTo(403)
    }
}
