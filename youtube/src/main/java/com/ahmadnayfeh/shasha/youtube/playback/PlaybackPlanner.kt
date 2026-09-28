package com.ahmadnayfeh.shasha.youtube.playback

import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import com.ahmadnayfeh.shasha.youtube.SourceErrorKind
import com.ahmadnayfeh.shasha.youtube.SourceException
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.services.youtube.ItagItem
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.CreationException
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubeOtfDashManifestCreator
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubePostLiveStreamDvrDashManifestCreator
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubeProgressiveDashManifestCreator
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.Stream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.SubtitlesStream
import org.schabi.newpipe.extractor.stream.VideoStream

/** One Media3 source to build. Several parts are merged into one MergingMediaSource. */
internal sealed interface MediaPart {
    /** A ready DASH manifest: our multi-quality MPD, or NPE's single-stream one. */
    data class Dash(val manifest: String, val kind: String) : MediaPart

    /** A DASH manifest that needs network to build (OTF, ended live); built on Media3's loader thread. */
    class LazyDash(val kind: String, val create: () -> String) : MediaPart

    /** A single file read progressively with range requests. */
    data class Progressive(val url: String, val kind: String) : MediaPart

    data class Hls(val url: String, val live: Boolean) : MediaPart

    data class LiveDash(val url: String) : MediaPart
}

internal data class SubtitlePart(val url: String, val mimeType: String, val language: String, val label: String)

internal data class PlaybackPlan(val parts: List<MediaPart>, val subtitle: SubtitlePart?, val isLive: Boolean)

/**
 * Decides how a [StreamInfo] is played (pure, apart from NPE's offline progressive manifest creator):
 * - VOD: one generated MPD with every decodable video-only quality ≤ maxHeight (ABR) + the original
 *   audio track; per-stream fallbacks (NPE manifest creators, progressive) for streams without index ranges;
 *   muxed progressive as the last resort, then HLS.
 * - Live: HLS, else the DASH manifest URL. Ended live (post-live DVR) and OTF: NPE's manifest creators.
 */
@OptIn(UnstableApi::class)
internal class PlaybackPlanner(private val decoders: VideoDecoderCheck) {

    fun plan(info: StreamInfo, maxHeight: Int, subtitleLanguage: String?): PlaybackPlan = when (info.streamType) {
        StreamType.LIVE_STREAM, StreamType.AUDIO_LIVE_STREAM -> livePlan(info)
        StreamType.POST_LIVE_STREAM, StreamType.POST_LIVE_AUDIO_STREAM ->
            PlaybackPlan(postLiveParts(info, maxHeight), subtitle(info, subtitleLanguage), isLive = false)
        else -> PlaybackPlan(vodParts(info, maxHeight), subtitle(info, subtitleLanguage), isLive = false)
    }

    private fun livePlan(info: StreamInfo): PlaybackPlan {
        val part = info.hlsUrl?.takeIf { it.isNotBlank() }?.let { MediaPart.Hls(it, live = true) }
            ?: info.dashMpdUrl?.takeIf { it.isNotBlank() }?.let { MediaPart.LiveDash(it) }
            ?: throw broken("NoLiveManifest", "Live stream ${info.id} has neither an HLS nor a DASH manifest")
        return PlaybackPlan(listOf(part), subtitle = null, isLive = true)
    }

    private fun vodParts(info: StreamInfo, maxHeight: Int): List<MediaPart> {
        val durationSeconds = info.duration
        val videos = selectVideos(info.videoOnlyStreams.orEmpty(), maxHeight)
        val audios = preferredAudio(info.audioStreams.orEmpty())
        val rangedVideos = videos.filter { it.stream.hasIndexRanges() }
        val rangedAudios = audios.filter { it.hasIndexRanges() }

        val parts = mutableListOf<MediaPart>()
        var hasVideo = rangedVideos.isNotEmpty()
        var muxed = false
        val separateVideo = if (rangedVideos.isEmpty()) videos.firstNotNullOfOrNull { singleStreamPart(it.stream, durationSeconds) } else null
        if (separateVideo != null) {
            hasVideo = true
        } else if (!hasVideo) {
            bestMuxed(info.videoStreams.orEmpty(), maxHeight)?.let {
                parts += MediaPart.Progressive(it.content, "muxed ${it.getResolution()}")
                hasVideo = true
                muxed = true
            }
        }

        val manifestAudios = if (muxed) emptyList() else rangedAudios
        if (rangedVideos.isNotEmpty() || manifestAudios.isNotEmpty()) {
            parts.add(0, MediaPart.Dash(generatedManifest(rangedVideos, manifestAudios, durationSeconds), "generated"))
        }
        separateVideo?.let { parts += it }
        if (!muxed && manifestAudios.isEmpty()) {
            audios.firstNotNullOfOrNull { singleStreamPart(it, durationSeconds) }?.let { parts += it }
        }

        if (!hasVideo) {
            // No usable video stream: an HLS manifest (visionOS/iOS) is still better than audio only.
            info.hlsUrl?.takeIf { it.isNotBlank() }?.let { return listOf(MediaPart.Hls(it, live = false)) }
            if (info.streamType != StreamType.AUDIO_STREAM || parts.isEmpty()) {
                throw broken("NoPlayableStreams", "No playable video stream for ${info.id}")
            }
        }
        return parts
    }

    private fun postLiveParts(info: StreamInfo, maxHeight: Int): List<MediaPart> {
        val durationSeconds = info.duration
        val video = selectVideos(info.videoOnlyStreams.orEmpty(), maxHeight).firstNotNullOfOrNull { postLivePart(it.stream, durationSeconds) }
        val audio = preferredAudio(info.audioStreams.orEmpty()).firstNotNullOfOrNull { postLivePart(it, durationSeconds) }
        if (video != null) return listOfNotNull(video, audio)
        info.hlsUrl?.takeIf { it.isNotBlank() }?.let { return listOf(MediaPart.Hls(it, live = false)) }
        if (audio != null && info.streamType == StreamType.POST_LIVE_AUDIO_STREAM) return listOf(audio)
        throw broken("NoPlayableStreams", "No playable stream for ended live ${info.id}")
    }

    private fun postLivePart(stream: Stream, durationSeconds: Long): MediaPart? {
        val itag = stream.itagItem ?: return null
        val url = stream.content.takeIf { it.isNotBlank() } ?: return null
        return MediaPart.LazyDash("post-live ${itag.id}") {
            YoutubePostLiveStreamDvrDashManifestCreator.fromPostLiveStreamDvrStreamingUrl(
                url, itag, itag.targetDurationSec, durationSeconds,
            )
        }
    }

    /** One stream as its own source: OTF → NPE OTF manifest; progressive → NPE manifest, else plain progressive. */
    private fun singleStreamPart(stream: Stream, durationSeconds: Long): MediaPart? {
        val url = stream.content.takeIf { it.isNotBlank() } ?: return null
        val itag = stream.itagItem
        return when {
            stream.deliveryMethod == DeliveryMethod.DASH && !stream.isUrl && itag != null ->
                MediaPart.LazyDash("otf ${itag.id}") { YoutubeOtfDashManifestCreator.fromOtfStreamingUrl(url, itag, durationSeconds) }
            stream.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && stream.isUrl -> {
                val manifest = itag?.let {
                    try {
                        YoutubeProgressiveDashManifestCreator.fromProgressiveStreamingUrl(url, it, durationSeconds)
                    } catch (e: CreationException) {
                        null // e.g. no index ranges: ExoPlayer can still read the file progressively
                    }
                }
                if (manifest != null) MediaPart.Dash(manifest, "npe-progressive ${stream.id}")
                else MediaPart.Progressive(url, "progressive ${stream.id}")
            }
            stream.deliveryMethod == DeliveryMethod.HLS && stream.isUrl -> MediaPart.Hls(url, live = false)
            else -> null
        }
    }

    // --- video ---------------------------------------------------------------------------------

    internal data class VideoCandidate(
        val stream: VideoStream,
        val codecs: String,
        val width: Int,
        val height: Int,
        val fps: Int,
        val bitrate: Int,
        val decoder: DecoderKind,
    ) {
        /** YouTube's quality label ("1080p") is the short side: vertical videos are 1080x1920. */
        val quality: Int get() = if (width > 0 && height > 0) minOf(width, height) else height
    }

    /**
     * Video-only streams to offer, best first:
     * - only what the device decodes in hardware (software decoding stutters on TV CPUs); if nothing has
     *   a hardware decoder, software-decodable ones except AV1; if the device reports nothing, H.264.
     * - no HDR (SDR versions always exist);
     * - quality (short side) ≤ [maxHeight], or the lowest quality when nothing fits.
     */
    internal fun selectVideos(streams: List<VideoStream>, maxHeight: Int): List<VideoCandidate> {
        val all = streams.filter { it.isVideoOnly() }.mapNotNull(::candidate)
        if (all.isEmpty()) return emptyList()
        val sdr = all.filterNot { VideoCodecs.isHdr(it.codecs) }.ifEmpty { all }
        val decodable = sdr.filter { it.decoder == DecoderKind.HARDWARE }
            .ifEmpty { sdr.filter { it.decoder == DecoderKind.SOFTWARE && VideoCodecs.family(it.codecs) != "av01" } }
            .ifEmpty { sdr.filter { VideoCodecs.family(it.codecs) == "avc1" } }
            .ifEmpty { sdr }
        val capped = if (maxHeight <= 0) decodable else decodable.filter { it.quality <= maxHeight }
            .ifEmpty { decodable.minOf { it.quality }.let { lowest -> decodable.filter { it.quality == lowest } } }
        return capped.sortedWith(
            compareByDescending<VideoCandidate> { it.quality }
                .thenByDescending { it.fps }
                .thenBy { codecRank(it.codecs) }
                .thenByDescending { it.bitrate },
        )
    }

    private fun candidate(stream: VideoStream): VideoCandidate? {
        val itag = stream.itagItem
        val codecs = itag?.codec?.takeIf { it.isNotBlank() } ?: guessCodecs(stream.format) ?: return null
        val width = itag?.width ?: 0
        val height = itag?.height?.takeIf { it > 0 } ?: stream.height.takeIf { it > 0 }
            ?: stream.getResolution().substringBefore('p').toIntOrNull() ?: 0
        val fps = itag?.getFps()?.takeIf { it > 0 } ?: stream.fps.takeIf { it > 0 } ?: 30
        val bitrate = itag?.bitrate?.takeIf { it > 0 } ?: stream.bitrate
        return VideoCandidate(stream, codecs, width, height, fps, bitrate, decoders.check(codecs, width, height, fps))
    }

    /** H.264 first on ties: every TV decodes it cheaply. */
    private fun codecRank(codecs: String) = when (VideoCodecs.family(codecs)) {
        "avc1" -> 0
        "vp9" -> 1
        "av01" -> 2
        else -> 3
    }

    private fun guessCodecs(format: MediaFormat?): String? = when (format) {
        MediaFormat.WEBM -> "vp9"
        MediaFormat.MPEG_4 -> "avc1"
        else -> null
    }

    private fun bestMuxed(streams: List<VideoStream>, maxHeight: Int): VideoStream? {
        val usable = streams.filter { !it.isVideoOnly() && it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && !it.content.isNullOrBlank() }
        if (usable.isEmpty()) return null
        fun VideoStream.h() = height.takeIf { it > 0 } ?: getResolution().substringBefore('p').toIntOrNull() ?: 0
        val fitting = usable.filter { maxHeight <= 0 || it.h() <= maxHeight }
        return fitting.maxByOrNull { it.h() } ?: usable.minByOrNull { it.h() }
    }

    // --- audio ---------------------------------------------------------------------------------

    /**
     * The original audio track (videos with auto-dubs list dozens of tracks), best bitrate first,
     * without the "stable volume" (DRC) variants when normal ones exist.
     */
    internal fun preferredAudio(streams: List<AudioStream>): List<AudioStream> {
        if (streams.isEmpty()) return emptyList()
        val tracks = streams.groupBy { it.audioTrackId }
        val chosen = tracks.entries.firstOrNull { (_, s) -> s.any { it.audioTrackType == AudioTrackType.ORIGINAL } }
            ?: tracks.entries.firstOrNull { (id, _) -> id == null }
            ?: tracks.entries.firstOrNull { (_, s) -> s.none { it.audioTrackType == AudioTrackType.DUBBED || it.audioTrackType == AudioTrackType.DESCRIPTIVE } }
            ?: tracks.entries.first()
        val track = chosen.value
        val normal = track.filter { it.itagItem?.isDrc != true }.ifEmpty { track }
        return normal.sortedByDescending { it.itagItem?.bitrate?.takeIf { b -> b > 0 } ?: (it.averageBitrate * 1000) }
    }

    // --- generated MPD ------------------------------------------------------------------------

    private fun Stream.hasIndexRanges(): Boolean {
        val itag = itagItem ?: return false
        return isUrl && deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && !content.isNullOrBlank() &&
            itag.initStart >= 0 && itag.initEnd > itag.initStart &&
            itag.indexStart > itag.initEnd && itag.indexEnd > itag.indexStart &&
            !itag.codec.isNullOrBlank() && itag.bitrate > 0 && format?.mimeType != null
    }

    private fun generatedManifest(videos: List<VideoCandidate>, audios: List<AudioStream>, durationSeconds: Long): String {
        val ids = HashSet<String>()
        fun uniqueId(base: String): String {
            var id = base
            var n = 1
            while (!ids.add(id)) id = "$base-${n++}"
            return id
        }

        val videoSets = videos
            .groupBy { it.stream.format!!.mimeType to VideoCodecs.family(it.codecs) }
            .map { (key, group) ->
                DashAdaptationSet(
                    type = DashContentType.VIDEO,
                    mimeType = key.first,
                    representations = group.sortedByDescending { it.bitrate }.map { c ->
                        val itag = c.stream.itagItem!!
                        representation(uniqueId("v${itag.id}"), c.stream.content, itag)
                            .copy(width = c.width, height = c.height, fps = c.fps)
                    },
                )
            }
        val audioSets = audios
            .groupBy { it.format!!.mimeType }
            .map { (mimeType, group) ->
                DashAdaptationSet(
                    type = DashContentType.AUDIO,
                    mimeType = mimeType,
                    language = group.firstNotNullOfOrNull { it.audioLocale?.language?.takeIf(String::isNotBlank) },
                    representations = group.map { a ->
                        val itag = a.itagItem!!
                        representation(uniqueId("a${itag.id}"), a.content, itag)
                            .copy(sampleRate = itag.sampleRate.coerceAtLeast(0), channels = itag.audioChannels.coerceAtLeast(0))
                    },
                )
            }

        val itagDurationMs = (videos.map { it.stream.itagItem!! } + audios.map { it.itagItem!! })
            .maxOfOrNull { it.approxDurationMs } ?: -1L
        val durationMs = if (itagDurationMs > 0) itagDurationMs else durationSeconds * 1000
        return DashManifestWriter.write(durationMs, videoSets + audioSets)
    }

    private fun representation(id: String, url: String, itag: ItagItem) = DashRepresentation(
        id = id,
        url = url,
        codecs = itag.codec,
        bandwidth = itag.bitrate,
        initStart = itag.initStart.toLong(),
        initEnd = itag.initEnd.toLong(),
        indexStart = itag.indexStart.toLong(),
        indexEnd = itag.indexEnd.toLong(),
    )

    // --- subtitles -----------------------------------------------------------------------------

    /** The chosen language: an exact tag match first ("en"), then a regional one ("en-GB"); human-made first. */
    internal fun subtitle(info: StreamInfo, language: String?): SubtitlePart? {
        if (language.isNullOrBlank()) return null
        val all = info.subtitles.orEmpty().filter { !it.content.isNullOrBlank() && mimeTypeOf(it) != null }
        val matches = all.filter { it.languageTag.equals(language, ignoreCase = true) }
            .ifEmpty { all.filter { it.languageTag.orEmpty().startsWith("$language-", ignoreCase = true) } }
        val chosen = matches.firstOrNull { !it.isAutoGenerated } ?: matches.firstOrNull() ?: return null
        return SubtitlePart(
            url = chosen.content,
            mimeType = mimeTypeOf(chosen)!!,
            language = chosen.languageTag,
            label = chosen.displayLanguageName?.takeIf { it.isNotBlank() } ?: chosen.languageTag,
        )
    }

    private fun mimeTypeOf(subtitle: SubtitlesStream): String? = when (subtitle.format) {
        MediaFormat.TTML -> MimeTypes.APPLICATION_TTML
        MediaFormat.VTT -> MimeTypes.TEXT_VTT
        MediaFormat.SRT -> MimeTypes.APPLICATION_SUBRIP
        else -> null
    }

    private fun broken(tag: String, message: String) =
        SourceException(SourceErrorKind.EXTRACTION_BROKEN, tag, message, null)
}
