package com.ahmadnayfeh.shasha.data

/**
 * A small in-memory cache: at most [maxEntries] values (the least recently used leave first), each
 * for at most [maxAgeMs]. Expired values are dropped on every access, so nothing outlives its use.
 * Not thread-safe: callers hold their own lock.
 */
internal class ExpiringCache<K : Any, V : Any>(
    private val maxEntries: Int,
    private val maxAgeMs: Long,
    private val clock: () -> Long,
) {
    private val entries = object : LinkedHashMap<K, Pair<Long, V>>(maxEntries + 1, 0.75f, /* accessOrder = */ true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, Pair<Long, V>>): Boolean = size > maxEntries
    }

    val size: Int get() = entries.size

    operator fun get(key: K): V? {
        dropExpired()
        return entries[key]?.second
    }

    operator fun set(key: K, value: V) {
        dropExpired()
        entries[key] = clock() to value
    }

    fun remove(key: K) {
        entries.remove(key)
    }

    private fun dropExpired() {
        val now = clock()
        entries.values.removeAll { (storedAt, _) -> now - storedAt > maxAgeMs }
    }
}
