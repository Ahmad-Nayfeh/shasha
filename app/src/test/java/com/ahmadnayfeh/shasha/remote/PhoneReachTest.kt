package com.ahmadnayfeh.shasha.remote

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import java.net.ServerSocket
import java.net.URI

/**
 * v1.2: whether the parent's phone can answer the TV ([TvPhoneReach]): paired once a phone reached the server with the
 * current token (kept across restarts, forgotten with a new token, never in the backup), connected while it polls.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class PhoneReachTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val loopback = InetAddress.getByName("127.0.0.1") as Inet4Address
    private lateinit var env: RemoteTestEnv
    private lateinit var runtime: RemoteRuntime

    @Before
    fun setUp() {
        env = remoteEnv(tmp.root)
        runtime = newRuntime()
        // A port nothing else in this JVM listens on.
        runBlocking { env.remoteSettings.rememberPort(ServerSocket(0, 50, loopback).use { it.localPort }) }
    }

    /** A runtime over the test's stores; the paired mark is written from the test's own scope. */
    private fun newRuntime() = RemoteRuntime(env.remoteSettings, env.controller, NoAnnouncer, findAddress = { loopback }, scope = env.scope)

    @After
    fun tearDown() {
        runBlocking { runtime.stop() }
        env.close()
    }

    private fun status(port: Int, token: String): Int {
        val conn = URI("http://127.0.0.1:$port/api/status").toURL().openConnection() as HttpURLConnection
        conn.connectTimeout = 5_000
        conn.readTimeout = 10_000
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("Connection", "close")
        return try {
            conn.responseCode
        } finally {
            conn.disconnect()
        }
    }

    @Test
    fun aPhoneWithTheToken_pairs_staysPairedAcrossARestart_untilANewToken() = runBlocking<Unit> {
        val reach = TvPhoneReach(env.remoteSettings, runtime)
        val port = (runtime.refresh() as RemoteState.Running).port
        assertThat(reach.isPaired()).isFalse()
        assertThat(reach.isConnected()).isFalse()

        // A wrong token pairs nothing.
        assertThat(status(port, "wrong-token-wrong-token-wrong-tok")).isEqualTo(401)
        assertThat(reach.isPaired()).isFalse()
        assertThat(reach.isConnected()).isFalse()

        assertThat(status(port, env.remoteSettings.token())).isEqualTo(200)
        withTimeout(10_000) { env.remoteSettings.phonePaired.first { it } }
        assertThat(reach.isPaired()).isTrue()
        assertThat(reach.isConnected()).isTrue()

        // The app restarts: still paired (on disk), but no page has polled this process yet.
        runtime.stop()
        val restarted = newRuntime()
        try {
            val again = TvPhoneReach(env.remoteSettings, restarted)
            restarted.refresh()
            assertThat(again.isPaired()).isTrue()
            assertThat(again.isConnected()).isFalse()

            // A new pairing token: the old phone is no longer paired, nor there.
            val fresh = restarted.regenerateToken()
            assertThat(again.isPaired()).isFalse()
            assertThat(again.isConnected()).isFalse()
            val newPort = (restarted.state.value as RemoteState.Running).port
            assertThat(status(newPort, fresh)).isEqualTo(200)
            assertThat(restarted.presence.seenWithin(PhoneReach.CONNECTED_MS)).isTrue()
            withTimeout(10_000) { env.remoteSettings.phonePaired.first { it } }
            assertThat(again.isConnected()).isTrue()
        } finally {
            restarted.stop()
        }
    }

    @Test
    fun theRemoteSwitchedOff_noPhoneCanAnswer() = runBlocking<Unit> {
        val reach = TvPhoneReach(env.remoteSettings, runtime)
        env.remoteSettings.markPhonePaired()
        assertThat(reach.isPaired()).isTrue()
        env.remoteSettings.setEnabled(false)
        assertThat(reach.isPaired()).isFalse()
        // Not running: never connected.
        assertThat(reach.isConnected()).isFalse()
    }

    @Test
    fun thePairedMark_isNeverInTheBackup() = runBlocking<Unit> {
        env.remoteSettings.markPhonePaired()
        val section = RemoteSettings.backupSection(env.remoteStore)
        assertThat(section.export().keys).doesNotContain("phone_paired")
        // A restore leaves it as it is.
        section.restore(emptyMap())
        assertThat(env.remoteSettings.phonePaired.first()).isTrue()
    }

    private object NoAnnouncer : Announcer {
        override val name: String? = null

        override fun register(port: Int) = Unit

        override fun unregister() = Unit
    }
}
