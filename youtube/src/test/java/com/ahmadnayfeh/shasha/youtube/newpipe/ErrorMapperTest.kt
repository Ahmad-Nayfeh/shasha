package com.ahmadnayfeh.shasha.youtube.newpipe

import com.ahmadnayfeh.shasha.youtube.SourceErrorKind
import com.ahmadnayfeh.shasha.youtube.SourceException
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.schabi.newpipe.extractor.exceptions.AccountTerminatedException
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ContentNotSupportedException
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.exceptions.FoundAdException
import org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException
import org.schabi.newpipe.extractor.exceptions.PaidContentException
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.exceptions.PrivateContentException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException
import org.schabi.newpipe.extractor.exceptions.SoundCloudGoPlusContentException
import org.schabi.newpipe.extractor.exceptions.UnsupportedContentInCountryException
import org.schabi.newpipe.extractor.exceptions.UnsupportedTabException
import org.schabi.newpipe.extractor.exceptions.YoutubeMusicPremiumContentException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class ErrorMapperTest {

    private fun kindOf(t: Throwable, status: Int? = null) = ErrorMapper.map(t, status).kind

    @Test
    fun everyExtractorExceptionHasAKind() {
        val cases = mapOf(
            SignInConfirmNotBotException("got error LOGIN_REQUIRED") to SourceErrorKind.BOT_CHECK,
            ReCaptchaException("reCaptcha", "https://www.youtube.com") to SourceErrorKind.RATE_LIMITED,
            AccountTerminatedException("terminated") to SourceErrorKind.ACCOUNT_TERMINATED,
            PrivateContentException("private") to SourceErrorKind.PRIVATE,
            AgeRestrictedContentException("age") to SourceErrorKind.AGE_RESTRICTED,
            GeographicRestrictionException("geo") to SourceErrorKind.GEO_RESTRICTED,
            UnsupportedContentInCountryException("country") to SourceErrorKind.GEO_RESTRICTED,
            PaidContentException("members") to SourceErrorKind.PAID,
            YoutubeMusicPremiumContentException() to SourceErrorKind.PAID,
            SoundCloudGoPlusContentException() to SourceErrorKind.PAID,
            ContentNotAvailableException("removed") to SourceErrorKind.CONTENT_UNAVAILABLE,
            ContentNotSupportedException("unsupported") to SourceErrorKind.CONTENT_UNAVAILABLE,
            FoundAdException("ad") to SourceErrorKind.EXTRACTION_BROKEN,
            ParsingException("could not parse") to SourceErrorKind.EXTRACTION_BROKEN,
            ExtractionException("broken") to SourceErrorKind.EXTRACTION_BROKEN,
            UnsupportedTabException("tab") to SourceErrorKind.EXTRACTION_BROKEN,
            IllegalStateException("npe bug") to SourceErrorKind.EXTRACTION_BROKEN,
        )
        cases.forEach { (error, expected) ->
            val mapped = ErrorMapper.map(error)
            assertThat(mapped.kind).isEqualTo(expected)
            assertThat(mapped.technicalName).isEqualTo(error.javaClass.simpleName)
            assertThat(mapped.cause).isSameInstanceAs(error)
        }
    }

    @Test
    fun botCheckIsRecognisedFromTheMessageToo() {
        assertThat(kindOf(ContentNotAvailableException("Got error LOGIN_REQUIRED: \"Sign in to confirm you're not a bot\"")))
            .isEqualTo(SourceErrorKind.BOT_CHECK)
        assertThat(kindOf(ContentNotAvailableException("Got error LOGIN_REQUIRED: \"Sign in to confirm you\u2019re not a bot\"")))
            .isEqualTo(SourceErrorKind.BOT_CHECK)
        assertThat(kindOf(ContentNotAvailableException("سجِّل الدخول لتأكيد أنّك لست روبوتًا"))).isEqualTo(SourceErrorKind.BOT_CHECK)
        // The age check starts with the same words but is not a bot check.
        assertThat(kindOf(ContentNotAvailableException("Got error LOGIN_REQUIRED: \"Sign in to confirm your age\"")))
            .isEqualTo(SourceErrorKind.CONTENT_UNAVAILABLE)
    }

    @Test
    fun networkFailuresAreNotYouTubesFault() {
        listOf(UnknownHostException("www.youtube.com"), SocketTimeoutException("timeout"), ConnectException("refused"),
            SSLHandshakeException("captive portal"), IOException("unexpected end of stream"))
            .forEach { assertThat(kindOf(it)).isEqualTo(SourceErrorKind.NETWORK) }
    }

    @Test
    fun wrappedCausesAreFound() {
        val wrappedNetwork = ParsingException("Could not get page", UnknownHostException("www.youtube.com"))
        val mapped = ErrorMapper.map(wrappedNetwork)
        assertThat(mapped.kind).isEqualTo(SourceErrorKind.NETWORK)
        assertThat(mapped.technicalName).isEqualTo("UnknownHostException")

        // StreamInfo wraps the real reason in a generic ContentNotAvailableException.
        val wrappedPrivate = ContentNotAvailableException("Video unavailable", PrivateContentException("private"))
        assertThat(kindOf(wrappedPrivate)).isEqualTo(SourceErrorKind.PRIVATE)

        val wrappedBot = ExtractionException(ParsingException("x", SignInConfirmNotBotException("bot")))
        assertThat(ErrorMapper.map(wrappedBot).technicalName).isEqualTo("SignInConfirmNotBotException")
    }

    @Test
    fun http403Handling() {
        val direct = ErrorMapper.map(HttpStatusException(403, "https://www.youtube.com/youtubei/v1/player"))
        assertThat(direct.kind).isEqualTo(SourceErrorKind.BLOCKED_403)
        assertThat(direct.technicalName).isEqualTo("HTTP 403")
        assertThat(kindOf(HttpStatusException(429, null))).isEqualTo(SourceErrorKind.RATE_LIMITED)
        assertThat(kindOf(HttpStatusException(404, null))).isEqualTo(SourceErrorKind.CONTENT_UNAVAILABLE)

        // A 403 seen during the operation explains a vague failure...
        val vague = ErrorMapper.map(ParsingException("JSON response is too short"), httpBlockedStatus = 403)
        assertThat(vague.kind).isEqualTo(SourceErrorKind.BLOCKED_403)
        assertThat(vague.technicalName).isEqualTo("HTTP 403")
        assertThat(kindOf(ContentNotAvailableException("Content unavailable"), 403)).isEqualTo(SourceErrorKind.BLOCKED_403)
        // ...but never hides a specific reason.
        assertThat(kindOf(PrivateContentException("private"), 403)).isEqualTo(SourceErrorKind.PRIVATE)
        assertThat(kindOf(UnknownHostException("x"), 403)).isEqualTo(SourceErrorKind.NETWORK)
    }

    @Test
    fun sourceExceptionPassesThrough() {
        val original = SourceException(SourceErrorKind.LOW_QUALITY_ONLY, "MuxedOnly360p", "x", null)
        assertThat(ErrorMapper.map(original)).isSameInstanceAs(original)
    }

    @Test
    fun selfReferencingCauseDoesNotLoop() {
        val a = IllegalStateException("a")
        val b = IllegalArgumentException("b", a)
        a.initCause(b)
        assertThat(kindOf(b)).isEqualTo(SourceErrorKind.EXTRACTION_BROKEN)
    }
}
