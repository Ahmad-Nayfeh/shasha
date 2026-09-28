package com.ahmadnayfeh.shasha.youtube.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import com.ahmadnayfeh.shasha.youtube.SourceErrorKind
import com.google.common.truth.Truth.assertThat
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import okhttp3.Dns
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.net.InetAddress

/**
 * The data source against a local server posing as googlevideo (a custom Dns maps the
 * googlevideo host name to the MockWebServer).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class YouTubeHttpDataSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OkHttpClient.Builder()
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> = listOf(InetAddress.getByName(server.hostName))
            })
            .build()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun mediaUrl(query: String = "itag=137&c=ANDROID") = "http://rr1---sn-test.googlevideo.com:${server.port}/videoplayback?$query"

    private fun read(source: HttpDataSource, spec: DataSpec): Pair<Long, ByteArray> {
        val opened = source.open(spec)
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(7)
        while (true) {
            val n = source.read(buffer, 0, buffer.size)
            if (n == C.RESULT_END_OF_INPUT) break
            out.write(buffer, 0, n)
        }
        source.close()
        return opened to out.toByteArray()
    }

    @Test
    fun dashRequestsCarryRangeAndRequestNumberInTheUrlAndPostTheMagicBody() {
        val body = ByteArray(50) { it.toByte() }
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(body)))
        val source = YouTubeHttpDataSource.Factory(client, GoogleVideoQuirks.DASH).createDataSource()

        val (opened, bytes) = read(source, DataSpec.Builder().setUri(mediaUrl()).setPosition(100).setLength(50).build())

        assertThat(opened).isEqualTo(50)
        // A 200 with the range in the URL already starts at the position: nothing is skipped.
        assertThat(bytes).isEqualTo(body)
        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.body.readByteArray()).isEqualTo(byteArrayOf(0x78, 0))
        assertThat(request.requestUrl!!.encodedQuery).isEqualTo("itag=137&c=ANDROID&rn=0&range=100-149")
        assertThat(request.getHeader("Range")).isNull()
        assertThat(request.getHeader("User-Agent")).startsWith("com.google.android.youtube/")
        assertThat(request.getHeader("Accept-Encoding")).isEqualTo("identity")
    }

    @Test
    fun progressiveReadsUseARangeHeaderAndSkipWhenTheServerIgnoresIt() {
        val file = ByteArray(300) { (it % 251).toByte() }
        // First: a server honouring the range (206). Second: one answering 200 with the whole file.
        server.enqueue(MockResponse().setResponseCode(206).addHeader("Content-Range", "bytes 100-299/300").setBody(Buffer().write(file, 100, 200)))
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(file)))
        val source = YouTubeHttpDataSource.Factory(client, GoogleVideoQuirks.PROGRESSIVE).createDataSource()
        val spec = DataSpec.Builder().setUri(mediaUrl("itag=18&c=IOS")).setPosition(100).build()

        val (opened206, bytes206) = read(source, spec)
        val (opened200, bytes200) = read(source, spec)

        assertThat(opened206).isEqualTo(200)
        assertThat(bytes206).isEqualTo(file.copyOfRange(100, 300))
        assertThat(opened200).isEqualTo(200)
        assertThat(bytes200).isEqualTo(file.copyOfRange(100, 300))
        val first = server.takeRequest()
        assertThat(first.getHeader("Range")).isEqualTo("bytes=100-")
        assertThat(first.requestUrl!!.queryParameter("rn")).isEqualTo("0")
        assertThat(first.requestUrl!!.queryParameter("range")).isNull()
        assertThat(first.getHeader("User-Agent")).startsWith("com.google.ios.youtube/")
        assertThat(server.takeRequest().requestUrl!!.queryParameter("rn")).isEqualTo("1")
    }

    @Test
    fun otherHostsGetAPlainGet() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("WEBVTT\n"))
        val source = YouTubeHttpDataSource.Factory(client, GoogleVideoQuirks.DASH).createDataSource()

        val (_, bytes) = read(source, DataSpec(Uri.parse(server.url("/api/timedtext?lang=en").toString())))

        assertThat(String(bytes)).isEqualTo("WEBVTT\n")
        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("GET")
        assertThat(request.requestUrl!!.queryParameter("rn")).isNull()
        assertThat(request.getHeader("User-Agent")).contains("Firefox")
    }

    @Test
    fun http403IsAnInvalidResponseCodeClassifiedAsBlocked() {
        server.enqueue(MockResponse().setResponseCode(403).setBody("denied"))
        val source = YouTubeHttpDataSource.Factory(client, GoogleVideoQuirks.DASH).createDataSource()

        val e = assertThrows(HttpDataSource.InvalidResponseCodeException::class.java) {
            source.open(DataSpec.Builder().setUri(mediaUrl()).setPosition(0).setLength(10).build())
        }

        assertThat(e.responseCode).isEqualTo(403)
        assertThat(String(e.responseBody)).isEqualTo("denied")
        assertThat(PlaybackErrorClassifier.classify(e).kind).isEqualTo(SourceErrorKind.BLOCKED_403)
    }

    @Test
    fun defaultRequestPropertiesAreSentButCannotOverrideTheClientUserAgent() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("x"))
        val factory = YouTubeHttpDataSource.Factory(client, GoogleVideoQuirks.DASH)
        factory.setDefaultRequestProperties(mapOf("X-Test" to "1", "User-Agent" to "App/1.0"))

        read(factory.createDataSource(), DataSpec(Uri.parse(mediaUrl())))

        val request = server.takeRequest()
        assertThat(request.getHeader("X-Test")).isEqualTo("1")
        assertThat(request.getHeader("User-Agent")).startsWith("com.google.android.youtube/")
    }
}
