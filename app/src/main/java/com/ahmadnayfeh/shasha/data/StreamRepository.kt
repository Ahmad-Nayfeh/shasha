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
 * Stream extraction with a short in-memory cache, so opening a video that was just checked (the
 * diagnostics screen checks all of its videos) starts playing without a second extraction.
 *
 * Only successes are cached, and only for a few minutes: googlevideo URLs expire after some hours
 * and a failure should always be retried for real. Concurrent calls for the same video share one
 * extraction.
 */
class StreamRepository(
    private val source: VideoSource,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxAgeMs: Long = 10 * 60_000L,
) {
    private val scope = CoroutineScope(SupervisorJob())
    private val mutex = Mutex()
    private val cache = HashMap<String, Pair<Long, StreamDetails>>()
    private val inFlight = HashMap<String, Deferred<StreamDetails>>()

    /** @throws SourceException for every failure (unexpected ones are wrapped as EXTRACTION_BROKEN). */
    suspend fun stream(videoId: String): StreamDetails {
        val job = mutex.withLock {
            cache[videoId]?.let { (at, details) -> if (clock() - at <= maxAgeMs) return details else cache.remove(videoId) }
            inFlight.getOrPut(videoId) {
                scope.async {
                    try {
                        source.stream(videoId).also { details -> mutex.withLock { cache[videoId] = clock() to details } }
                    } finally {
                        mutex.withLock { inFlight.remove(videoId) }
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
    suspend fun cached(videoId: String): StreamDetails? = mutex.withLock {
        cache[videoId]?.takeIf { (at, _) -> clock() - at <= maxAgeMs }?.second
    }
}

/** Any failure becomes a [SourceException], so the error screen can always show something. */
fun Throwable.asSourceException(): SourceException = this as? SourceException
    ?: SourceException(SourceErrorKind.EXTRACTION_BROKEN, javaClass.simpleName.ifEmpty { javaClass.name }, message, this)
