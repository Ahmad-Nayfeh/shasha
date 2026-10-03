package com.ahmadnayfeh.shasha.youtube.playback

import androidx.annotation.OptIn
import androidx.media3.common.ParserException
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import com.ahmadnayfeh.shasha.youtube.SourceErrorKind
import com.ahmadnayfeh.shasha.youtube.SourceErrorKind.BLOCKED_403
import com.ahmadnayfeh.shasha.youtube.SourceErrorKind.CONTENT_UNAVAILABLE
import com.ahmadnayfeh.shasha.youtube.SourceErrorKind.DEVICE_DECODER
import com.ahmadnayfeh.shasha.youtube.SourceErrorKind.EXTRACTION_BROKEN
import com.ahmadnayfeh.shasha.youtube.SourceErrorKind.NETWORK
import com.ahmadnayfeh.shasha.youtube.SourceErrorKind.RATE_LIMITED
import com.ahmadnayfeh.shasha.youtube.SourceException
import com.ahmadnayfeh.shasha.youtube.newpipe.HttpStatusException
import com.ahmadnayfeh.shasha.youtube.newpipe.className
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Maps a failure during playback (usually a Media3 PlaybackException) to one [SourceException].
 * The whole cause chain is inspected; the most specific finding wins: an HTTP status from YouTube,
 * then a connectivity problem, then a failure of this device's own decoder (DEVICE_DECODER: the
 * picture is what YouTube sent, the device could not decode it), and anything else means the stream
 * data or manifests are not what the player expected (EXTRACTION_BROKEN).
 */
@OptIn(UnstableApi::class)
internal object PlaybackErrorClassifier {

    private val priority = listOf(BLOCKED_403, RATE_LIMITED, CONTENT_UNAVAILABLE, NETWORK, DEVICE_DECODER, EXTRACTION_BROKEN)

    fun classify(error: Throwable): SourceException {
        var best: Pair<SourceErrorKind, Throwable>? = null
        for (t in error.causeChain()) {
            if (t is SourceException) return t
            val kind = kindOf(t) ?: continue
            // On a tie the deeper cause wins: it names the problem more precisely (UnknownHostException).
            if (best == null || priority.indexOf(kind) <= priority.indexOf(best.first)) best = kind to t
        }
        val (kind, culprit) = best ?: (EXTRACTION_BROKEN to error)
        return SourceException(kind, technicalName(culprit), culprit.message ?: error.message, error)
    }

    private fun kindOf(t: Throwable): SourceErrorKind? = when {
        t is HttpDataSource.InvalidResponseCodeException -> kindOfStatus(t.responseCode)
        t is HttpStatusException -> kindOfStatus(t.code)
        // NPE's OTF / post-live manifest creators go through our downloader, which reports 429 this way.
        t is ReCaptchaException -> RATE_LIMITED
        // ParserException is an IOException: bad data, not a bad connection.
        t is ParserException -> EXTRACTION_BROKEN
        t is UnknownHostException || t is ConnectException || t is NoRouteToHostException ||
            t is SocketException || t is SSLException || t is InterruptedIOException -> NETWORK
        t is HttpDataSource.CleartextNotPermittedException -> EXTRACTION_BROKEN
        t is HttpDataSource.HttpDataSourceException -> NETWORK
        t is PlaybackException -> when (t.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            -> NETWORK
            // The device's decoder, not YouTube: it could not start, query, or decode what it was given.
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED,
            -> DEVICE_DECODER
            else -> null
        }
        t is IOException && t.javaClass == IOException::class.java -> NETWORK
        else -> null
    }

    private fun kindOfStatus(code: Int): SourceErrorKind = when (code) {
        403 -> BLOCKED_403
        429 -> RATE_LIMITED
        404, 410 -> CONTENT_UNAVAILABLE
        else -> EXTRACTION_BROKEN
    }

    private fun technicalName(t: Throwable): String = when (t) {
        is HttpDataSource.InvalidResponseCodeException -> "HTTP ${t.responseCode}"
        is HttpStatusException -> "HTTP ${t.code}"
        is PlaybackException -> t.errorCodeName
        else -> t.className()
    }

    private fun Throwable.causeChain(): Sequence<Throwable> {
        val seen = HashSet<Throwable>()
        return generateSequence(this) { it.cause }.takeWhile { seen.add(it) }
    }
}
