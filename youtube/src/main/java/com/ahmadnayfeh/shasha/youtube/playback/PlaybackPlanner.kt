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
 * What the player asks of the picture beyond the quality cap (the `maxHeight` of [PlaybackPlanner.plan]).
 *
 * @property maxPortraitShortSide the short side a vertical picture (taller than wide) may have, whatever the quality cap;
 * ≤ 0: none. The TV asks for 720: a 9:16 picture is 607 px wide on a 1080p screen, so more pixels are never seen, and the
 * decoders of weak TV SoCs fail on the biggest vertical ones. A phone shows vertical video natively and asks for none.
 * @property safe the retry after a decoder failure ([DecoderRetry]): H.264 only, and on the short side at most
 * [SafePlan.MAX_PORTRAIT_SHORT_SIDE] for a vertical picture and [SafePlan.MAX_LANDSCAPE_SHORT_SIDE] for a horizontal one.
 */
internal data class PlanLimits(val maxPortraitShortSide: Int = 0, val safe: Boolean = false) {
    /** The highest quality (short side) a picture of this orientation may have under the overall cap [maxHeight] (≤ 0: none). */
    fun shortSideCap(portrait: Boolean, maxHeight: Int): Int = listOfNotNull(
        maxHeight.takeIf { it > 0 },
        maxPortraitShortSide.takeIf { portrait && it > 0 },
        if (!safe) null else if (portrait) SafePlan.MAX_PORTRAIT_SHORT_SIDE else SafePlan.MAX_LANDSCAPE_SHORT_SIDE,
    ).minOrNull() ?: Int.MAX_VALUE
}

/**
 * A muxed-only result at or below this height is a broken extraction (SPEC: "360p only" → error screen), unless
 * the caller allowed low quality (VideoSource.stream's allowLowQuality: then it plays, and the app shows it).
 */
internal const val LOW_QUALITY_MAX_HEIGHT = 360

internal fun lowQualityOnly(bestHeight: Int) = SourceException(
    SourceErrorKind.LOW_QUALITY_ONLY, "MuxedOnly360p",
    "Only muxed streams up to ${bestHeight}p, and no adaptive streams or HLS manifest", null,
)

/** YouTube's quality label: the stream's height, or the number in its resolution ("360p"). */
internal fun VideoStream.heightOrResolution(): Int =
    height.takeIf { it > 0 } ?: getResolution().substringBefore('p').toIntOrNull() ?: 0

/**
 * Decides how a [StreamInfo] is played (pure, apart from NPE's offline progressive manifest creator):
 * - VOD: one generated MPD with every decodable video-only quality ≤ maxHeight (ABR) + the original
 *   audio track; per-stream fallbacks (NPE manifest creators, progressive) for streams without index ranges.
 *   Without adaptive video, or without any audio for it: YouTube's HLS manifest (the ladder, with sound).
 *   Muxed progressive is the last resort, and only above 360p: muxed 360p alone is the signature of a
 *   broken extraction and must reach the error screen instead of playing silently at 360p, unless
 *   `allowLowQuality` (the handle of a stream extracted with it, marked for the app to show). Video
 *   without audio is never played (a mute picture is a failure too).
 * - Live: HLS, else the DASH manifest URL. Ended live (post-live DVR) and OTF: NPE's manifest creators.
 *
 * The video qualities also answer to [PlanLimits] (a cap for vertical pictures, the safe plan) and to [failures]: what a
 * decoder of this device already failed on in this run is left out of every later plan ([selectVideos]).
 *
 * StreamMapper.checkPlayable applies the same rules when the stream is extracted, so its verdict
 * (e.g. when the policy looks a video up) is the one the player would reach.
 */
@OptIn(UnstableApi::class)
internal class PlaybackPlanner(
    private val decoders: VideoDecoderCheck,
    private val failures: DecoderFailures = DecoderFailures(),
) {

    fun plan(
        info: StreamInfo,
        maxHeight: Int,
        subtitleLanguage: String?,
        allowLowQuality: Boolean = false,
        limits: PlanLimits = PlanLimits(),
    ): PlaybackPlan =
        when (info.streamType) {
            StreamType.LIVE_STREAM, StreamType.AUDIO_LIVE_STREAM -> livePlan(info)
            StreamType.POST_LIVE_STREAM, StreamType.POST_LIVE_AUDIO_STREAM ->
                PlaybackPlan(postLiveParts(info, maxHeight, allowLowQuality, limits), subtitle(info, subtitleLanguage), isLive = false)
            else -> PlaybackPlan(vodParts(info, maxHeight, allowLowQuality, limits), subtitle(info, subtitleLanguage), isLive = false)
        }

    private fun livePlan(info: StreamInfo): PlaybackPlan {
        val part = info.hlsUrl?.takeIf { it.isNotBlank() }?.let { MediaPart.Hls(it, live = true) }
            ?: info.dashMpdUrl?.takeIf { it.isNotBlank() }?.let { MediaPart.LiveDash(it) }
            ?: throw broken("NoLiveManifest", "Live stream ${info.id} has neither an HLS nor a DASH manifest")
        return PlaybackPlan(listOf(part), subtitle = null, isLive = true)
    }

    private fun vodParts(info: StreamInfo, maxHeight: Int, allowLowQuality: Boolean, limits: PlanLimits): List<MediaPart> {
        val durationSeconds = info.duration
        val videos = selectVideos(info.videoOnlyStreams.orEmpty(), maxHeight, limits)
        val audios = preferredAudio(info.audioStreams.orEmpty())
        val rangedVideos = videos.filter { it.stream.hasIndexRanges() }
        val rangedAudios = audios.filter { it.hasIndexRanges() }
        // Streams without index ranges (OTF, or ranges missing) are played as their own sources.
        val separateVideo = if (rangedVideos.isEmpty()) videos.firstNotNullOfOrNull { singleStreamPart(it.stream, durationSeconds) } else null
        val separateAudio = if (rangedAudios.isEmpty()) audios.firstNotNullOfOrNull { singleStreamPart(it, durationSeconds) } else null
        val hasVideo = rangedVideos.isNotEmpty() || separateVideo != null
        val hasAudio = rangedAudios.isNotEmpty() || separateAudio != null

        fun manifest(videos: List<VideoCandidate>) = MediaPart.Dash(generatedManifest(videos, rangedAudios, durationSeconds), "generated")
            .takeIf { videos.isNotEmpty() || rangedAudios.isNotEmpty() }

        if (hasVideo && hasAudio) return listOfNotNull(manifest(rangedVideos), separateVideo, separateAudio)
        val audioOnly = if (info.streamType == StreamType.AUDIO_STREAM && hasAudio) listOfNotNull(manifest(emptyList()), separateAudio) else emptyList()
        return fallbackParts(info, maxHeight, videoWithoutAudio = hasVideo, audioOnly = audioOnly, allowLowQuality = allowLowQuality)
    }

    private fun postLiveParts(info: StreamInfo, maxHeight: Int, allowLowQuality: Boolean, limits: PlanLimits): List<MediaPart> {
        val durationSeconds = info.duration
        val video = selectVideos(info.videoOnlyStreams.orEmpty(), maxHeight, limits).firstNotNullOfOrNull { postLivePart(it.stream, durationSeconds) }
        val audio = preferredAudio(info.audioStreams.orEmpty()).firstNotNullOfOrNull { postLivePart(it, durationSeconds) }
        if (video != null && audio != null) return listOf(video, audio)
        val audioOnly = if (info.streamType == StreamType.POST_LIVE_AUDIO_STREAM) listOfNotNull(audio) else emptyList()
        return fallbackParts(info, maxHeight, videoWithoutAudio = video != null, audioOnly = audioOnly, allowLowQuality = allowLowQuality)
    }

    /**
     * No adaptive video with sound. YouTube's HLS manifest (visionOS/iOS) has the whole ladder with audio;
     * without it, a picture without sound is a broken extraction; audio-only content plays as audio; muxed
     * progressive is the last resort, above 360p only (or at any height when [allowLowQuality]).
     */
    private fun fallbackParts(
        info: StreamInfo,
        maxHeight: Int,
        videoWithoutAudio: Boolean,
        audioOnly: List<MediaPart>,
        allowLowQuality: Boolean,
    ): List<MediaPart> {
        info.hlsUrl?.takeIf { it.isNotBlank() }?.let { return listOf(MediaPart.Hls(it, live = false)) }
        if (videoWithoutAudio) throw broken("NoAudioStreams", "Video-only streams but no playable audio stream for ${info.id}")
        if (audioOnly.isNotEmpty()) return audioOnly
        val muxed = info.videoStreams.orEmpty().filter { it.isPlayableMuxed() }
        if (muxed.isNotEmpty()) {
            val best = muxed.maxOf { it.heightOrResolution() }
            if (best <= LOW_QUALITY_MAX_HEIGHT && !allowLowQuality) throw lowQualityOnly(best)
            val chosen = bestMuxed(muxed, maxHeight)
            return listOf(MediaPart.Progressive(chosen.content, "muxed ${chosen.getResolution()}"))
        }
        throw broken("NoPlayableStreams", "No playable video stream for ${info.id}")
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

        /** A vertical picture (a Short): taller than wide. A size the stream does not say is not vertical. */
        val isPortrait: Boolean get() = width > 0 && height > width
    }

    /**
     * Video-only streams to offer, best first:
     * - no HDR (SDR versions always exist);
     * - nothing a decoder of this device already failed on in this run ([failures]: that codec's pictures from the size that
     *   failed up, in that orientation); if that leaves nothing, the failures are not held against the video, it is
     *   better to try than to give up;
     * - with [PlanLimits.safe], H.264 only (unless the video has none);
     * - only what the device decodes in hardware (software decoding stutters on TV CPUs); if nothing has
     *   a hardware decoder, software-decodable ones except AV1; if the device reports nothing, H.264.
     * - quality (short side) ≤ [maxHeight] and ≤ what [limits] allow for its orientation, or the lowest quality when
     *   nothing fits.
     */
    internal fun selectVideos(streams: List<VideoStream>, maxHeight: Int, limits: PlanLimits = PlanLimits()): List<VideoCandidate> {
        val all = streams.filter { it.isVideoOnly() }.mapNotNull(::candidate)
        if (all.isEmpty()) return emptyList()
        val sdr = all.filterNot { VideoCodecs.isHdr(it.codecs) }.ifEmpty { all }
        val untried = sdr.filterNot { failures.rulesOut(it.codecs, it.width, it.height) }.ifEmpty { sdr }
        val allowed = if (limits.safe) untried.filter { VideoCodecs.family(it.codecs) == "avc1" }.ifEmpty { untried } else untried
        val decodable = allowed.filter { it.decoder == DecoderKind.HARDWARE }
            .ifEmpty { allowed.filter { it.decoder == DecoderKind.SOFTWARE && VideoCodecs.family(it.codecs) != "av01" } }
            .ifEmpty { allowed.filter { VideoCodecs.family(it.codecs) == "avc1" } }
            .ifEmpty { allowed }
        val capped = decodable.filter { it.quality <= limits.shortSideCap(it.isPortrait, maxHeight) }
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

    private fun VideoStream.isPlayableMuxed() =
        !isVideoOnly() && isUrl && deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && !content.isNullOrBlank()

    /** The best of [muxed] (not empty) within [maxHeight], or the lowest when none fits. */
    private fun bestMuxed(muxed: List<VideoStream>, maxHeight: Int): VideoStream =
        muxed.filter { maxHeight <= 0 || it.heightOrResolution() <= maxHeight }.maxByOrNull { it.heightOrResolution() }
            ?: muxed.minBy { it.heightOrResolution() }

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
