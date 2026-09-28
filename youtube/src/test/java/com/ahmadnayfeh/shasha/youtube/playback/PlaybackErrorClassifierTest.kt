package com.ahmadnayfeh.shasha.youtube.playback

import android.net.Uri
import androidx.media3.common.ParserException
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource.HttpDataSourceException
import androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException
import androidx.media3.exoplayer.ExoPlaybackException
import com.ahmadnayfeh.shasha.youtube.SourceErrorKind
import com.ahmadnayfeh.shasha.youtube.SourceException
import com.ahmadnayfeh.shasha.youtube.newpipe.HttpStatusException
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.CreationException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class PlaybackErrorClassifierTest {

    private val spec = DataSpec(Uri.parse("https://rr1---sn-a.googlevideo.com/videoplayback?itag=137"))

    /** What ExoPlayer hands to Player.Listener.onPlayerError for a failed media load. */
    private fun playerError(cause: IOException, code: Int = PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS) =
        ExoPlaybackException.createForSource(cause, code)

    private fun status(code: Int) = InvalidResponseCodeException(code, "x", null, emptyMap(), spec, ByteArray(0))

    private fun classify(t: Throwable) = PlaybackErrorClassifier.classify(t)

    @Test
    fun httpStatusesFromGooglevideo() {
        classify(playerError(status(403))).let {
            assertThat(it.kind).isEqualTo(SourceErrorKind.BLOCKED_403)
            assertThat(it.technicalName).isEqualTo("HTTP 403")
        }
        assertThat(classify(playerError(status(429))).kind).isEqualTo(SourceErrorKind.RATE_LIMITED)
        assertThat(classify(playerError(status(404))).kind).isEqualTo(SourceErrorKind.CONTENT_UNAVAILABLE)
        assertThat(classify(playerError(status(410))).kind).isEqualTo(SourceErrorKind.CONTENT_UNAVAILABLE)
        classify(playerError(status(500))).let {
            assertThat(it.kind).isEqualTo(SourceErrorKind.EXTRACTION_BROKEN)
            assertThat(it.technicalName).isEqualTo("HTTP 500")
        }
    }

    @Test
    fun connectivityProblemsAreNetwork() {
        val offline = HttpDataSourceException.createForIOException(UnknownHostException("rr1---sn-a.googlevideo.com"), spec, HttpDataSourceException.TYPE_OPEN)
        classify(playerError(offline, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)).let {
            assertThat(it.kind).isEqualTo(SourceErrorKind.NETWORK)
            assertThat(it.technicalName).isEqualTo("UnknownHostException")
        }
        val timeout = HttpDataSourceException.createForIOException(SocketTimeoutException(), spec, HttpDataSourceException.TYPE_READ)
        assertThat(classify(playerError(timeout, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT)).kind)
            .isEqualTo(SourceErrorKind.NETWORK)
        assertThat(classify(PlaybackException("no cause", null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)).kind)
            .isEqualTo(SourceErrorKind.NETWORK)
    }

    @Test
    fun malformedDataIsExtractionBrokenEvenThoughItIsAnIOException() {
        val parser = ParserException.createForMalformedManifest("bad mpd", null)
        classify(playerError(parser, PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED)).let {
            assertThat(it.kind).isEqualTo(SourceErrorKind.EXTRACTION_BROKEN)
            assertThat(it.technicalName).isEqualTo("ParserException")
        }
    }

    @Test
    fun anythingElseIsExtractionBrokenNamedByTheErrorCode() {
        classify(PlaybackException("decoder", null, PlaybackException.ERROR_CODE_DECODER_INIT_FAILED)).let {
            assertThat(it.kind).isEqualTo(SourceErrorKind.EXTRACTION_BROKEN)
            assertThat(it.technicalName).isEqualTo("ERROR_CODE_DECODER_INIT_FAILED")
        }
        assertThat(classify(IllegalStateException("boom")).technicalName).isEqualTo("IllegalStateException")
    }

    @Test
    fun manifestCreationFailures() {
        // What YouTubeMediaSourceFactory throws when NPE's OTF / post-live creator sees a 403 or 429.
        val blocked = HttpStatusException(403, null).apply { initCause(CreationException("Could not get the initialization URL: response code 403")) }
        assertThat(classify(playerError(blocked, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)).kind).isEqualTo(SourceErrorKind.BLOCKED_403)
        val limited = YouTubeMediaSourceFactory.ManifestCreationException(CreationException("x", ReCaptchaException("429", "u")))
        assertThat(classify(playerError(limited, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)).kind).isEqualTo(SourceErrorKind.RATE_LIMITED)
        val other = YouTubeMediaSourceFactory.ManifestCreationException(CreationException("Could not get segment durations"))
        assertThat(classify(playerError(other, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)).kind).isEqualTo(SourceErrorKind.EXTRACTION_BROKEN)
    }

    @Test
    fun anExistingSourceExceptionIsReturnedAsIs() {
        val original = SourceException(SourceErrorKind.PRIVATE, "PrivateContentException", "private", null)
        assertThat(classify(RuntimeException(original))).isSameInstanceAs(original)
    }
}
