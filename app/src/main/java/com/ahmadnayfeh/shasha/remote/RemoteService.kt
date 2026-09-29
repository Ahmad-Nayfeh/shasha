package com.ahmadnayfeh.shasha.remote

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.ahmadnayfeh.shasha.R
import com.ahmadnayfeh.shasha.appContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * SPEC §5: the phone remote's LAN server, kept alive by a foreground service of type connectedDevice while
 * «التحكم من الجوال» is on and the first start is done (the parent PIN exists). It binds only to the TV's
 * LAN address ([RemoteRuntime]) and binds again whenever the network changes (a new address, Wi-Fi back
 * after a drop), and stops itself when switched off. Its notification is quiet.
 *
 * Android 14+ asks a connectedDevice service for one of a few permissions: the manifest declares
 * CHANGE_WIFI_MULTICAST_STATE, which the NSD announcement's multicast lock uses anyway.
 */
@OptIn(FlowPreview::class)
class RemoteService : LifecycleService() {
    private var multicast: WifiManager.MulticastLock? = null

    /** Set once created (off the main thread: the controller behind it reads the current profile from disk). */
    @Volatile
    private var runtime: RemoteRuntime? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        // First, even to stop at once: a service started with startForegroundService must call startForeground.
        startInForeground()
        val container = appContainer
        lifecycleScope.launch {
            val remote = withContext(Dispatchers.IO) { container.remote }
            runtime = remote
            combine(container.remoteSettings.enabled, container.parentPin.isSet) { on, pinSet -> on && pinSet }
                .distinctUntilChanged()
                .collectLatest { wanted ->
                    if (!wanted) {
                        remote.stop()
                        releaseMulticast()
                        stopSelf()
                        return@collectLatest
                    }
                    holdMulticast()
                    refresh(remote)
                    // Rebind on every network change (settled for a moment: DHCP renewals come in bursts), and look
                    // again now and then: a missed callback, a port that became free, the NSD name.
                    merge(networkChanges().debounce(SETTLE_MS), ticks()).collect { refresh(remote) }
                }
        }
    }

    private suspend fun refresh(runtime: RemoteRuntime) {
        try {
            runtime.refresh()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The next network change or tick tries again.
            Log.w(TAG, "Could not start the phone remote", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        releaseMulticast()
        // The server must not outlive its service; stopping blocks (≤ 1.5 s), so not on the main thread.
        runtime?.let { started -> appContainer.appScope.launch { withContext(NonCancellable) { started.stop() } } }
        super.onDestroy()
    }

    /** Wi-Fi and Ethernet networks coming, going or changing addresses. */
    private fun networkChanges(): Flow<Unit> = callbackFlow {
        val connectivity = getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(Unit)
            }

            override fun onLost(network: Network) {
                trySend(Unit)
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                trySend(Unit)
            }
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            // A home network without internet still links the phone and the TV.
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        try {
            connectivity.registerNetworkCallback(request, callback)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Cannot follow network changes", e)
        }
        awaitClose { runCatching { connectivity.unregisterNetworkCallback(callback) } }
    }

    private fun ticks(): Flow<Unit> = flow {
        while (true) {
            delay(RECHECK_MS)
            emit(Unit)
        }
    }

    /** Some Wi-Fi drivers drop multicast packets unless an app asks: NSD's replies need them. */
    private fun holdMulticast() {
        if (multicast?.isHeld == true) return
        try {
            val wifi = applicationContext.getSystemService(WifiManager::class.java) ?: return
            multicast = wifi.createMulticastLock(TAG).apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "No multicast lock: NSD may not be seen on some networks", e)
        }
    }

    private fun releaseMulticast() {
        runCatching { multicast?.takeIf { it.isHeld }?.release() }
        multicast = null
    }

    private fun startInForeground() {
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.remote_channel_name), NotificationManager.IMPORTANCE_MIN).apply {
                setShowBadge(false)
            },
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(getString(R.string.remote_notification_title))
            .setContentText(getString(R.string.remote_notification_text))
            .setOngoing(true)
            .setShowWhen(false)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        // The type is required from Android 14; below it the manifest's type is only informative.
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    companion object {
        private const val TAG = "RemoteService"
        private const val CHANNEL_ID = "remote"
        private const val NOTIFICATION_ID = 5
        private const val SETTLE_MS = 1_500L
        private const val RECHECK_MS = 60_000L

        /** The service runs in this process now. */
        @Volatile
        var running = false
            private set

        /**
         * Starts the phone remote when it is wanted (switched on, and the parent PIN exists): from the app's
         * start, its activity, after a reboot or an update, and when switched on. Safe to call often. A start the
         * system refuses from the background is logged; the next call tries again. [done] runs once decided.
         */
        fun startIfWanted(context: Context, done: () -> Unit = {}) {
            val app = context.applicationContext
            val container = app.appContainer
            container.appScope.launch {
                try {
                    val wanted = runCatching { container.remoteSettings.enabled.first() && container.parentPin.isSet() }
                        .onFailure { Log.w(TAG, "Cannot read the remote's settings", it) }
                        .getOrDefault(false)
                    if (wanted) start(app)
                } finally {
                    done()
                }
            }
        }

        private fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, RemoteService::class.java))
            } catch (e: RuntimeException) {
                // ForegroundServiceStartNotAllowedException (API 31+, from the background) or a SecurityException.
                Log.w(TAG, "Could not start the phone remote", e)
            }
        }
    }
}
