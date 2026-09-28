package com.ahmadnayfeh.shasha.remote

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.junit.Test

/** The server's own brake on PIN guessing (PinGate), whatever the controller does with the PIN. */
class PinGateTest {
    private val second = 1_000L
    private val hour = 60 * 60 * second

    private fun Harness.wrongPins() = apply {
        controller.result = RemoteResult.error("رمز PIN غير صحيح.")
        controller.loginResult = ParentLoginResult.WrongPin(attemptsLeft = 3)
    }

    private suspend fun io.ktor.client.HttpClient.guess(pin: String = "0000") =
        call("POST", "/api/profile", """{"id":"a","pin":"$pin"}""")

    private suspend fun io.ktor.client.HttpClient.tryLogin(pin: String) =
        call("POST", "/api/parent/login", """{"pin":"$pin"}""")

    private suspend fun HttpResponse.assertBlocked(retryAfter: Long) {
        assertThat(status).isEqualTo(HttpStatusCode.TooManyRequests)
        assertThat(headers[HttpHeaders.RetryAfter]).isEqualTo(retryAfter.toString())
        val json = json()
        assertThat(json.str("code")).isEqualTo("locked_out")
        assertThat(json.str("message")).isEqualTo(ServerMessages.lockedOut(retryAfter))
    }

    private fun Harness.switchCalls() = controller.calls.count { it.startsWith("switchProfile(") }

    @Test
    fun profilePin_fiveWrong_thenEveryGuessIsRefused_withoutAskingTheController() = remoteTest(Harness().wrongPins()) { h ->
        repeat(5) { i ->
            val res = client.guess("000$i")
            assertThat(res.status).isEqualTo(HttpStatusCode.OK)
            assertThat(res.json().str("ok")).isEqualTo("false")
        }
        client.guess("1234").assertBlocked(retryAfter = 30)
        client.guess("1235").assertBlocked(retryAfter = 30)
        assertThat(h.switchCalls()).isEqualTo(5)
    }

    @Test
    fun blockEnds_andDoublesAfterEachNewFailure_upToAnHour() = remoteTest(Harness().wrongPins()) { h ->
        repeat(5) { client.guess() }
        h.now += 30 * second - 1
        client.guess().assertBlocked(retryAfter = 1)
        h.now += 1
        assertThat(client.guess().status).isEqualTo(HttpStatusCode.OK) // 6th failure
        client.guess().assertBlocked(retryAfter = 60)
        var expected = 60L
        repeat(10) {
            h.now += expected * second
            assertThat(client.guess().status).isEqualTo(HttpStatusCode.OK)
            expected = (expected * 2).coerceAtMost(3600)
            client.guess().assertBlocked(retryAfter = expected)
        }
        assertThat(expected).isEqualTo(3600)
        assertThat(h.switchCalls()).isEqualTo(16)
    }

    @Test
    fun loginAndProfilePins_shareOneCount() = remoteTest(Harness().wrongPins()) { h ->
        repeat(3) { client.guess() }
        repeat(2) { assertThat(client.tryLogin("9999").status).isEqualTo(HttpStatusCode.Unauthorized) }
        client.tryLogin("1234").assertBlocked(retryAfter = 30)
        client.guess("1234").assertBlocked(retryAfter = 30)
        assertThat(h.controller.calls.count { it.startsWith("parentLogin(") }).isEqualTo(2)
    }

    @Test
    fun controllerLockout_countsAsAFailure_andTheGateTakesOver() = remoteTest(Harness().wrongPins()) { h ->
        h.controller.loginResult = ParentLoginResult.LockedOut(retryAfterSeconds = 300)
        repeat(5) { client.tryLogin("0000").assertBlocked(retryAfter = 300) } // the controller's own lockout
        client.tryLogin("0000").assertBlocked(retryAfter = 30) // the gate's: the controller is not asked
        assertThat(h.controller.calls.count { it.startsWith("parentLogin(") }).isEqualTo(5)
    }

    @Test
    fun parentLoginSuccess_resetsTheCount() = remoteTest(Harness().wrongPins()) { h ->
        repeat(4) { client.guess() }
        h.controller.loginResult = ParentLoginResult.Success
        assertThat(client.tryLogin("1234").status).isEqualTo(HttpStatusCode.OK)
        repeat(4) { assertThat(client.guess().status).isEqualTo(HttpStatusCode.OK) }
        assertThat(client.guess().status).isEqualTo(HttpStatusCode.OK) // 5th failure since the reset
        client.guess().assertBlocked(retryAfter = 30)
    }

    @Test
    fun profileSwitchSuccess_doesNotResetTheCount() = remoteTest(Harness().wrongPins()) { h ->
        // A switch "succeeds" without checking anything, e.g. to the profile already on screen.
        repeat(4) { client.guess() }
        h.controller.result = RemoteResult.ok("«أحمد» هو الملف المفتوح الآن.")
        assertThat(client.guess().json().str("ok")).isEqualTo("true")
        h.controller.result = RemoteResult.error("رمز PIN غير صحيح.")
        client.guess() // 5th failure
        client.guess().assertBlocked(retryAfter = 30)
    }

    @Test
    fun failures_areForgottenAfterSixQuietHours() = remoteTest(Harness().wrongPins()) { h ->
        repeat(4) { client.guess() }
        h.now += 6 * hour
        repeat(4) { assertThat(client.guess().status).isEqualTo(HttpStatusCode.OK) }
        assertThat(h.switchCalls()).isEqualTo(8)
    }

    @Test
    fun switchWithoutPin_isNeverBlocked() = remoteTest(Harness().wrongPins()) { h ->
        repeat(5) { client.guess() }
        client.guess().assertBlocked(retryAfter = 30)
        val res = client.call("POST", "/api/profile", """{"id":"c1"}""")
        assertThat(res.status).isEqualTo(HttpStatusCode.OK)
        assertThat(h.controller.calls.last()).isEqualTo("switchProfile(c1,null)")
    }

    @Test
    fun rejection_countsAsAWrongPin_crashDoesNot() = remoteTest { h ->
        h.controller.failure = IllegalStateException("bug")
        repeat(8) { assertThat(client.guess().status).isEqualTo(HttpStatusCode.InternalServerError) }
        h.controller.failure = RemoteRejection("أدخل رمز PIN الصحيح.")
        repeat(5) { assertThat(client.guess().status).isEqualTo(HttpStatusCode.Forbidden) }
        client.guess().assertBlocked(retryAfter = 30)
        assertThat(h.switchCalls()).isEqualTo(13)
    }

    @Test
    fun parallelGuesses_areCheckedOneAtATime_soStillOnlyFiveReachTheController() = remoteTest(Harness().wrongPins()) { h ->
        val statuses = coroutineScope {
            (0 until 40).map { i -> async { client.guess(i.toString().padStart(4, '0')).status } }.awaitAll()
        }
        assertWithMessage(statuses.toString()).that(statuses.count { it == HttpStatusCode.OK }).isEqualTo(5)
        assertThat(statuses.count { it == HttpStatusCode.TooManyRequests }).isEqualTo(35)
        assertThat(h.switchCalls()).isEqualTo(5)
    }

    @Test
    fun theGate_survivesAServerRestart() {
        val controller = FakeController().apply { result = RemoteResult.error("رمز PIN غير صحيح.") }
        val server = RemoteServer(controller) { TOKEN }
        val loopback = java.net.InetAddress.getByName("127.0.0.1")
        try {
            val port = server.start(loopback, 0)
            repeat(5) { assertThat(post(port, "/api/profile", """{"id":"a","pin":"0000"}""")).isEqualTo(200) }
            server.stop()
            server.start(loopback, port)
            assertThat(post(port, "/api/profile", """{"id":"a","pin":"1234"}""")).isEqualTo(429)
        } finally {
            server.stop()
        }
    }

    private fun post(port: Int, path: String, body: String): Int {
        val conn = java.net.URI("http://127.0.0.1:$port$path").toURL().openConnection() as java.net.HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 3_000
        conn.readTimeout = 5_000
        conn.setRequestProperty("Authorization", "Bearer $TOKEN")
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.use { it.write(body.toByteArray()) }
        return try {
            conn.responseCode
        } finally {
            conn.disconnect()
        }
    }
}
