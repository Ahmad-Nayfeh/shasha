package com.ahmadnayfeh.shasha.remote

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
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

/** The pairing token and how the server is bound ([RemoteRuntime], [RemoteSettings]), on 127.0.0.1. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class RemoteRuntimeTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val loopback = InetAddress.getByName("127.0.0.1") as Inet4Address
    private lateinit var env: RemoteTestEnv
    private val announcer = FakeAnnouncer()

    /** Where "the LAN" is: null = no network. */
    @Volatile private var address: Inet4Address? = loopback
    private lateinit var runtime: RemoteRuntime

    @Before
    fun setUp() {
        env = remoteEnv(tmp.root)
        runtime = RemoteRuntime(env.remoteSettings, env.controller, announcer, findAddress = { address })
    }

    @After
    fun tearDown() {
        runBlocking { runtime.stop() }
        env.close()
    }

    private fun status(port: Int, token: String?): Int {
        val conn = URI("http://127.0.0.1:$port/api/status").toURL().openConnection() as HttpURLConnection
        conn.connectTimeout = 5_000
        conn.readTimeout = 10_000
        token?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
        return try {
            conn.responseCode
        } finally {
            conn.disconnect()
        }
    }

    @Test
    fun thePairingToken_is32UrlSafeRandomCharacters_keptAcrossRestarts() = runBlocking<Unit> {
        val token = env.remoteSettings.token()
        assertThat(token).matches("[A-Za-z0-9_-]{32,}")
        assertThat(env.remoteSettings.token()).isEqualTo(token)
        // Another process reading the same file gets the same token.
        val reopened = RemoteSettings(env.stores.restart(RemoteSettings.FILE_NAME))
        assertThat(reopened.token()).isEqualTo(token)
        assertThat((1..50).map { reopened.newToken() }.toSet()).hasSize(50)
    }

    @Test
    fun regeneratingTheToken_locksEveryPairedPhoneOut_atOnce() = runBlocking<Unit> {
        val running = runtime.refresh() as RemoteState.Running
        assertThat(running.address).isEqualTo(loopback)
        val old = runtime.pairingToken.value!!
        assertThat(runtime.pairingUrl()).isEqualTo("http://127.0.0.1:${running.port}/?t=$old")
        assertThat(status(running.port, old)).isEqualTo(200)

        val fresh = runtime.regenerateToken()
        assertThat(fresh).isNotEqualTo(old)
        assertThat(fresh).matches("[A-Za-z0-9_-]{32,}")
        assertThat(status(running.port, old)).isEqualTo(401)
        assertThat(status(running.port, fresh)).isEqualTo(200)
        assertThat(runtime.pairingUrl()).endsWith("?t=$fresh")
        // Stored: a restart keeps the new one.
        assertThat(env.remoteSettings.token()).isEqualTo(fresh)
    }

    @Test
    fun aTakenPort_fallsBackToAFreeOne_andRemembersIt() = runBlocking<Unit> {
        ServerSocket(0, 50, loopback).use { taken ->
            env.remoteSettings.rememberPort(taken.localPort)
            val running = runtime.refresh() as RemoteState.Running
            assertThat(running.port).isNotEqualTo(taken.localPort)
            assertThat(env.remoteSettings.preferredPort()).isEqualTo(running.port)
            assertThat(announcer.registered).containsExactly(running.port)
            assertThat(status(running.port, env.remoteSettings.token())).isEqualTo(200)
        }
    }

    @Test
    fun theNetwork_decidesWhereItListens() = runBlocking<Unit> {
        val first = runtime.refresh() as RemoteState.Running
        // Nothing changed: the same binding (a network callback that changed nothing for us).
        assertThat(runtime.refresh()).isEqualTo(first.copy(serviceName = FakeAnnouncer.NAME))
        assertThat(announcer.registered).hasSize(1)

        address = null
        assertThat(runtime.refresh()).isEqualTo(RemoteState.NoNetwork)
        assertThat(runtime.server.isRunning).isFalse()
        assertThat(announcer.unregistered).isAtLeast(1)

        address = loopback
        assertThat(runtime.refresh()).isInstanceOf(RemoteState.Running::class.java)
        runtime.stop()
        assertThat(runtime.state.value).isEqualTo(RemoteState.Stopped)
        assertThat(runtime.server.isRunning).isFalse()
    }
}

class FakeAnnouncer : Announcer {
    val registered = mutableListOf<Int>()
    var unregistered = 0

    @Volatile
    override var name: String? = null
        private set

    override fun register(port: Int) {
        registered += port
        name = NAME
    }

    override fun unregister() {
        unregistered++
        name = null
    }

    companion object {
        const val NAME = "شاشة"
    }
}
