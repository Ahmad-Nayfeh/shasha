package com.ahmadnayfeh.shasha.remote

import kotlinx.coroutines.flow.first

/**
 * Whether the parent's phone can answer the TV (v1.1.1). A video that only the phone could end early waits for the phone's
 * «تأكيد» ([PhoneConfirmations]), and the adult's way out on the TV may only be turned off while a phone is paired.
 */
interface PhoneReach {
    /** The phone remote is on and a phone has reached it with the current pairing token (it scanned the QR code). */
    suspend fun isPaired(): Boolean

    /** A paired phone reached the TV in the last [CONNECTED_MS]: its page is open (it polls every 2 s while it is). */
    fun isConnected(): Boolean

    companion object {
        /** "~60 s" (owner decision): a page that polled within it is there to answer. */
        const val CONNECTED_MS = 60_000L
    }
}

/** [PhoneReach] over the phone remote's settings and its server ([RemoteRuntime]). */
class TvPhoneReach(private val settings: RemoteSettings, private val runtime: RemoteRuntime) : PhoneReach {
    override suspend fun isPaired(): Boolean = settings.enabled.first() && settings.phonePaired.first()

    override fun isConnected(): Boolean =
        runtime.state.value is RemoteState.Running && runtime.presence.seenWithin(PhoneReach.CONNECTED_MS)
}
