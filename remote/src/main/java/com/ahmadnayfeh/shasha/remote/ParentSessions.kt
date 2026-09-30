package com.ahmadnayfeh.shasha.remote

/**
 * In-memory parent sessions: sliding [ttlMillis] expiry, at most [maxSessions] (the least recently
 * used one is dropped). Sessions die with the server; nothing is persisted.
 *
 * [nowMillis] must be monotonic (wall-clock jumps must not expire or extend sessions).
 * Keys are SHA-256 digests of the session ids, so map lookups do not compare secrets directly.
 */
internal class ParentSessions(
    private val nowMillis: () -> Long,
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val maxSessions: Int = DEFAULT_MAX_SESSIONS,
) {
    private val lastSeen = LinkedHashMap<Key, Long>(16, 0.75f, true) // access order = LRU first

    @Synchronized
    fun create(): String {
        purgeExpired()
        while (lastSeen.size >= maxSessions) lastSeen.remove(lastSeen.keys.first())
        val id = RemoteTokens.randomToken(32)
        lastSeen[Key.of(id)] = nowMillis()
        return id
    }

    /** True if [id] is a live session; also extends it. */
    @Synchronized
    fun touch(id: String): Boolean {
        purgeExpired()
        val key = Key.of(id)
        if (!lastSeen.containsKey(key)) return false
        lastSeen[key] = nowMillis()
        return true
    }

    @Synchronized
    fun remove(id: String) {
        lastSeen.remove(Key.of(id))
    }

    @Synchronized
    fun clear() = lastSeen.clear()

    @Synchronized
    fun size(): Int {
        purgeExpired()
        return lastSeen.size
    }

    private fun purgeExpired() {
        val now = nowMillis()
        lastSeen.entries.removeAll { now - it.value >= ttlMillis }
    }

    private class Key private constructor(private val digest: ByteArray) {
        override fun equals(other: Any?) = other is Key && digest.contentEquals(other.digest)
        override fun hashCode() = digest.contentHashCode()

        companion object {
            fun of(id: String) = Key(RemoteTokens.sha256(id))
        }
    }

    companion object {
        const val DEFAULT_TTL_MILLIS = 30 * 60 * 1000L
        const val DEFAULT_MAX_SESSIONS = 8
    }
}
