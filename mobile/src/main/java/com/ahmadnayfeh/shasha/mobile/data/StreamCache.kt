package com.ahmadnayfeh.shasha.mobile.data

import com.ahmadnayfeh.shasha.youtube.StreamDetails
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Semaphore

/**
 * Resolved streams by video id, shared by the whole app: a video the viewer pressed (or a Short just ahead) is looked up once, and
 * whoever plays it later gets the answer at once. Entries live [ttlMs] (the streams' addresses expire after some hours), at most
 * [maxEntries] are kept (least recently used out), and asking for an id whose lookup is under way joins that lookup. A failure
 * is never kept. Lookups run in [scope], so a caller that goes away does not cancel what another one waits for.
 *
 * [prefetch] is for guesses: at most [maxPrefetch] run at once and the rest are dropped, not queued (a fast scroll must not flood
 * the network); [get] always runs.
 */
class StreamCache(
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    maxPrefetch: Int = DEFAULT_MAX_PREFETCH,
    private val loader: suspend (String) -> StreamDetails,
) {
    private class Entry(val atMs: Long, val prefetchOnly: Boolean) {
        lateinit var deferred: Deferred<StreamDetails>
    }

    private val lock = Any()
    private val entries = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) = size > maxEntries
    }
    private val gate = Semaphore(maxPrefetch)

    /** [videoId]'s streams: kept, under way, or looked up now. */
    suspend fun get(videoId: String): StreamDetails = entry(videoId, prefetch = false)!!.deferred.await()

    /** Starts looking [videoId] up when nobody has and a slot is free. Never throws. */
    fun prefetch(videoId: String) {
        entry(videoId, prefetch = true)
    }

    /** Gives up a lookup [prefetch] started that nobody has asked for (the card scrolled away). */
    fun cancelPrefetch(videoId: String) {
        synchronized(lock) {
            val e = entries[videoId] ?: return
            if (e.prefetchOnly && !e.deferred.isCompleted) {
                entries.remove(videoId)
                e.deferred.cancel()
            }
        }
    }

    /** Forgets [videoId] (its addresses stopped working: the next [get] asks again). */
    fun invalidate(videoId: String) {
        val old = synchronized(lock) { entries.remove(videoId) }
        if (old != null && !old.deferred.isCompleted) old.deferred.cancel()
    }

    fun clear() {
        val old = synchronized(lock) { entries.values.toList().also { entries.clear() } }
        old.forEach { if (!it.deferred.isCompleted) it.deferred.cancel() }
    }

    /** True when [videoId] has a good answer (or one under way). */
    fun contains(videoId: String): Boolean = synchronized(lock) { fresh(entries[videoId]) }

    private fun fresh(e: Entry?) = e != null && clock() - e.atMs <= ttlMs

    private fun entry(videoId: String, prefetch: Boolean): Entry? = synchronized(lock) {
        val existing = entries[videoId]
        if (existing != null && fresh(existing)) return existing
        existing?.let {
            entries.remove(videoId)
            it.deferred.cancel()
        }
        if (prefetch && !gate.tryAcquire()) return null
        val e = Entry(clock(), prefetchOnly = prefetch)
        e.deferred = scope.async(start = CoroutineStart.LAZY) {
            try {
                loader(videoId)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                forget(videoId, e)
                throw t
            } finally {
                if (prefetch) gate.release()
            }
        }
        entries[videoId] = e
        e.deferred.start()
        e
    }

    private fun forget(videoId: String, e: Entry) {
        synchronized(lock) { if (entries[videoId] === e) entries.remove(videoId) }
    }

    companion object {
        const val DEFAULT_TTL_MS = 90 * 60_000L
        const val DEFAULT_MAX_ENTRIES = 40
        const val DEFAULT_MAX_PREFETCH = 2
    }
}
