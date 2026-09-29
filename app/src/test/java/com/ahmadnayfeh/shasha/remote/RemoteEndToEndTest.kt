package com.ahmadnayfeh.shasha.remote

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.data.PinResult
import com.ahmadnayfeh.shasha.policy.ALLOWED_PLAYLIST
import com.ahmadnayfeh.shasha.policy.DenyReason
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The phone's requests over a real socket: the real [RemoteServer] on 127.0.0.1 in front of the real
 * [TvRemoteController] and the TV's real rules ([RemoteTestEnv]; only YouTube, the player and the screens
 * are fakes).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class RemoteEndToEndTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val loopback = InetAddress.getByName("127.0.0.1") as Inet4Address
    private lateinit var env: RemoteTestEnv
    private lateinit var server: RemoteServer
    private var port = 0

    @Before
    fun setUp() {
        env = remoteEnv(tmp.root)
        server = RemoteServer(env.controller) { TOKEN }
        port = server.start(loopback, 0)
    }

    @After
    fun tearDown() {
        server.stop()
        env.close()
    }

    private class Answer(val code: Int, val body: String) {
        val json: JsonObject get() = Json.parseToJsonElement(body).jsonObject
    }

    private fun call(
        method: String,
        path: String,
        body: String? = null,
        token: String? = TOKEN,
        parent: String? = null,
        port: Int = this.port,
    ): Answer {
        val conn = URI("http://127.0.0.1:$port$path").toURL().openConnection() as HttpURLConnection
        conn.connectTimeout = 5_000
        conn.readTimeout = 20_000
        conn.requestMethod = method
        token?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
        parent?.let { conn.setRequestProperty("X-Parent-Session", it) }
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray()) }
        }
        return try {
            val code = conn.responseCode
            val stream = if (code >= 400) conn.errorStream else conn.inputStream
            Answer(code, stream?.use { it.readBytes().decodeToString() }.orEmpty())
        } finally {
            conn.disconnect()
        }
    }

    @Test
    fun child_aLinkOutsideTheAllowlist_is200OkFalse_andNothingIsPlayed() = runBlocking<Unit> {
        env.enterChild()
        // A shared link naming the allowlisted playlist: the video's own channel is not allowlisted.
        val answer = call("POST", "/api/play", """{"url":"${watchLink(OUTSIDE_VIDEO, list = ALLOWED_PLAYLIST)}"}""")
        assertThat(answer.code).isEqualTo(200)
        assertThat(answer.json["ok"]!!.jsonPrimitive.boolean).isFalse()
        assertThat(answer.json["message"]!!.jsonPrimitive.content).isEqualTo(env.texts.deny(DenyReason.NOT_IN_ALLOWLIST))
        // Nothing reached the screens, and nothing plays.
        assertThat(env.display.shown).isEmpty()
        assertThat(env.hub.active.value).isNull()
        assertThat(env.lock.current()).isNull()

        // The status the page polls says who is watching, and that a child cannot search.
        val status = call("GET", "/api/status").json
        assertThat(status["profileKind"]!!.jsonPrimitive.content).isEqualTo("CHILD")
        assertThat(status["canSearch"]!!.jsonPrimitive.boolean).isFalse()
        assertThat(call("POST", "/api/search", """{"q":"cartoons"}""").code).isEqualTo(403)
    }

    @Test
    fun theParentSection_needsASession_andTheLogin_worksWhileTheTvIsLockedOut() = runBlocking<Unit> {
        val quota = """{"profileId":"kid","minutes":15}"""
        assertThat(call("POST", "/api/parent/quota", quota).code).isEqualTo(401)
        assertThat(call("POST", "/api/parent/quota", quota, parent = "made-up").code).isEqualTo(403)
        assertThat(call("POST", "/api/parent/unlock-time", """{"extraMinutes":15}""").code).isEqualTo(401)
        assertThat(call("GET", "/api/parent/backup").code).isEqualTo(401)
        assertThat(env.profiles.get("kid")!!.dailyQuotaMinutes).isEqualTo(60)

        // A child's wrong guesses on the TV lock the TV's PIN pad out, not the parent's phone.
        env.lockOutTheTvPin()
        val wrong = call("POST", "/api/parent/login", """{"pin":"$WRONG_PIN"}""")
        assertThat(wrong.code).isEqualTo(401)
        val login = call("POST", "/api/parent/login", """{"pin":"$PIN"}""")
        assertThat(login.code).isEqualTo(200)
        val session = login.json["session"]!!.jsonPrimitive.content

        val saved = call("POST", "/api/parent/quota", quota, parent = session)
        assertThat(saved.code).isEqualTo(200)
        assertThat(saved.json["ok"]!!.jsonPrimitive.boolean).isTrue()
        assertThat(env.profiles.get("kid")!!.dailyQuotaMinutes).isEqualTo(15)

        // A new parent PIN (or a regenerated token) logs every phone out: AppContainer calls this then.
        server.revokeParentSessions()
        assertThat(call("POST", "/api/parent/quota", quota, parent = session).code).isEqualTo(403)
    }

    @Test
    fun thePhonesPinLockout_outlivesARestartOfTheApp_butNeverTouchesTheTvsPinPad() = runBlocking<Unit> {
        repeat(4) { assertThat(call("POST", "/api/parent/login", """{"pin":"$WRONG_PIN"}""").code).isEqualTo(401) }
        val fifth = call("POST", "/api/parent/login", """{"pin":"$WRONG_PIN"}""")
        assertThat(fifth.code).isEqualTo(429)
        assertThat(fifth.json["code"]!!.jsonPrimitive.content).isEqualTo("locked_out")

        // A new process (the TV was unplugged, the app updated or killed): a new server, whose own brake starts
        // from nothing, and the phone's count read back from the store.
        server.stop()
        val restarted = RemoteServer(env.newController(PhonePin(env.policyStore, env.clock))) { TOKEN }
        val newPort = restarted.start(loopback, 0)
        try {
            val right = call("POST", "/api/parent/login", """{"pin":"$PIN"}""", port = newPort)
            assertThat(right.code).isEqualTo(429)
            assertThat(right.json["code"]!!.jsonPrimitive.content).isEqualTo("locked_out")
            // The TV's own PIN pad keeps its own count: the parent still gets in on the TV.
            assertThat(env.parentPin.verify(PIN)).isEqualTo(PinResult.Success)
        } finally {
            restarted.stop()
        }
    }

    @Test
    fun aRestoreFromThePhone_isFinished_evenWhenTheServerStopsMidway() {
        val entered = CountDownLatch(1)
        val release = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        val slow = remoteEnv(tmp.newFolder("slow")) {
            // The last step of a restore (AppContainer's: the feed sync, logging the phones out).
            entered.countDown()
            release.await()
            finished.complete(Unit)
        }
        val slowServer = RemoteServer(slow.controller) { TOKEN }
        try {
            val slowPort = slowServer.start(loopback, 0)
            val session = call("POST", "/api/parent/login", """{"pin":"$PIN"}""", port = slowPort).json["session"]!!.jsonPrimitive.content
            val backup = runBlocking { slow.controller.exportBackup() }.decodeToString()
            runBlocking { slow.profiles.update("kid") { it.copy(name = "تغيّر") } }

            val upload = thread { runCatching { call("POST", "/api/parent/backup", backup, parent = session, port = slowPort) } }
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue()
            // The TV's address changed (or Wi-Fi dropped): the server stops and cancels the calls still running.
            slowServer.stop()
            release.complete(Unit)
            // Only the answer was lost: the restore went on to its end.
            runBlocking { withTimeout(5_000) { finished.await() } }
            assertThat(runBlocking { slow.profiles.get("kid") }!!.name).isEqualTo("سارة")
            upload.join(10_000)
        } finally {
            slowServer.stop()
            slow.close()
        }
    }

    @Test
    fun withoutThePairingToken_nothingAnswers() {
        assertThat(call("GET", "/api/status", token = null).code).isEqualTo(401)
        assertThat(call("POST", "/api/play", """{"url":"${watchLink(INSIDE_VIDEO)}"}""", token = "wrong-token").code).isEqualTo(401)
        assertThat(env.display.shown).isEmpty()
    }

    private companion object {
        const val TOKEN = "Pair1ngToken-for-the-end-to-end-test"
    }
}
