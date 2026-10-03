package com.ahmadnayfeh.shasha.youtube.download

import com.ahmadnayfeh.shasha.youtube.SourceErrorKind
import com.ahmadnayfeh.shasha.youtube.SourceException
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * The downloader against a local server that poses as googlevideo (a custom Dns maps its host name to the MockWebServer) or
 * as a plain file server.
 */
class StreamDownloaderTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private val content = ByteArray(10_000) { (it * 31 % 251).toByte() }
    private val requests = CopyOnWriteArrayList<RecordedRequest>()

    /** Answers any byte range of [content]: "range=a-b" in the URL, or a Range header. */
    private val rangeDispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            requests += request
            val fromUrl = request.requestUrl?.queryParameter("range")
            val fromHeader = request.getHeader("Range")?.removePrefix("bytes=")
            val range = fromUrl ?: fromHeader ?: return MockResponse().setBody(Buffer().write(content))
            val (start, endText) = range.split('-')
            val first = start.toInt()
            if (first >= content.size) return MockResponse().setResponseCode(416)
            val last = minOf(endText.takeIf { it.isNotEmpty() }?.toInt() ?: (content.size - 1), content.size - 1)
            val body = Buffer().write(content, first, last - first + 1)
            return MockResponse().setResponseCode(if (fromUrl != null) 200 else 206).setBody(body)
        }
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = rangeDispatcher
        server.start()
        client = OkHttpClient.Builder()
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> = listOf(InetAddress.getByName(server.hostName))
            })
            .build()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun googlevideo(size: Long? = content.size.toLong()) = DownloadStream(
        url = "http://rr1---sn-test.googlevideo.com:${server.port}/videoplayback?itag=140&c=ANDROID&clen=${content.size}",
        mimeType = "audio/mp4", codecs = "mp4a.40.2", bitrate = 130_000, sizeBytes = size, itag = 140,
    )

    private fun plain(size: Long? = content.size.toLong()) = googlevideo(size).copy(url = server.url("/file.m4a").toString())

    private fun downloader(chunk: Long = 3_000) = StreamDownloader(client, chunkBytes = chunk, retryDelayMs = 1)

    @Test
    fun fetchesInChunksWithTheRangeInTheUrlLikeYouTubesPlayers() = runBlocking<Unit> {
        val target = File(folder.root, "a.m4a")
        val progress = ArrayList<Long>()

        val length = downloader().download(googlevideo(), target) { progress += it }

        assertThat(length).isEqualTo(10_000)
        assertThat(target.readBytes()).isEqualTo(content)
        assertThat(requests.map { it.requestUrl!!.queryParameter("range") })
            .containsExactly("0-2999", "3000-5999", "6000-8999", "9000-9999").inOrder()
        // googlevideo's habits: a POST with the two-byte body and a request counter.
        assertThat(requests.map { it.method }.distinct()).containsExactly("POST")
        assertThat(requests.map { it.requestUrl!!.queryParameter("rn") }).containsExactly("0", "1", "2", "3").inOrder()
        assertThat(progress.first()).isEqualTo(0)
        assertThat(progress.last()).isEqualTo(10_000)
        assertThat(progress).isInOrder()
    }

    @Test
    fun aPlainServerGetsARangeHeaderInstead() = runBlocking<Unit> {
        val target = File(folder.root, "b.m4a")

        downloader().download(plain(), target)

        assertThat(target.readBytes()).isEqualTo(content)
        assertThat(requests.map { it.getHeader("Range") })
            .containsExactly("bytes=0-2999", "bytes=3000-5999", "bytes=6000-8999", "bytes=9000-9999").inOrder()
        assertThat(requests.map { it.method }.distinct()).containsExactly("GET")
    }

    @Test
    fun resumesFromTheBytesAlreadyOnDisk() = runBlocking<Unit> {
        val target = File(folder.root, "c.m4a")
        target.writeBytes(content.copyOfRange(0, 4_000))

        val length = downloader().download(googlevideo(), target)

        assertThat(length).isEqualTo(10_000)
        assertThat(target.readBytes()).isEqualTo(content)
        assertThat(requests.first().requestUrl!!.queryParameter("range")).isEqualTo("4000-6999")
    }

    @Test
    fun aCompleteFileAsksForNothing() = runBlocking<Unit> {
        val target = File(folder.root, "d.m4a")
        target.writeBytes(content)

        downloader().download(googlevideo(), target)

        assertThat(requests).isEmpty()
        assertThat(target.readBytes()).isEqualTo(content)
    }

    @Test
    fun aFileLongerThanTheStreamStartsAgain() = runBlocking<Unit> {
        val target = File(folder.root, "e.m4a")
        target.writeBytes(ByteArray(12_000) { 7 })

        downloader().download(googlevideo(), target)

        assertThat(target.readBytes()).isEqualTo(content)
        assertThat(requests.first().requestUrl!!.queryParameter("range")).isEqualTo("0-2999")
    }

    @Test
    fun withoutAKnownSizeAShortChunkIsTheEnd() = runBlocking<Unit> {
        val target = File(folder.root, "f.m4a")

        val length = downloader().download(googlevideo(size = null), target)

        assertThat(length).isEqualTo(10_000)
        assertThat(target.readBytes()).isEqualTo(content)
        // 3000 + 3000 + 3000 + 1000: the last answer was shorter than asked for.
        assertThat(requests).hasSize(4)
    }

    @Test
    fun aBrokenConnectionIsTriedAgainFromWhereItBroke() = runBlocking<Unit> {
        val broken = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val response = rangeDispatcher.dispatch(request)
                // The second request dies after 1000 of its 3000 bytes.
                return if (broken.incrementAndGet() == 2) {
                    response.setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
                } else {
                    response
                }
            }
        }
        val target = File(folder.root, "g.m4a")

        downloader().download(googlevideo(), target)

        assertThat(target.readBytes()).isEqualTo(content)
        // The request after the broken one asked for the rest of that chunk, not for the whole chunk again.
        val starts = requests.map { it.requestUrl!!.queryParameter("range")!!.substringBefore('-').toInt() }
        assertThat(starts.any { it in 3_001..5_999 }).isTrue()
    }

    @Test
    fun aServerErrorIsTriedAgain() = runBlocking<Unit> {
        val calls = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (calls.incrementAndGet() <= 2) MockResponse().setResponseCode(503) else rangeDispatcher.dispatch(request)
        }
        val target = File(folder.root, "h.m4a")

        downloader().download(googlevideo(), target)

        assertThat(target.readBytes()).isEqualTo(content)
    }

    @Test
    fun aRefusedUrlIsABlocked403ForTheCallerToExtractAgain() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return MockResponse().setResponseCode(403)
            }
        }

        val error = assertThrows(SourceException::class.java) {
            runBlocking { downloader().download(googlevideo(), File(folder.root, "i.m4a")) }
        }

        assertThat(error.kind).isEqualTo(SourceErrorKind.BLOCKED_403)
        assertThat(error.technicalName).isEqualTo("HTTP 403")
        assertThat(requests).hasSize(1) // no retries: the URL will not get better by asking again
    }

    @Test
    fun givesUpAfterTheRetries() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return MockResponse().setResponseCode(500)
            }
        }

        assertThrows(java.io.IOException::class.java) {
            runBlocking { downloader().download(googlevideo(), File(folder.root, "j.m4a")) }
        }

        assertThat(requests).hasSize(StreamDownloader.MAX_RETRIES + 1)
    }

    @Test
    fun cancellingStopsTheDownloadAtOnce() = runBlocking<Unit> {
        // A server that never answers the first request.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
        }
        val job = async { downloader().download(googlevideo(), File(folder.root, "k.m4a")) }
        delay(200)

        job.cancelAndJoin()

        assertThat(job.isCancelled).isTrue()
    }
}
