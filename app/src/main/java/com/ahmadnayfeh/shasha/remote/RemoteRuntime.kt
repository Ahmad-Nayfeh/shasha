package com.ahmadnayfeh.shasha.remote

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.BindException
import java.net.Inet4Address

/** Where the phone remote stands, for «التحكم من الجوال» and the first start's last step. */
sealed interface RemoteState {
    /** Not running: switched off, or the first start is not done yet. */
    data object Stopped : RemoteState

    data object Starting : RemoteState

    /** The TV has no address on a home network (no Wi-Fi or cable): nothing to listen on. */
    data object NoNetwork : RemoteState

    /** Listening on [address]:[port]; [serviceName] is the NSD name, once announced. */
    data class Running(val address: Inet4Address, val port: Int, val serviceName: String?) : RemoteState

    /** No port could be bound (every candidate taken, or the address vanished meanwhile). */
    data object Failed : RemoteState
}

/** The NSD announcement ([NsdAdvertiser] on the TV; tests use a fake). */
interface Announcer {
    fun register(port: Int)

    fun unregister()

    /** The name actually announced (NSD may add " (2)"), null until registered. */
    val name: String?
}

class NsdAnnouncer(private val advertiser: NsdAdvertiser) : Announcer {
    override fun register(port: Int) = advertiser.register(port)

    override fun unregister() = advertiser.unregister()

    override val name: String? get() = advertiser.registeredName
}

/**
 * The phone remote's server (SPEC §5) and how it is bound: ONLY to the TV's LAN IPv4 ([findAddress]:
 * [LanAddress.find]), on the port it had last time ([RemoteSettings.preferredPort], 8765 at first) or the
 * next free one after 8765, announced over NSD. [RemoteService] drives it: [refresh] when it starts and on
 * every network change (a new address means a new binding, and a new QR code), [stop] when it stops.
 *
 * The pairing token is read on every request from memory ([token]), so [regenerateToken] locks out every
 * phone at once. The server is created once per process: its PIN brake outlives stop/start (and [phonePin]'s,
 * saved on disk, outlives the process too).
 */
class RemoteRuntime(
    private val settings: RemoteSettings,
    controller: RemoteController,
    private val announcer: Announcer,
    private val findAddress: () -> Inet4Address? = LanAddress::find,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** The phone's PIN count, forgotten with the phones that guessed ([regenerateToken]). */
    private val phonePin: PhonePin? = null,
) {
    @Volatile
    private var token: String = ""

    private val _token = MutableStateFlow<String?>(null)

    /** The pairing token of the QR code (null until read). */
    val pairingToken: StateFlow<String?> = _token.asStateFlow()

    val server = RemoteServer(controller) { token }.apply {
        onError = { Log.w(TAG, "Remote request failed", it) }
    }

    private val _state = MutableStateFlow<RemoteState>(RemoteState.Stopped)
    val state: StateFlow<RemoteState> = _state.asStateFlow()

    private val binding = Mutex()
    private var boundAddress: Inet4Address? = null

    /** The pairing link of the QR code, while running. */
    fun pairingUrl(): String? {
        val running = _state.value as? RemoteState.Running ?: return null
        val t = _token.value ?: return null
        return RemoteUrls.pairingUrl(running.address, running.port, t)
    }

    /**
     * Makes sure the server listens on the current LAN address: starts it, moves it to a new address, or
     * stops it when there is none. Nothing changes while it already listens where it should. Blocking work
     * (binding) runs on [io].
     */
    suspend fun refresh(): RemoteState = binding.withLock {
        withContext(io) {
            loadToken()
            val address = findAddress()
            val running = _state.value as? RemoteState.Running
            when {
                address == null -> {
                    stopLocked()
                    _state.value = RemoteState.NoNetwork
                }
                running != null && server.isRunning && address == boundAddress -> {
                    // Only the NSD name may have come in since.
                    _state.value = running.copy(serviceName = announcer.name)
                }
                else -> {
                    stopLocked()
                    _state.value = RemoteState.Starting
                    _state.value = bind(address)
                }
            }
            _state.value
        }
    }

    /** Stops listening and the announcement. Idempotent. */
    suspend fun stop() = binding.withLock {
        withContext(io) {
            stopLocked()
            _state.value = RemoteState.Stopped
        }
    }

    /**
     * A new pairing token (Settings → «إعادة توليد الرمز», behind the parent PIN): the old QR code and every paired
     * phone stop working now. The wrong PINs counted so far are forgotten with them: every paired phone shares one
     * PIN count, so a phone that guessed (a child's) closes the parent section on the others until this.
     */
    suspend fun regenerateToken(): String = binding.withLock {
        // Under the lock: a first refresh reading the stored token meanwhile never puts the old one back.
        val fresh = settings.regenerateToken()
        token = fresh
        _token.value = fresh
        server.revokeParentSessions()
        server.forgetPinFailures()
        try {
            phonePin?.forget()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The new token is what matters; the old count then runs out by itself.
            Log.w(TAG, "Could not forget the phone's wrong PINs", e)
        }
        fresh
    }

    /** Logs every phone out of the parent section (the parent PIN changed, a backup was restored). */
    fun revokeParentSessions() = server.revokeParentSessions()

    private suspend fun loadToken() {
        if (token.isNotEmpty()) return
        val stored = settings.token()
        token = stored
        _token.value = stored
    }

    /** Inside [binding], on [io]. */
    private suspend fun bind(address: Inet4Address): RemoteState {
        val preferred = settings.preferredPort()
        val candidates = (listOf(preferred) + (RemoteSettings.DEFAULT_PORT until RemoteSettings.DEFAULT_PORT + FALLBACK_PORTS)).distinct() + 0
        for (port in candidates) {
            val bound = try {
                server.start(address, port)
            } catch (e: BindException) {
                // Taken, or the address is gone already (the next network change binds again).
                Log.i(TAG, "Cannot listen on ${address.hostAddress}:$port: ${e.message}")
                continue
            } catch (e: IOException) {
                Log.w(TAG, "Could not start the remote server on ${address.hostAddress}:$port", e)
                return RemoteState.Failed
            } catch (e: RuntimeException) {
                Log.w(TAG, "Could not start the remote server on ${address.hostAddress}:$port", e)
                return RemoteState.Failed
            }
            boundAddress = address
            if (bound != preferred) settings.rememberPort(bound)
            announcer.register(bound)
            Log.i(TAG, "Listening on ${address.hostAddress}:$bound")
            return RemoteState.Running(address, bound, announcer.name)
        }
        return RemoteState.Failed
    }

    /** Inside [binding], on [io]. */
    private fun stopLocked() {
        announcer.unregister()
        server.stop()
        boundAddress = null
    }

    private companion object {
        const val TAG = "RemoteRuntime"

        /** 8765 to 8774 before any free port: a saved link most likely still matches. */
        const val FALLBACK_PORTS = 10
    }
}
