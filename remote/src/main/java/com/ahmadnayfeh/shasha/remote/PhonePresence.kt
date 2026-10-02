package com.ahmadnayfeh.shasha.remote

/**
 * When a paired phone last reached the server: any request that carried the pairing token (the page polls the status
 * every 2 s while it is open on a phone). The TV reads it to know whether a phone can answer now (v1.2: a video that
 * only the phone could end must first be confirmed from it). Memory only; [nowMillis] is monotonic.
 *
 * [onSeen] runs on the server's threads for every such request (the TV remembers that a phone paired with the token).
 */
class PhonePresence internal constructor(private val nowMillis: () -> Long) {
    @Volatile
    private var lastSeen: Long? = null

    @Volatile
    var onSeen: () -> Unit = {}

    internal fun seen() {
        lastSeen = nowMillis()
        runCatching { onSeen() }
    }

    /** Milliseconds since a paired phone's last request; null when none came since the server was created (or [forget]). */
    fun sinceLastMillis(): Long? = lastSeen?.let { (nowMillis() - it).coerceAtLeast(0) }

    /** A paired phone reached the server within the last [millis]. */
    fun seenWithin(millis: Long): Boolean = sinceLastMillis()?.let { it <= millis } ?: false

    /** The phones of the old token are no longer paired (a new token): none was seen. */
    fun forget() {
        lastSeen = null
    }
}
