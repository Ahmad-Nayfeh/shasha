package com.ahmadnayfeh.shasha.youtube.download

import com.ahmadnayfeh.shasha.youtube.SourceErrorKind
import com.ahmadnayfeh.shasha.youtube.SourceException
import com.ahmadnayfeh.shasha.youtube.playback.GoogleVideoQuirks
import com.ahmadnayfeh.shasha.youtube.playback.GoogleVideoRequests
import com.ahmadnayfeh.shasha.youtube.playback.PlannedRequest
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Fetches a [DownloadStream] into a file, the way YouTube's own players fetch media: in byte ranges of a few megabytes,
 * shaped like [GoogleVideoQuirks.DASH] (range in the URL, request counter, the User-Agent of the client that produced the
 * URL). One long request would be throttled or refused more often.
 *
 * Resumable: a file that already holds the first n bytes continues from n. A broken connection is retried from where it
 * broke ([MAX_RETRIES] times); HTTP failures leave as [SourceException] (403 is [SourceErrorKind.BLOCKED_403]: the URL
 * has expired, extract the video again). Cancelling the coroutine cancels the request at once.
 */
class StreamDownloader(
    okHttpClient: OkHttpClient,
    private val chunkBytes: Long = DEFAULT_CHUNK_BYTES,
    private val retryDelayMs: Long = RETRY_DELAY_MS,
) {
    // The app's interceptors (e.g. a global User-Agent) would override the per-client headers googlevideo checks.
    private val client: OkHttpClient = okHttpClient.newBuilder()
        .apply {
            interceptors().clear()
            networkInterceptors().clear()
        }
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Appends [stream] to [target] (created if missing) from the file's current length, and returns its final length.
     * [onProgress] receives the bytes the file holds so far, from an IO thread.
     */
    suspend fun download(stream: DownloadStream, target: File, onProgress: (bytes: Long) -> Unit = {}): Long =
        withContext(Dispatchers.IO) {
            target.parentFile?.mkdirs()
            val total = stream.sizeBytes
            var position = if (target.exists()) target.length() else 0L
            if (total != null && position > total) {
                // More than the stream has: not a partial download of it. Start again.
                target.delete()
                position = 0L
            }
            onProgress(position)
            if (total != null && position == total) return@withContext position

            var requestNumber = 0L
            var retries = 0
            FileOutputStream(target, /* append = */ true).use { out ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val length = if (total != null) minOf(chunkBytes, total - position) else chunkBytes
                    val before = position
                    try {
                        val reachedEnd = fetchChunk(stream.url, position, length, requestNumber++) { buffer, count ->
                            out.write(buffer, 0, count)
                            position += count
                            onProgress(position)
                        }
                        retries = 0
                        // A known size ends the download when it is all there; a short chunk then is only a broken
                        // connection (the next round asks for the rest). Without a size, a short chunk is the end.
                        if (total != null) {
                            if (position >= total) break
                        } else if (reachedEnd) {
                            break
                        }
                        if (position == before) throw IOException("The server sent nothing at $position")
                    } catch (e: IOException) {
                        currentCoroutineContext().ensureActive() // cancelling the call surfaces as an IOException
                        if (++retries > MAX_RETRIES) throw e
                        delay(retryDelayMs * retries)
                    }
                }
                out.flush()
                out.fd.sync()
            }
            position
        }

    /**
     * Reads one chunk [position, position + length) and hands its bytes to [sink]. True when the file ended inside it
     * (only knowable for a stream of unknown size: a short or empty answer).
     */
    private suspend fun fetchChunk(
        url: String,
        position: Long,
        length: Long,
        requestNumber: Long,
        sink: (ByteArray, Int) -> Unit,
    ): Boolean {
        val plan = GoogleVideoRequests.plan(url, position, length, GoogleVideoQuirks.DASH) { requestNumber }
        return withCall(client.newCall(buildRequest(plan))) { response ->
            val code = response.code
            if (code == 416 && position > 0) return@withCall true // asked for a byte past the end
            if (code in 500..599) throw IOException("HTTP $code") // transient: tried again by the caller
            if (!response.isSuccessful) throw httpError(code)
            val body = response.body
            // With a Range header, a 200 instead of 206 means the server ignored it: skip to the position by hand.
            var toSkip = if (!plan.rangeInUrl && code == 200 && position != 0L) position else 0L
            val buffer = ByteArray(BUFFER_BYTES)
            var received = 0L
            body.byteStream().use { input ->
                while (received < length) {
                    val read = input.read(buffer, 0, minOf(buffer.size.toLong(), length - received + toSkip).toInt())
                    if (read < 0) break
                    if (toSkip > 0) {
                        val skipped = minOf(toSkip, read.toLong()).toInt()
                        toSkip -= skipped
                        if (read > skipped) {
                            sink(buffer.copyOfRange(skipped, read), read - skipped)
                            received += read - skipped
                        }
                    } else {
                        sink(buffer, read)
                        received += read
                    }
                }
            }
            received < length
        }
    }

    private fun buildRequest(plan: PlannedRequest): Request {
        val builder = Request.Builder().url(plan.url)
        plan.headers.forEach { (name, value) -> builder.header(name, value) }
        // Otherwise OkHttp asks for gzip and hides Content-Length.
        builder.header("Accept-Encoding", "identity")
        return if (plan.method == "POST") builder.post((plan.body ?: ByteArray(0)).toRequestBody(FORM)).build() else builder.get().build()
    }

    private fun httpError(code: Int): SourceException {
        val kind = when (code) {
            403 -> SourceErrorKind.BLOCKED_403
            429 -> SourceErrorKind.RATE_LIMITED
            404, 410 -> SourceErrorKind.CONTENT_UNAVAILABLE
            else -> SourceErrorKind.EXTRACTION_BROKEN
        }
        return SourceException(kind, "HTTP $code", "The server answered HTTP $code", null)
    }

    /** Runs [call] on IO and reads its answer with [read]; cancelling the caller cancels the call. */
    private suspend fun <T> withCall(call: Call, read: suspend (Response) -> T): T = coroutineScope {
        val canceller = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                call.cancel() // harmless once the answer was read
            }
        }
        try {
            withContext(Dispatchers.IO) { call.execute().use { response -> read(response) } }
        } finally {
            canceller.cancel()
        }
    }

    companion object {
        const val DEFAULT_CHUNK_BYTES = 4L * 1024 * 1024
        const val MAX_RETRIES = 4
        private const val RETRY_DELAY_MS = 1_000L
        private const val BUFFER_BYTES = 64 * 1024
        private val FORM = "application/x-www-form-urlencoded".toMediaType()
    }
}
