package com.ahmadnayfeh.shasha.data

import com.ahmadnayfeh.shasha.youtube.SourceErrorKind
import com.ahmadnayfeh.shasha.youtube.SourceException
import com.ahmadnayfeh.shasha.youtube.StreamDetails
import com.ahmadnayfeh.shasha.youtube.VideoSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Stream extraction with a short in-memory cache, so opening a video that was just checked (the policy looks a
 * video up before it plays) starts playing without a second extraction.
 *
 * Only successes are cached, only the last [MAX_CACHED] (LRU), and only for 90 minutes: googlevideo URLs
 * expire after some hours (a refused URL is re-extracted by the player), a failure should always be retried for real, and each entry holds a whole
 * extraction (every stream URL, related items, storyboards) that a TV's small heap should not keep.
 * Concurrent calls for the same video (and the same `allowLowQuality`) share one extraction.
 *
 * A low-quality result ([StreamDetails.lowQualityHeight], only ever returned to a call that allowed it) is
 * never handed to a call that did not: that one extracts for itself (and gets LOW_QUALITY_ONLY).
 */
class StreamRepository(
    private val source: VideoSource,
    private val clock: () -> Long = System::currentTimeMillis,
    maxAgeMs: Long = 90 * 60_000L,
) {
    private val scope = CoroutineScope(SupervisorJob())
    private val mutex = Mutex()
    private val cache = ExpiringCache<String, StreamDetails>(MAX_CACHED, maxAgeMs, clock)
    private val inFlight = HashMap<Pair<String, Boolean>, Deferred<StreamDetails>>()

    /**
     * [allowLowQuality]: see [VideoSource.stream] (a child profile's player, the policy's look-up, the adult's
     * «شغّل بجودة 360p على أي حال»).
     *
     * @throws SourceException for every failure (unexpected ones are wrapped as EXTRACTION_BROKEN).
     */
    suspend fun stream(videoId: String, allowLowQuality: Boolean = false): StreamDetails {
        val key = videoId to allowLowQuality
        val job = mutex.withLock {
            cache[videoId]?.takeIf { it.usableFor(allowLowQuality) }?.let { return it }
            inFlight.getOrPut(key) {
                scope.async {
                    try {
                        source.stream(videoId, allowLowQuality).also { details -> mutex.withLock { cache[videoId] = details } }
                    } finally {
                        mutex.withLock { inFlight.remove(key) }
                    }
                }
            }
        }
        return try {
            job.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw e.asSourceException()
        }
    }

    /** Drops a cached stream (e.g. its URLs were refused during playback). */
    suspend fun invalidate(videoId: String) {
        mutex.withLock { cache.remove(videoId) }
    }

    /** Takes the cached stream if it is still fresh, without any network work. */
    suspend fun cached(videoId: String, allowLowQuality: Boolean = false): StreamDetails? =
        mutex.withLock { cache[videoId]?.takeIf { it.usableFor(allowLowQuality) } }

    private fun StreamDetails.usableFor(allowLowQuality: Boolean) = allowLowQuality || lowQualityHeight == null

    private companion object {
        /** The last few videos looked up, checked and then played. */
        const val MAX_CACHED = 16
    }
}

/** Any failure becomes a [SourceException], so the error screen can always show something. */
fun Throwable.asSourceException(): SourceException = this as? SourceException
    ?: SourceException(SourceErrorKind.EXTRACTION_BROKEN, javaClass.simpleName.ifEmpty { javaClass.name }, message, this)
