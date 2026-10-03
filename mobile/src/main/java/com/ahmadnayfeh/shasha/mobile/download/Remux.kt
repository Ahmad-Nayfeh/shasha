package com.ahmadnayfeh.shasha.mobile.download

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.nio.ByteBuffer

/** One track's samples in order: what [MediaExtractor] gives, or a list in a test. */
interface SampleSource {
    /** Presentation time of the current sample in microseconds; negative when the track is over. */
    val timeUs: Long

    /** `MediaCodec.BUFFER_FLAG_*` of the current sample (the key-frame flag). */
    val flags: Int

    /** Copies the current sample into [buffer] (from position 0) and returns its size, or -1 when there is none. */
    fun read(buffer: ByteBuffer): Int

    /** Moves to the next sample. */
    fun advance()
}

/** Where the samples go: one track per source, in the order the sources were given. */
interface SampleSink {
    fun write(track: Int, buffer: ByteBuffer, size: Int, timeUs: Long, flags: Int)
}

/**
 * Merges the tracks of several [SampleSource]s into one [SampleSink] with no re-encoding: always the sample that comes
 * next in time, whichever track it is on. A muxer that was fed one whole track and then the other would hold the first
 * one in memory until the second began.
 */
object SampleInterleaver {
    const val DEFAULT_BUFFER_BYTES = 8 * 1024 * 1024

    /**
     * @param shouldContinue asked before every sample; false stops (cancelled).
     * @param onProgress the time (µs) of the sample just written.
     * @return how many samples each source wrote.
     */
    fun run(
        sources: List<SampleSource>,
        sink: SampleSink,
        buffer: ByteBuffer = ByteBuffer.allocate(DEFAULT_BUFFER_BYTES),
        shouldContinue: () -> Boolean = { true },
        onProgress: (timeUs: Long) -> Unit = {},
    ): IntArray {
        val written = IntArray(sources.size)
        val finished = BooleanArray(sources.size) { sources[it].timeUs < 0 }
        while (shouldContinue()) {
            var next = -1
            var nextTime = Long.MAX_VALUE
            for (i in sources.indices) {
                if (finished[i]) continue
                val time = sources[i].timeUs
                if (time < 0) {
                    finished[i] = true
                    continue
                }
                if (time < nextTime) {
                    next = i
                    nextTime = time
                }
            }
            if (next < 0) break
            val source = sources[next]
            buffer.clear()
            val size = source.read(buffer)
            if (size < 0) {
                finished[next] = true
                continue
            }
            val time = source.timeUs
            val flags = source.flags
            buffer.position(0)
            buffer.limit(size)
            sink.write(next, buffer, size, time, flags)
            written[next]++
            onProgress(time)
            source.advance()
        }
        return written
    }
}

/** Merges a downloaded video-only MP4 and an M4A audio file into one MP4 (or rewrites a lone M4A cleanly). */
interface Remuxer {
    /**
     * @param video the video-only file, or null for an audio-only result.
     * @param output a seekable descriptor opened for reading and writing.
     * @param onProgress 0..1.
     * @throws IOException when a file cannot be read as media or the muxer fails.
     */
    suspend fun mux(video: File?, audio: File, output: FileDescriptor, onProgress: (Float) -> Unit)
}

/** [SampleSource] over one selected track of a [MediaExtractor]. */
internal class ExtractorSource(private val extractor: MediaExtractor) : SampleSource {
    override val timeUs: Long get() = extractor.sampleTime

    override val flags: Int
        get() = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0

    override fun read(buffer: ByteBuffer): Int = extractor.readSampleData(buffer, 0)

    override fun advance() {
        extractor.advance()
    }
}

/** [SampleSink] over a started [MediaMuxer]; [trackIds] are the muxer's own indexes, one per source. */
internal class MuxerSink(private val muxer: MediaMuxer, private val trackIds: IntArray) : SampleSink {
    private val info = MediaCodec.BufferInfo()

    override fun write(track: Int, buffer: ByteBuffer, size: Int, timeUs: Long, flags: Int) {
        info.set(0, size, timeUs, flags)
        muxer.writeSampleData(trackIds[track], buffer, info)
    }
}

/**
 * The remux with Android's own [MediaExtractor] and [MediaMuxer] (MPEG-4 output): the H.264 and AAC samples are copied as
 * they are, so it is fast and loses nothing. Cannot run on the JVM: [SampleInterleaver] holds its logic and is tested.
 */
class MediaMuxerRemuxer : Remuxer {
    override suspend fun mux(video: File?, audio: File, output: FileDescriptor, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        val job = currentCoroutineContext()[Job]
        val extractors = ArrayList<MediaExtractor>(2)
        var muxer: MediaMuxer? = null
        var started = false
        try {
            val sources = ArrayList<SampleSource>(2)
            val formats = ArrayList<MediaFormat>(2)
            var durationUs = 0L
            var rotation = 0
            for ((file, prefix) in listOfNotNull(video?.let { it to "video/" }, audio to "audio/")) {
                val extractor = MediaExtractor().also { extractors += it }
                extractor.setDataSource(file.path)
                val index = (0 until extractor.trackCount).firstOrNull {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith(prefix) == true
                } ?: throw IOException("${file.name} has no ${prefix.trimEnd('/')} track")
                val format = extractor.getTrackFormat(index)
                extractor.selectTrack(index)
                if (format.containsKey(MediaFormat.KEY_DURATION)) durationUs = maxOf(durationUs, format.getLong(MediaFormat.KEY_DURATION))
                if (prefix == "video/" && format.containsKey(MediaFormat.KEY_ROTATION)) rotation = format.getInteger(MediaFormat.KEY_ROTATION)
                sources += ExtractorSource(extractor)
                formats += format
            }
            val m = MediaMuxer(output, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = m
            if (rotation != 0) m.setOrientationHint(rotation)
            val trackIds = IntArray(formats.size) { m.addTrack(formats[it]) }
            m.start()
            started = true
            val written = SampleInterleaver.run(
                sources = sources,
                sink = MuxerSink(m, trackIds),
                shouldContinue = { job?.isActive != false },
                onProgress = { timeUs -> if (durationUs > 0) onProgress((timeUs.toFloat() / durationUs).coerceIn(0f, 1f)) },
            )
            currentCoroutineContext().ensureActive() // cancelled: the file is not finished, so it is not stopped as if it were
            if (written.any { it == 0 }) throw IOException("A track had no samples")
            m.stop()
            started = false
            onProgress(1f)
        } finally {
            extractors.forEach { runCatching { it.release() } }
            muxer?.let {
                if (started) runCatching { it.stop() }
                runCatching { it.release() }
            }
        }
    }
}
