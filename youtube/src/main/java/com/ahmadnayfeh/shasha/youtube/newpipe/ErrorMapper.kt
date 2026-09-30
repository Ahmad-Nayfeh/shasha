package com.ahmadnayfeh.shasha.youtube.newpipe

import com.ahmadnayfeh.shasha.youtube.SourceErrorKind
import com.ahmadnayfeh.shasha.youtube.SourceErrorKind.ACCOUNT_TERMINATED
import com.ahmadnayfeh.shasha.youtube.SourceErrorKind.AGE_RESTRICTED
import com.ahmadnayfeh.shasha.youtube.SourceErrorKind.BLOCKED_403
import com.ahmadnayfeh.shasha.youtube.SourceErrorKind.BOT_CHECK
import com.ahmadnayfeh.shasha.youtube.SourceErrorKind.CONTENT_UNAVAILABLE
import com.ahmadnayfeh.shasha.youtube.SourceErrorKind.EXTRACTION_BROKEN
import com.ahmadnayfeh.shasha.youtube.SourceErrorKind.GEO_RESTRICTED
import com.ahmadnayfeh.shasha.youtube.SourceErrorKind.NETWORK
import com.ahmadnayfeh.shasha.youtube.SourceErrorKind.PAID
import com.ahmadnayfeh.shasha.youtube.SourceErrorKind.PRIVATE
import com.ahmadnayfeh.shasha.youtube.SourceErrorKind.RATE_LIMITED
import com.ahmadnayfeh.shasha.youtube.SourceException
import org.schabi.newpipe.extractor.exceptions.AccountTerminatedException
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ContentNotSupportedException
import org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException
import org.schabi.newpipe.extractor.exceptions.PaidContentException
import org.schabi.newpipe.extractor.exceptions.PrivateContentException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException
import org.schabi.newpipe.extractor.exceptions.SoundCloudGoPlusContentException
import org.schabi.newpipe.extractor.exceptions.UnsupportedContentInCountryException
import org.schabi.newpipe.extractor.exceptions.YoutubeMusicPremiumContentException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Maps any failure (NewPipeExtractor, IO, HTTP) to one [SourceException].
 *
 * The whole cause chain is inspected because the extractor often wraps the real reason
 * (e.g. a ParsingException around an UnknownHostException). The most specific finding wins.
 */
internal object ErrorMapper {

    /** Lower index = more specific = wins. */
    private val priority = listOf(
        BOT_CHECK, RATE_LIMITED, BLOCKED_403, ACCOUNT_TERMINATED, PRIVATE, AGE_RESTRICTED,
        GEO_RESTRICTED, PAID, NETWORK, CONTENT_UNAVAILABLE, EXTRACTION_BROKEN,
    )

    // "Sign in to confirm you're not a bot" (also with a curly apostrophe), plus the Arabic wording in
    // case YouTube localises the reason (we send hl=ar). Not "sign in to confirm": that also starts
    // the age-check message.
    private val botCheckMarkers = listOf("not a bot", "روبوت")

    /**
     * @param httpBlockedStatus 403 when YouTube answered 403 during the failed operation
     * (see [NewPipeDownloader.blockedStatusSinceTrace]).
     */
    fun map(error: Throwable, httpBlockedStatus: Int? = null): SourceException {
        if (error is SourceException) return error

        var best: Pair<SourceErrorKind, Throwable>? = null
        for (t in error.causeChain()) {
            val kind = classify(t) ?: continue
            if (best == null || priority.indexOf(kind) < priority.indexOf(best.first)) best = kind to t
        }
        val (kind, culprit) = best ?: (EXTRACTION_BROKEN to error)

        // A 403 from YouTube explains a vague "content unavailable" / parsing failure better.
        if (httpBlockedStatus == 403 && (kind == EXTRACTION_BROKEN || isGenericUnavailable(kind, culprit))) {
            return SourceException(BLOCKED_403, "HTTP 403", culprit.message ?: "HTTP 403", error)
        }
        return SourceException(kind, technicalName(culprit), culprit.message, error)
    }

    private fun classify(t: Throwable): SourceErrorKind? {
        val message = t.message.orEmpty().lowercase()
        return when {
            t is SignInConfirmNotBotException -> BOT_CHECK
            botCheckMarkers.any { it in message } -> BOT_CHECK
            t is ReCaptchaException -> RATE_LIMITED
            t is HttpStatusException && t.code == 429 -> RATE_LIMITED
            t is HttpStatusException && t.code == 403 -> BLOCKED_403
            t is AccountTerminatedException -> ACCOUNT_TERMINATED
            t is PrivateContentException -> PRIVATE
            t is AgeRestrictedContentException -> AGE_RESTRICTED
            t is GeographicRestrictionException || t is UnsupportedContentInCountryException -> GEO_RESTRICTED
            t is PaidContentException || t is YoutubeMusicPremiumContentException ||
                t is SoundCloudGoPlusContentException -> PAID
            t is UnknownHostException || t is SocketTimeoutException || t is ConnectException ||
                t is NoRouteToHostException || t is SocketException || t is SSLException ||
                t is InterruptedIOException -> NETWORK
            t is ContentNotAvailableException || t is ContentNotSupportedException -> CONTENT_UNAVAILABLE
            t is HttpStatusException -> if (t.code == 404 || t.code == 410) CONTENT_UNAVAILABLE else EXTRACTION_BROKEN
            // Other transport failures (e.g. "unexpected end of stream") are connectivity problems.
            t is IOException -> NETWORK
            else -> null
        }
    }

    private fun isGenericUnavailable(kind: SourceErrorKind, culprit: Throwable) =
        kind == CONTENT_UNAVAILABLE && culprit.javaClass == ContentNotAvailableException::class.java

    fun technicalName(t: Throwable): String = when (t) {
        is HttpStatusException -> "HTTP ${t.code}"
        else -> t.className()
    }

    private fun Throwable.causeChain(): Sequence<Throwable> {
        val seen = HashSet<Throwable>()
        return generateSequence(this) { it.cause }.takeWhile { seen.add(it) }
    }
}

/**
 * The exception's class name without package or enclosing class ("NothingFoundException" for
 * SearchExtractor$NothingFoundException), for the error screen. Built from the binary name so it does
 * not depend on inner-class metadata a shrinker may drop; the names themselves survive R8 thanks to the
 * `-keepnames` rule in consumer-rules.pro.
 */
internal fun Throwable.className(): String {
    val binary = javaClass.name.substringAfterLast('.')
    val innermost = binary.substringAfterLast('$')
    // Anonymous classes ("Outer$1") have no name of their own.
    return if (innermost.isEmpty() || innermost.all(Char::isDigit)) binary else innermost
}

/** An HTTP status we treat as a failure (used by our own requests and by the playback layer). */
internal class HttpStatusException(val code: Int, url: String?) : IOException("HTTP $code${url?.let { " ($it)" } ?: ""}")
