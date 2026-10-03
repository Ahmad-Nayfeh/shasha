package com.ahmadnayfeh.shasha.youtube.download

import com.ahmadnayfeh.shasha.youtube.newpipe.NewPipeVideoSource
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile

/**
 * Downloads real streams from the real YouTube. Skipped unless run with:
 *   ./gradlew :youtube:testDebugUnitTest --tests '*RealNetworkDownloadTest*' -Dyoutube.realNetwork=true
 * It proves the assumptions the phone app's downloads rest on: that a video offers H.264 MP4 and M4A streams, that their
 * sizes are known, that chunked requests in googlevideo's own manner fetch exactly those bytes, and that what arrives is an
 * MP4 file (the phone app then merges the two with MediaExtractor and MediaMuxer, which only a device can run).
 */
class RealNetworkDownloadTest {

    @get:Rule
    val folder = TemporaryFolder()

    companion object {
        private lateinit var client: OkHttpClient
        private lateinit var source: NewPipeVideoSource

        @BeforeClass
        @JvmStatic
        fun setUp() {
            assumeTrue("real-network tests disabled", System.getProperty("youtube.realNetwork") == "true")
            client = OkHttpClient()
            source = NewPipeVideoSource(client)
        }

        private fun report(test: String, outcome: String) = println("REAL-RESULT | $test | $outcome")
    }

    @Test
    fun aNormalVideoOffersH264AndM4aStreamsThatDownloadWhole() = runBlocking {
        val details = source.stream("dQw4w9WgXcQ")
        val options = details.downloadOptions()
        report(
            "download options dQw4w9WgXcQ",
            "videos ${options.videos.map { "${it.height}p${if (it.fps > 30) it.fps else ""}/${it.video.codecs}/${it.video.sizeBytes ?: "?"}" }}, " +
                "audio ${options.audio?.codecs}/${options.audio?.sizeBytes ?: "?"}",
        )
        assertThat(options.videos).isNotEmpty()
        assertThat(options.audio).isNotNull()
        assertThat(options.videos.map { it.height }).contains(360)

        // The smallest picture and the audio: whole files, in chunks, through the same code the app uses.
        val lowest = options.videos.last()
        val downloader = StreamDownloader(client)
        val video = File(folder.root, "video.mp4")
        val audio = File(folder.root, "audio.m4a")
        downloader.download(lowest.video, video)
        downloader.download(options.audio!!, audio)

        report("download video ${lowest.height}p", "${video.length()} bytes (listed ${lowest.video.sizeBytes}), boxes ${summary(boxes(video))}")
        report("download audio", "${audio.length()} bytes (listed ${options.audio!!.sizeBytes}), boxes ${summary(boxes(audio))}")
        lowest.video.sizeBytes?.let { assertThat(video.length()).isEqualTo(it) }
        options.audio!!.sizeBytes?.let { assertThat(audio.length()).isEqualTo(it) }
        assertThat(video.length()).isGreaterThan(50_000L)
        assertThat(audio.length()).isGreaterThan(50_000L)
        assertThat(boxes(video).first()).isEqualTo("ftyp")
        assertThat(boxes(audio).first()).isEqualTo("ftyp")
        assertThat(boxes(video)).contains("moov")
        assertThat(boxes(audio)).contains("moov")
    }

    /** The top-level boxes of an MP4 file, in order. */
    private fun boxes(file: File): List<String> {
        val result = ArrayList<String>()
        RandomAccessFile(file, "r").use { f ->
            var position = 0L
            while (position + 8 <= f.length()) {
                f.seek(position)
                var size = f.readInt().toLong() and 0xFFFFFFFFL
                val type = ByteArray(4).also { f.readFully(it) }.toString(Charsets.US_ASCII)
                if (size == 1L) size = f.readLong()
                if (size == 0L) size = f.length() - position
                result += type
                if (size < 8) break
                position += size
            }
        }
        return result
    }

    /** "ftyp moov sidx moof×40 mdat×40": consecutive repeats collapsed. */
    private fun summary(boxes: List<String>): String {
        val parts = ArrayList<String>()
        var i = 0
        while (i < boxes.size) {
            var j = i
            while (j + 1 < boxes.size && boxes[j + 1] == boxes[i]) j++
            parts += if (j > i) "${boxes[i]}×${j - i + 1}" else boxes[i]
            i = j + 1
        }
        return parts.joinToString(" ")
    }
}
