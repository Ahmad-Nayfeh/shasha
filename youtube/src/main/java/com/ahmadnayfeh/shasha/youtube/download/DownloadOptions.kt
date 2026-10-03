package com.ahmadnayfeh.shasha.youtube.download

import com.ahmadnayfeh.shasha.youtube.StreamDetails
import com.ahmadnayfeh.shasha.youtube.playback.heightOrResolution
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.Stream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.VideoStream

/** One remote file a download fetches: a video-only MP4 (H.264) or an M4A (AAC) audio stream. */
data class DownloadStream(
    val url: String,
    /** "video/mp4" or "audio/mp4". */
    val mimeType: String,
    /** "avc1.640028", "mp4a.40.2". */
    val codecs: String,
    val bitrate: Int,
    /** The file's length in bytes (YouTube's `clen`), or null when nothing says. */
    val sizeBytes: Long?,
    val itag: Int,
)

/** One quality of a video download: the picture of [height] p and the audio that goes with it. */
data class VideoDownloadOption(
    /** YouTube's quality label: the short side of the picture ("1080" for 1920x1080 and for 1080x1920). */
    val height: Int,
    val fps: Int,
    val video: DownloadStream,
    val audio: DownloadStream,
) {
    /** What the two files add up to, or null when one of them does not say. */
    val sizeBytes: Long? get() = video.sizeBytes?.let { v -> audio.sizeBytes?.let { a -> v + a } }
}

/**
 * What a video can be downloaded as: [videos] best quality first (MP4 with H.264 plus M4A audio, which the app merges
 * into one MP4 without re-encoding), and [audio] alone (M4A). Empty/null when the video is a live stream or offers no
 * such streams (e.g. only WebM).
 */
data class DownloadOptions(
    val videos: List<VideoDownloadOption>,
    val audio: DownloadStream?,
    val durationSeconds: Long,
) {
    val isEmpty: Boolean get() = videos.isEmpty() && audio == null

    /**
     * The option for a request of [height]: the best one at or below it, or, when the video has nothing that low, the
     * lowest it has. Null without video options.
     */
    fun videoFor(height: Int): VideoDownloadOption? =
        videos.filter { it.height <= height }.maxByOrNull { it.height } ?: videos.minByOrNull { it.height }
}

/** The downloadable streams of this video (see [DownloadOptions]). */
fun StreamDetails.downloadOptions(): DownloadOptions = DownloadPlanner.options(playback.info)

internal object DownloadPlanner {

    fun options(info: StreamInfo): DownloadOptions {
        val live = info.streamType == StreamType.LIVE_STREAM || info.streamType == StreamType.AUDIO_LIVE_STREAM ||
            info.streamType == StreamType.POST_LIVE_STREAM || info.streamType == StreamType.POST_LIVE_AUDIO_STREAM
        if (live) return DownloadOptions(emptyList(), null, info.duration)
        val audioStreams = preferredAudio(info.audioStreams.orEmpty())
        val audio = audioStreams.firstOrNull()?.let(::toDownloadStream)
        val videos = if (audio == null) emptyList() else bestPerQuality(info.videoOnlyStreams.orEmpty()).map { (quality, fps, stream) ->
            VideoDownloadOption(quality, fps, toDownloadStream(stream)!!, audio)
        }
        return DownloadOptions(videos, audio, info.duration)
    }

    private data class Candidate(val quality: Int, val fps: Int, val stream: VideoStream)

    /** One H.264 MP4 stream per quality, best quality first: the highest frame rate, then the highest bitrate. */
    private fun bestPerQuality(streams: List<VideoStream>): List<Candidate> = streams
        .filter { it.isVideoOnly() && it.isUsable() && it.format == MediaFormat.MPEG_4 && isAvc(it.itagItem?.codec) }
        .map { Candidate(quality(it), it.itagItem?.getFps()?.takeIf { f -> f > 0 } ?: it.fps.takeIf { f -> f > 0 } ?: 30, it) }
        .filter { it.quality > 0 }
        .groupBy { it.quality }
        .map { (_, group) -> group.maxWith(compareBy<Candidate> { it.fps }.thenBy { it.stream.itagItem?.bitrate ?: it.stream.bitrate }) }
        .sortedByDescending { it.quality }

    /** The short side, like the player's quality label; the height alone when the width is unknown. */
    private fun quality(stream: VideoStream): Int {
        val itag = stream.itagItem
        val width = itag?.width ?: 0
        val height = itag?.height?.takeIf { it > 0 } ?: stream.heightOrResolution()
        return if (width > 0 && height > 0) minOf(width, height) else height
    }

    private fun isAvc(codec: String?): Boolean {
        val family = codec.orEmpty().substringBefore('.').lowercase()
        return family == "avc1" || family == "avc3"
    }

    /**
     * M4A streams of the original audio track (a video with auto-dubs lists dozens of tracks), best bitrate first, without
     * the "stable volume" (DRC) variants when normal ones exist.
     */
    fun preferredAudio(streams: List<AudioStream>): List<AudioStream> {
        val m4a = streams.filter { it.format == MediaFormat.M4A && it.isUsable() }
        if (m4a.isEmpty()) return emptyList()
        val tracks = m4a.groupBy { it.audioTrackId }
        val chosen = tracks.entries.firstOrNull { (_, s) -> s.any { it.audioTrackType == AudioTrackType.ORIGINAL } }
            ?: tracks.entries.firstOrNull { (id, _) -> id == null }
            ?: tracks.entries.firstOrNull { (_, s) -> s.none { it.audioTrackType == AudioTrackType.DUBBED || it.audioTrackType == AudioTrackType.DESCRIPTIVE } }
            ?: tracks.entries.first()
        val normal = chosen.value.filter { it.itagItem?.isDrc != true }.ifEmpty { chosen.value }
        return normal.sortedByDescending { it.itagItem?.bitrate?.takeIf { b -> b > 0 } ?: (it.averageBitrate * 1000) }
    }

    /** A plain file at a URL (not an OTF/DASH-manifest stream, not HLS). */
    private fun Stream.isUsable(): Boolean =
        isUrl && deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && !content.isNullOrBlank()

    private fun toDownloadStream(stream: Stream): DownloadStream? {
        val itag = stream.itagItem ?: return null
        val url = stream.content.takeIf { it.isNotBlank() } ?: return null
        val mime = stream.format?.mimeType ?: return null
        val size = itag.contentLength.takeIf { it > 0 } ?: contentLengthOf(url)
        return DownloadStream(
            url = url,
            mimeType = mime,
            codecs = itag.codec.orEmpty(),
            bitrate = itag.bitrate.takeIf { it > 0 } ?: (itag.averageBitrate * 1000),
            sizeBytes = size,
            itag = itag.id,
        )
    }

    /** googlevideo URLs carry the file's length as `clen`. */
    internal fun contentLengthOf(url: String): Long? =
        url.toHttpUrlOrNull()?.queryParameter("clen")?.toLongOrNull()?.takeIf { it > 0 }
}
