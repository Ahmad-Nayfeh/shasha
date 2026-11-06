package com.ahmadnayfeh.shasha.youtube

import com.ahmadnayfeh.shasha.youtube.newpipe.NewPipeVideoSource
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Times the extraction against the real service. Skipped unless run with -Dyoutube.realNetwork=true (CI stays offline).
 * Prints one "TIMING" line per step: the first extraction of the process (cold), later ones (warm), and Shorts.
 */
class RealNetworkTimingTest {
    private fun <T> tryTimed(label: String, block: suspend () -> T): T? = try { timed(label, block) } catch (e: Exception) { println("TIMING | $label | failed: ${e.message?.take(80)}"); null }

    private fun <T> timed(label: String, block: suspend () -> T): T {
        val start = System.nanoTime()
        return try {
            runBlocking { block() }
        } finally {
            println("TIMING | $label | ${(System.nanoTime() - start) / 1_000_000} ms")
        }
    }

    @Test
    fun extractionTimings() {
        assumeTrue("real-network tests disabled", System.getProperty("youtube.realNetwork") == "true")
        val source = timed("construct source") { NewPipeVideoSource(OkHttpClient()) }
        val first = timed("1st stream() cold (dQw4w9WgXcQ)") { source.stream("dQw4w9WgXcQ", allowLowQuality = true) }
        tryTimed("2nd stream() warm (9bZkp7q19f0)") { source.stream("9bZkp7q19f0", allowLowQuality = true) }
        tryTimed("3rd stream() warm (jNQXAC9IVRw)") { source.stream("jNQXAC9IVRw", allowLowQuality = true) }
        val shorts = tryTimed("channelShorts") { source.channelShorts("UCX6OQ3DkcsbYNE6H8uQQuVA") }.orEmpty()
        shorts.firstOrNull()?.let { s -> tryTimed("Short stream() (${s.videoId})") { source.stream(s.videoId, allowLowQuality = true) } }
        shorts.drop(1).firstOrNull()?.let { s -> tryTimed("2nd Short stream() (${s.videoId})") { source.stream(s.videoId, allowLowQuality = true) } }
        val info = first.playback.info
        println("TIMING | info | video-only ${info.videoOnlyStreams.size}, audio ${info.audioStreams.size}, muxed ${info.videoStreams.size}")
    }
}
