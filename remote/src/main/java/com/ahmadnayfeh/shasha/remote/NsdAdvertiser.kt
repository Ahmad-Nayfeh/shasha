package com.ahmadnayfeh.shasha.remote

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

/**
 * Announces the remote page on the LAN as a DNS-SD `_http._tcp.` service. Best effort only:
 * phone browsers rarely resolve mDNS, which is why the TV shows a QR code as the main way in.
 * Never throws; failures are logged. Never put the pairing token in the announcement.
 */
class NsdAdvertiser(context: Context) {
    private val nsd: NsdManager? = context.applicationContext.getSystemService(NsdManager::class.java)
    private var listener: NsdManager.RegistrationListener? = null

    /** The name actually announced (NSD appends " (2)" on a clash), null until registered. */
    @Volatile
    var registeredName: String? = null
        private set

    @Synchronized
    fun register(port: Int, serviceName: String = SERVICE_NAME) {
        unregister()
        val manager = nsd ?: return
        val info = NsdServiceInfo().apply {
            this.serviceName = serviceName
            serviceType = SERVICE_TYPE
            this.port = port
        }
        val callbacks = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                registeredName = info.serviceName
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "NSD registration failed: $errorCode")
                forget(this)
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "NSD unregistration failed: $errorCode")
            }
        }
        try {
            manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, callbacks)
            listener = callbacks
        } catch (e: RuntimeException) { // IllegalArgumentException, SecurityException…
            Log.w(TAG, "NSD registration refused", e)
        }
    }

    @Synchronized
    fun unregister() {
        val current = listener ?: return
        listener = null
        registeredName = null
        try {
            nsd?.unregisterService(current)
        } catch (e: RuntimeException) { // "listener not registered" after a failed registration
            Log.w(TAG, "NSD unregister refused", e)
        }
    }

    @Synchronized
    private fun forget(failed: NsdManager.RegistrationListener) {
        if (listener === failed) {
            listener = null
            registeredName = null
        }
    }

    companion object {
        const val SERVICE_TYPE = "_http._tcp."
        const val SERVICE_NAME = "شاشة"
        private const val TAG = "NsdAdvertiser"
    }
}
