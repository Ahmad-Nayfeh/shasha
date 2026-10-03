package com.ahmadnayfeh.shasha.mobile.download

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.nio.ByteBuffer

class SampleInterleaverTest {

    /** A track: (time µs, size, flags) per sample. */
    private class FakeTrack(private val samples: List<Triple<Long, Int, Int>>, private val fill: Byte) : SampleSource {
        var index = 0
        override val timeUs: Long get() = if (index < samples.size) samples[index].first else -1
        override val flags: Int get() = samples[index].third
        override fun read(buffer: ByteBuffer): Int {
            if (index >= samples.size) return -1
            val size = samples[index].second
            repeat(size) { buffer.put(fill) }
            return size
        }

        override fun advance() {
            index++
        }
    }

    private data class Written(val track: Int, val timeUs: Long, val size: Int, val flags: Int, val firstByte: Byte)

    private class RecordingSink : SampleSink {
        val written = ArrayList<Written>()
        override fun write(track: Int, buffer: ByteBuffer, size: Int, timeUs: Long, flags: Int) {
            // The buffer holds exactly the sample: from 0 to its size.
            assertThat(buffer.position()).isEqualTo(0)
            assertThat(buffer.remaining()).isEqualTo(size)
            written += Written(track, timeUs, size, flags, buffer.get(0))
        }
    }

    private val key = 1 // MediaCodec.BUFFER_FLAG_KEY_FRAME

    @Test
    fun theSampleThatComesNextInTimeGoesFirstWhateverItsTrack() {
        val video = FakeTrack(listOf(Triple(0L, 100, key), Triple(40_000L, 50, 0), Triple(80_000L, 50, 0), Triple(120_000L, 50, 0)), fill = 1)
        val audio = FakeTrack(listOf(Triple(0L, 10, key), Triple(23_000L, 10, key), Triple(46_000L, 10, key), Triple(69_000L, 10, key), Triple(92_000L, 10, key)), fill = 2)
        val sink = RecordingSink()

        val counts = SampleInterleaver.run(listOf(video, audio), sink)

        assertThat(counts.toList()).containsExactly(4, 5).inOrder()
        assertThat(sink.written.map { it.timeUs }).isInOrder()
        // Time 0 is on both tracks: the first track (the video) goes first.
        assertThat(sink.written.map { it.track }).containsExactly(0, 1, 1, 0, 1, 1, 0, 1, 0).inOrder()
    }

    @Test
    fun sizeFlagsAndBytesOfEachSampleArriveAsTheyWere() {
        val video = FakeTrack(listOf(Triple(0L, 100, key), Triple(40_000L, 7, 0)), fill = 9)
        val audio = FakeTrack(listOf(Triple(10_000L, 3, key)), fill = 5)
        val sink = RecordingSink()

        SampleInterleaver.run(listOf(video, audio), sink)

        assertThat(sink.written).containsExactly(
            Written(0, 0, 100, key, 9),
            Written(1, 10_000, 3, key, 5),
            Written(0, 40_000, 7, 0, 9),
        ).inOrder()
    }

    @Test
    fun aTrackThatEndsEarlyLeavesTheOtherToFinish() {
        val video = FakeTrack(listOf(Triple(0L, 1, key)), fill = 1)
        val audio = FakeTrack(List(5) { Triple(it * 1_000L, 1, key) }, fill = 2)
        val sink = RecordingSink()

        val counts = SampleInterleaver.run(listOf(video, audio), sink)

        assertThat(counts.toList()).containsExactly(1, 5).inOrder()
    }

    @Test
    fun anEmptyTrackWritesNothingAndTheOtherIsStillCopied() {
        val video = FakeTrack(emptyList(), fill = 1)
        val audio = FakeTrack(List(3) { Triple(it * 1_000L, 1, key) }, fill = 2)
        val sink = RecordingSink()

        val counts = SampleInterleaver.run(listOf(video, audio), sink)

        assertThat(counts.toList()).containsExactly(0, 3).inOrder()
    }

    @Test
    fun aSourceThatCannotReadItsSampleIsLeftOut() {
        val broken = object : SampleSource {
            override val timeUs: Long = 0
            override val flags: Int = 0
            override fun read(buffer: ByteBuffer): Int = -1
            override fun advance() = Unit
        }
        val audio = FakeTrack(List(2) { Triple(it * 1_000L, 1, key) }, fill = 2)
        val sink = RecordingSink()

        val counts = SampleInterleaver.run(listOf(broken, audio), sink)

        assertThat(counts.toList()).containsExactly(0, 2).inOrder()
    }

    @Test
    fun stoppingStopsBeforeTheNextSample() {
        val audio = FakeTrack(List(10) { Triple(it * 1_000L, 1, key) }, fill = 2)
        val sink = RecordingSink()
        var budget = 3

        SampleInterleaver.run(listOf(audio), sink, shouldContinue = { budget-- > 0 })

        assertThat(sink.written).hasSize(3)
    }

    @Test
    fun progressIsTheTimeOfTheSampleJustWritten() {
        val audio = FakeTrack(List(3) { Triple(it * 1_000L, 1, key) }, fill = 2)
        val seen = ArrayList<Long>()

        SampleInterleaver.run(listOf(audio), RecordingSink(), onProgress = { seen += it })

        assertThat(seen).containsExactly(0L, 1_000L, 2_000L).inOrder()
    }
}
