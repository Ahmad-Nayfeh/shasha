package com.ahmadnayfeh.shasha.remote

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.R
import com.ahmadnayfeh.shasha.data.Behavior
import com.ahmadnayfeh.shasha.data.BehaviorKeys
import com.ahmadnayfeh.shasha.data.PinResult
import com.ahmadnayfeh.shasha.policy.DenyReason
import com.ahmadnayfeh.shasha.policy.Profile
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI

/**
 * The phone page's LOGIC CHECKLIST (v1.1 part C), item by item, as the page meets it: HTTP over a real socket, the real
 * [RemoteRuntime] (binding, moving to a new port, stopping when the network drops) in front of the real
 * [TvRemoteController] and the TV's real rules ([RemoteTestEnv]). Two phones are two clients with their own parent
 * sessions. What only the page itself does (optimistic display and its rollback, the debounce, the double-tap guard, the
 * visible-only polling) is checked in a browser against the same rules ([PhonePageQaServer]).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class PhoneLogicChecklistTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val loopback = InetAddress.getByName("127.0.0.1") as Inet4Address
    private lateinit var env: RemoteTestEnv
    private lateinit var runtime: RemoteRuntime
    private lateinit var controller: TvRemoteController
    private lateinit var token: String

    private val port: Int get() = (runtime.state.value as RemoteState.Running).port

    @Before
    fun setUp() = runBlocking {
        env = remoteEnv(tmp.root)
        env.behavior.awaitLoaded()
        controller = env.newController(
            env.phonePin,
            // As AppContainer wires it, with a short announcement (the pages poll every 2 s; here the test does).
            restartRemote = { runtime.restart(announceMs = 600) },
            restartDelayMs = 100,
            portAvailable = { runtime.portAvailable(it) },
            movingTo = { runtime.movingTo.value },
        )
        runtime = RemoteRuntime(env.remoteSettings, controller, SilentAnnouncer, findAddress = { loopback })
        // A port nothing else on this machine listens on (8765 may be a QA server).
        env.remoteSettings.rememberPort(freePort())
        assertThat(runtime.refresh()).isInstanceOf(RemoteState.Running::class.java)
        token = env.remoteSettings.token()
    }

    @After
    fun tearDown() = runBlocking {
        runtime.stop()
        env.close()
    }

    // ---- 1. State sync after every action ---------------------------------------------------------------------

    @Test
    fun stateSync_everyAnswerIsTheTvsState_andTheStatusMarkMovesAfterEveryWrite() = runBlocking<Unit> {
        val phone = Phone().apply { login() }
        suspend fun afterWrite(what: String, write: suspend () -> Answer) {
            val before = phone.status().getValue("rev").jsonPrimitive.long
            val answer = write()
            assertThat(answer.code).isEqualTo(200)
            assertThat(answer.json["ok"]!!.jsonPrimitive.boolean).isTrue()
            try {
                withTimeout(5_000) { while (phone.status().getValue("rev").jsonPrimitive.long == before) delay(20) }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                throw AssertionError("The status mark did not move after $what", e)
            }
        }
        // A setting: its answer carries the whole page as the TV has it now.
        afterWrite("a setting") { phone.post("/api/parent/settings", """{"key":"warning_minutes","value":10,"base":5}""") }
        val page = phone.get("/api/parent/settings").json
        assertThat(page.setting("warning_minutes")).isEqualTo(JsonPrimitive(10))
        afterWrite("a quota") { phone.post("/api/parent/quota", """{"profileId":"kid","minutes":30}""") }
        assertThat(phone.profiles().first { it.str("id") == "kid" }.getValue("dailyQuotaMinutes").jsonPrimitive.int).isEqualTo(30)
        afterWrite("a schedule") { phone.post("/api/parent/schedule", """{"profileId":"kid","fromMinute":480,"untilMinute":1200}""") }
        assertThat(phone.profiles().first { it.str("id") == "kid" }.getValue("scheduleFrom").jsonPrimitive.int).isEqualTo(480)
        afterWrite("a profile switch") { phone.post("/api/profile", """{"id":"kid"}""") }
        assertThat(phone.status().str("profileId")).isEqualTo("kid")
        assertThat(phone.profiles().first { it.str("id") == "kid" }.getValue("isCurrent").jsonPrimitive.boolean).isTrue()
        afterWrite("a blocked word") { phone.post("/api/parent/blocklist/add", """{"kind":"keyword","value":"رعب"}""") }
        afterWrite("a new child") { phone.post("/api/parent/profile/create", """{"name":"ليلى","avatar":"cat"}""") }
        assertThat(phone.profiles().map { it.str("name") }).contains("ليلى")
        // A change made on the TV itself moves it too: the page reads again what it shows.
        val before = phone.status().getValue("rev").jsonPrimitive.long
        env.behavior.setSeriousMode(false)
        withTimeout(5_000) { while (phone.status().getValue("rev").jsonPrimitive.long == before) delay(20) }
        assertThat(phone.get("/api/parent/settings").json.setting("serious_mode")).isEqualTo(JsonPrimitive(false))
    }

    // ---- 2. Session expiry in the middle of an action -----------------------------------------------------------

    @Test
    fun sessionExpiry_midAction_nothingIsDone_andTheSameRequestWorksAfterTheNextLogin() = runBlocking<Unit> {
        val phone = Phone().apply { login() }
        val old = phone.session
        // The session ends while the parent is on a page (a new PIN, a restore, 30 idle minutes, a restart).
        runtime.revokeParentSessions()
        val refused = phone.post("/api/parent/quota", """{"profileId":"kid","minutes":15}""")
        assertThat(refused.code).isEqualTo(403)
        assertThat(refused.json.str("code")).isEqualTo("parent_session")
        assertThat(env.profiles.get("kid")!!.dailyQuotaMinutes).isEqualTo(60) // nothing was done
        val setting = phone.post("/api/parent/settings", """{"key":"serious_mode","value":false,"base":true}""")
        assertThat(setting.code).isEqualTo(403)
        assertThat(env.behavior.current().seriousMode).isTrue()
        // The page asks for the PIN right there and sends the same request again.
        phone.login()
        assertThat(phone.session).isNotEqualTo(old)
        assertThat(phone.post("/api/parent/quota", """{"profileId":"kid","minutes":15}""").json["ok"]!!.jsonPrimitive.boolean).isTrue()
        assertThat(phone.post("/api/parent/settings", """{"key":"serious_mode","value":false,"base":true}""").json["ok"]!!.jsonPrimitive.boolean).isTrue()
        assertThat(env.profiles.get("kid")!!.dailyQuotaMinutes).isEqualTo(15)
        assertThat(env.behavior.current().seriousMode).isFalse()
        // Without any session header at all: 401, the same code, the same answer from the page.
        assertThat(Phone().post("/api/parent/unlock-video", null).json.str("code")).isEqualTo("parent_session")
    }

    // ---- 3. Wrong PIN and lockout; the phone's and the TV's limiters stay apart ---------------------------------

    @Test
    fun wrongPin_andLockout_thePhoneAndTheTvCountApart() = runBlocking<Unit> {
        val phone = Phone()
        val first = phone.post("/api/parent/login", """{"pin":"$WRONG_PIN"}""")
        assertThat(first.code).isEqualTo(401)
        assertThat(first.json.str("code")).isEqualTo("wrong_pin")
        assertThat(first.json.str("message")).contains("4")
        repeat(3) { assertThat(phone.post("/api/parent/login", """{"pin":"$WRONG_PIN"}""").code).isEqualTo(401) }
        val locked = phone.post("/api/parent/login", """{"pin":"$WRONG_PIN"}""")
        assertThat(locked.code).isEqualTo(429)
        assertThat(locked.json.str("code")).isEqualTo("locked_out")
        assertThat(locked.retryAfter).isGreaterThan(0)
        // Locked out on the phone: even the right PIN, from any phone (they share one count), and a switch with it.
        assertThat(Phone().post("/api/parent/login", """{"pin":"$PIN"}""").code).isEqualTo(429)
        env.enterChild()
        assertThat(phone.post("/api/profile", """{"id":"adult","pin":"$PIN"}""").code).isEqualTo(429)
        // The TV's PIN pad never counted any of it.
        assertThat(env.parentPin.remainingLockMillis()).isEqualTo(0L)
        assertThat(env.parentPin.verify(PIN)).isEqualTo(PinResult.Success)

        // And the other way: a fresh TV whose PIN pad a child locked out still lets the parent in from the phone.
        val other = remoteEnv(tmp.newFolder("other"))
        val server = RemoteServer(other.controller) { "T0ken-for-the-other-tv" }
        val otherPort = server.start(loopback, 0)
        try {
            other.lockOutTheTvPin()
            val login = Phone(otherPort, "T0ken-for-the-other-tv").post("/api/parent/login", """{"pin":"$PIN"}""")
            assertThat(login.code).isEqualTo(200)
            assertThat(other.parentPin.remainingLockMillis()).isGreaterThan(0L)
        } finally {
            server.stop()
            other.close()
        }
    }

    // ---- 4. Child and adult rules, the optional adult PIN included -----------------------------------------------

    @Test
    fun childAndAdultRules_includingTheAdultEntryPin_inBothStates() = runBlocking<Unit> {
        val phone = Phone()
        env.profiles.upsert(Profile.newAdult("ضيف", id = "guest"))
        suspend fun needsPin(id: String) = phone.get("/api/profiles").array.first { it.jsonObject.str("id") == id }.jsonObject.getValue("needsPin").jsonPrimitive.boolean

        // The adult: search, any link; entering another adult profile asks for the PIN (the default).
        assertThat(phone.status().getValue("canSearch").jsonPrimitive.boolean).isTrue()
        assertThat(needsPin("guest")).isTrue()
        assertThat(phone.post("/api/profile", """{"id":"guest"}""").json["ok"]!!.jsonPrimitive.boolean).isFalse()
        assertThat(env.current().id).isEqualTo("adult")

        // The PIN off: the adult profiles open without it, on the phone as on the TV.
        env.behavior.setAdultEntryPin(false)
        withTimeout(5_000) { while (needsPin("guest")) delay(20) }
        assertThat(phone.post("/api/profile", """{"id":"guest"}""").json["ok"]!!.jsonPrimitive.boolean).isTrue()
        assertThat(env.current().id).isEqualTo("guest")
        // ...but the parent's section and settings still need it.
        assertThat(phone.get("/api/parent/settings").code).isEqualTo(401)

        // The child: no search, nothing outside its allowlist, and leaving it needs the PIN whatever the setting.
        assertThat(phone.post("/api/profile", """{"id":"kid"}""").json["ok"]!!.jsonPrimitive.boolean).isTrue()
        assertThat(phone.status().getValue("canSearch").jsonPrimitive.boolean).isFalse()
        assertThat(phone.post("/api/search", """{"q":"x"}""").code).isEqualTo(403)
        val outside = phone.post("/api/play", """{"url":"${watchLink(OUTSIDE_VIDEO)}"}""")
        assertThat(outside.json.str("message")).isEqualTo(env.texts.deny(DenyReason.NOT_IN_ALLOWLIST))
        assertThat(needsPin("adult")).isTrue()
        assertThat(phone.post("/api/profile", """{"id":"adult"}""").json["ok"]!!.jsonPrimitive.boolean).isFalse()
        env.behavior.setAdultEntryPin(true)
        assertThat(phone.post("/api/profile", """{"id":"adult","pin":"$PIN"}""").json["ok"]!!.jsonPrimitive.boolean).isTrue()
        assertThat(env.current().id).isEqualTo("adult")
    }

    // ---- 5. Serious Mode on and off during playback ------------------------------------------------------------

    @Test
    fun seriousMode_turnedOffAndOn_whileAVideoPlays() = runBlocking<Unit> {
        val phone = Phone().apply { login() }
        env.enterChild()
        val player = env.playOnScreen(INSIDE_VIDEO)
        env.lock.onVideoStarted("kid", INSIDE_VIDEO, null)
        assertThat(phone.status().getValue("videoLocked").jsonPrimitive.boolean).isTrue()
        assertThat(phone.post("/api/control", """{"action":"STOP"}""").code).isEqualTo(403)
        assertThat(phone.post("/api/play", """{"url":"${watchLink(INSIDE_VIDEO)}"}""").json.str("message")).isEqualTo(env.texts.deny(DenyReason.LOCKED))
        // Pausing and seeking still work under the lock.
        assertThat(phone.post("/api/control", """{"action":"PAUSE"}""").json["ok"]!!.jsonPrimitive.boolean).isTrue()

        // Off from the phone: the lock that holds the video ends at once, without a surrender.
        assertThat(phone.post("/api/parent/settings", """{"key":"serious_mode","value":false,"base":true}""").json["ok"]!!.jsonPrimitive.boolean).isTrue()
        withTimeout(5_000) { while (phone.status().getValue("videoLocked").jsonPrimitive.boolean) delay(20) }
        assertThat(phone.status().getValue("seriousMode").jsonPrimitive.boolean).isFalse()
        assertThat(phone.post("/api/control", """{"action":"STOP"}""").json["ok"]!!.jsonPrimitive.boolean).isTrue()
        assertThat(player.left).isEqualTo(1)
        assertThat(env.usage.surrenders("kid", 0)).isEmpty()
        // While it is off nothing that starts is locked.
        env.lock.onVideoStarted("kid", INSIDE_VIDEO, null)
        assertThat(env.lock.current()).isNull()

        // On again during a video: that video plays on unlocked; the next one that starts is locked.
        assertThat(phone.post("/api/parent/settings", """{"key":"serious_mode","value":true,"base":false}""").json["ok"]!!.jsonPrimitive.boolean).isTrue()
        withTimeout(5_000) { while (!phone.status().getValue("seriousMode").jsonPrimitive.boolean) delay(20) }
        assertThat(phone.status().getValue("videoLocked").jsonPrimitive.boolean).isFalse()
        env.source.videos["inside00002"] = com.ahmadnayfeh.shasha.policy.clip("inside00002", channelId = com.ahmadnayfeh.shasha.policy.ALLOWED_CHANNEL)
        env.lock.onVideoStarted("kid", "inside00002", null)
        assertThat(phone.status().getValue("videoLocked").jsonPrimitive.boolean).isTrue()
    }

    // ---- 6. Double taps and concurrent requests ------------------------------------------------------------------

    @Test
    fun concurrentRequests_fromTwoPhones_areTakenOneAtATime_andNothingIsUndoneUnseen() = runBlocking<Unit> {
        val a = Phone().apply { login() }
        val b = Phone().apply { login() }
        val shown = JsonArray(listOf(15, 30, 60).map(::JsonPrimitive))
        // Ten changes of the same list at the same moment, each made over what both pages showed: one wins, every other
        // one is refused with the list as it is now (none undoes the winner unseen).
        val wanted = listOf(listOf(5), listOf(10), listOf(15), listOf(20), listOf(30), listOf(45), listOf(60), listOf(90), listOf(120), listOf(5, 10))
        val answers = wanted.mapIndexed { i, list ->
            async(Dispatchers.IO) {
                (if (i % 2 == 0) a else b).post("/api/parent/settings", """{"key":"extra_minutes","value":${JsonArray(list.map(::JsonPrimitive))},"base":$shown}""")
            }
        }.awaitAll()
        assertThat(answers.map { it.code }.toSet()).containsExactly(200)
        val winners = answers.filter { it.json["ok"]!!.jsonPrimitive.boolean }
        assertThat(winners).hasSize(1)
        val won = winners.single().json["settings"]!!.jsonObject.setting("extra_minutes")
        assertThat(JsonArray(env.behavior.current().extraMinutes.map(::JsonPrimitive))).isEqualTo(won)
        answers.filter { !it.json["ok"]!!.jsonPrimitive.boolean }.forEach {
            assertThat(it.json.str("message")).isEqualTo(env.texts.get(R.string.phone_setting_changed_elsewhere, env.texts.get(R.string.playback_extra)))
        }

        // Quota writes and status polls all at once: every one answered, the last write is what the TV has.
        val writes = (1..8).map { n ->
            async(Dispatchers.IO) { (if (n % 2 == 0) a else b).post("/api/parent/quota", """{"profileId":"kid","minutes":${n * 15}}""") }
        } + (1..8).map { async(Dispatchers.IO) { a.get("/api/status") } }
        assertThat(writes.awaitAll().map { it.code }.toSet()).containsExactly(200)
        assertThat(env.profiles.get("kid")!!.dailyQuotaMinutes).isIn((1..8).map { it * 15 })

        // Five wrong PINs at once from both phones: counted one by one, exactly five, then the brake.
        val pins = (1..6).map { i -> async(Dispatchers.IO) { Phone().post("/api/parent/login", """{"pin":"$WRONG_PIN"}""").code } }.awaitAll()
        assertThat(pins.count { it == 401 }).isEqualTo(4)
        assertThat(pins.count { it == 429 }).isEqualTo(2)
        assertThat(env.parentPin.verify(PIN)).isEqualTo(PinResult.Success)
    }

    // ---- 7. Out-of-range values ------------------------------------------------------------------------------------

    @Test
    fun outOfRangeValues_areRefused_andNothingChanges() = runBlocking<Unit> {
        val phone = Phone().apply { login() }
        val badRequests = listOf(
            "/api/parent/quota" to """{"profileId":"kid","minutes":1441}""",
            "/api/parent/quota" to """{"profileId":"kid","minutes":-1}""",
            "/api/parent/quota" to """{"profileId":"kid"}""",
            "/api/parent/unlock-time" to """{"extraMinutes":0}""",
            "/api/parent/unlock-time" to """{"extraMinutes":1441}""",
            "/api/control" to """{"action":"SEEK_BY","seconds":0}""",
            "/api/control" to """{"action":"SEEK_BY","seconds":86401}""",
            "/api/control" to """{"action":"JUMP"}""",
            "/api/parent/schedule" to """{"profileId":"kid","fromMinute":1440,"untilMinute":60}""",
            "/api/parent/schedule" to """{"profileId":"kid","fromMinute":60}""",
            "/api/parent/profile/create" to """{"name":"","avatar":"cat"}""",
            "/api/search" to """{"q":"${"x".repeat(201)}"}""",
            "/api/parent/settings" to """{"key":"","value":1}""",
            "/api/parent/settings" to "not json",
        )
        for ((path, body) in badRequests) assertThat(phone.post(path, body).code).isEqualTo(400)
        // Values the TV does not offer: answered with the page as the TV has it, unchanged.
        val refused = listOf(
            """{"key":"warning_minutes","value":7}""",
            """{"key":"warning_minutes","value":"5"}""",
            """{"key":"remote_port","value":80}""",
            """{"key":"remote_port","value":70000}""",
            """{"key":"default_speed","value":9}""",
            """{"key":"extra_minutes","value":[5,10,15,20,30]}""",
            """{"key":"voice_language","value":"fr-FR"}""",
            """{"key":"max_quality","value":1000}""",
        )
        for (body in refused) {
            val answer = phone.post("/api/parent/settings", body)
            assertThat(answer.code).isEqualTo(200)
            assertThat(answer.json["ok"]!!.jsonPrimitive.boolean).isFalse()
            assertThat(answer.json["settings"]).isNotNull()
        }
        assertThat(phone.post("/api/parent/profile/create", """{"name":"${"ن".repeat(21)}","avatar":"cat"}""").json["ok"]!!.jsonPrimitive.boolean).isFalse()
        assertThat(phone.post("/api/parent/profile/create", """{"name":"ليلى","avatar":"person"}""").json["ok"]!!.jsonPrimitive.boolean).isFalse()
        assertThat(env.profiles.get("kid")!!.dailyQuotaMinutes).isEqualTo(60)
        assertThat(env.profiles.all()).hasSize(2)
        assertThat(env.behavior.current()).isEqualTo(Behavior.DEFAULT)
    }

    // ---- 8. Port-change restart ------------------------------------------------------------------------------------

    @Test
    fun aNewPort_isAnnounced_thenTheServerMoves_andEveryPageFollowsStillLoggedIn() = runBlocking<Unit> {
        val a = Phone().apply { login() }
        val b = Phone().apply { login() }
        val oldPort = port
        val newPort = freePort()
        val answer = a.post("/api/parent/settings", """{"key":"remote_port","value":$newPort}""")
        assertThat(answer.json["ok"]!!.jsonPrimitive.boolean).isTrue()
        assertThat(answer.json["reconnectPort"]!!.jsonPrimitive.int).isEqualTo(newPort)
        // The other page hears it from the status before the old port closes.
        val announced = withTimeout(5_000) {
            var seen: JsonElement? = null
            while (seen == null) {
                seen = b.status().get("movingTo")?.takeIf { it is JsonPrimitive && it.content != "null" }
                if (seen == null) delay(20)
            }
            seen
        }
        assertThat(announced!!.jsonPrimitive.int).isEqualTo(newPort)
        withTimeout(10_000) { while ((runtime.state.value as? RemoteState.Running)?.port != newPort) delay(20) }
        // The old port is gone; on the new one both pages are still in the parent section.
        assertThat(runCatching { Phone(oldPort).get("/api/status") }.exceptionOrNull()).isInstanceOf(IOException::class.java)
        a.port = newPort
        b.port = newPort
        assertThat(a.get("/api/parent/settings").code).isEqualTo(200)
        assertThat(b.get("/api/parent/settings").json.setting("remote_port")).isEqualTo(JsonPrimitive(newPort))
        assertThat(b.status()["movingTo"].toString()).isEqualTo("null")
        // A port another program holds is refused before anything moves.
        ServerSocket(0, 50, loopback).use { taken ->
            val refused = a.post("/api/parent/settings", """{"key":"remote_port","value":${taken.localPort}}""")
            assertThat(refused.json["ok"]!!.jsonPrimitive.boolean).isFalse()
            assertThat(refused.json["reconnectPort"].toString()).isEqualTo("null")
        }
        assertThat(port).isEqualTo(newPort)
    }

    // ---- 9. Network drops --------------------------------------------------------------------------------------------

    @Test
    fun aNetworkDrop_nothingAnswers_thenTheTvIsBackOnItsPort_andThePageCatchesUp() = runBlocking<Unit> {
        val phone = Phone().apply { login() }
        val revBefore = phone.status().getValue("rev").jsonPrimitive.long
        val before = port
        // The TV's Wi-Fi drops: the server stops (RemoteService refreshes on every network change).
        runtime.stop()
        assertThat(runCatching { phone.get("/api/status") }.exceptionOrNull()).isInstanceOf(IOException::class.java)
        // Meanwhile something changes on the TV.
        env.behavior.setNumber(BehaviorKeys.WARNING_MINUTES, 10)
        // Back: the same port (remembered), the same pairing; the parent session did not survive the stop.
        assertThat(runtime.refresh()).isInstanceOf(RemoteState.Running::class.java)
        assertThat(port).isEqualTo(before)
        // The page's first status after the drop shows that something changed: it reads again what it shows.
        withTimeout(5_000) { while (phone.status().getValue("rev").jsonPrimitive.long == revBefore) delay(20) }
        assertThat(phone.get("/api/parent/settings").code).isEqualTo(403)
        phone.login()
        assertThat(phone.get("/api/parent/settings").json.setting("warning_minutes")).isEqualTo(JsonPrimitive(10))
    }

    // ---- 10. Two phones at once ---------------------------------------------------------------------------------------

    @Test
    fun twoPhones_seeEachOthersChanges_andAStaleChangeIsRefusedWithTheValueAsItIs() = runBlocking<Unit> {
        val a = Phone().apply { login() }
        val b = Phone().apply { login() }
        val bSaw = b.get("/api/parent/settings").json.setting("warning_minutes")
        val revB = b.status().getValue("rev").jsonPrimitive.long
        assertThat(a.post("/api/parent/settings", """{"key":"warning_minutes","value":10,"base":$bSaw}""").json["ok"]!!.jsonPrimitive.boolean).isTrue()
        // B's next poll sees the mark move, and reads the new value.
        withTimeout(5_000) { while (b.status().getValue("rev").jsonPrimitive.long == revB) delay(20) }
        assertThat(b.get("/api/parent/settings").json.setting("warning_minutes")).isEqualTo(JsonPrimitive(10))
        // A change B made over what it showed before: refused, with the value as it is now.
        val stale = b.post("/api/parent/settings", """{"key":"warning_minutes","value":15,"base":$bSaw}""")
        assertThat(stale.json["ok"]!!.jsonPrimitive.boolean).isFalse()
        assertThat(stale.json["settings"]!!.jsonObject.setting("warning_minutes")).isEqualTo(JsonPrimitive(10))
        assertThat(env.behavior.current().warningMinutes).isEqualTo(10)
        // Over the new value it goes through.
        assertThat(b.post("/api/parent/settings", """{"key":"warning_minutes","value":15,"base":10}""").json["ok"]!!.jsonPrimitive.boolean).isTrue()
        // One phone leaving the parent section leaves the other in it.
        assertThat(a.post("/api/parent/logout", null).code).isEqualTo(200)
        assertThat(a.get("/api/parent/settings").code).isEqualTo(403)
        assertThat(b.get("/api/parent/settings").code).isEqualTo(200)
    }

    // ---- Helpers ----------------------------------------------------------------------------------------------------

    private class Answer(val code: Int, val body: String, val retryAfter: Long) {
        val json: JsonObject get() = Json.parseToJsonElement(body).jsonObject
        val array: JsonArray get() = Json.parseToJsonElement(body).jsonArray
    }

    /** A phone: the pairing token and, once logged in, its own parent session. */
    private inner class Phone(var port: Int = this@PhoneLogicChecklistTest.port, private val pairing: String = token) {
        var session: String? = null

        suspend fun login(pin: String = PIN) {
            val answer = post("/api/parent/login", """{"pin":"$pin"}""")
            assertThat(answer.code).isEqualTo(200)
            session = answer.json.str("session")
        }

        suspend fun status(): JsonObject = get("/api/status").json

        suspend fun profiles(): List<JsonObject> = get("/api/parent/profiles").json.getValue("profiles").jsonArray.map { it.jsonObject }

        suspend fun get(path: String) = call("GET", path, null)

        suspend fun post(path: String, body: String?) = call("POST", path, body)

        private suspend fun call(method: String, path: String, body: String?): Answer = withContext(Dispatchers.IO) {
            val conn = URI("http://127.0.0.1:$port$path").toURL().openConnection() as HttpURLConnection
            conn.connectTimeout = 3_000
            conn.readTimeout = 20_000
            conn.requestMethod = method
            conn.setRequestProperty("Authorization", "Bearer $pairing")
            session?.let { conn.setRequestProperty(PARENT_SESSION_HEADER_NAME, it) }
            if (body != null || method == "POST") {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write((body ?: "").toByteArray()) }
            }
            try {
                val code = conn.responseCode
                val stream = if (code >= 400) conn.errorStream else conn.inputStream
                Answer(code, stream?.use { it.readBytes().decodeToString() }.orEmpty(), conn.getHeaderField("Retry-After")?.toLongOrNull() ?: 0)
            } finally {
                conn.disconnect()
            }
        }
    }

    private fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content

    /** The value of setting [key] in a settings page. */
    private fun JsonObject.setting(key: String): JsonElement = getValue("groups").jsonArray
        .flatMap { it.jsonObject.getValue("items").jsonArray }
        .map { it.jsonObject }
        .first { it.str("key") == key }
        .getValue("value")

    private fun freePort(): Int = ServerSocket(0, 50, loopback).use { it.localPort }

    private object SilentAnnouncer : Announcer {
        override val name: String? = null

        override fun register(port: Int) = Unit

        override fun unregister() = Unit
    }

    private companion object {
        const val PARENT_SESSION_HEADER_NAME = "X-Parent-Session"
    }
}
