package com.ahmadnayfeh.shasha.youtube.newpipe

import com.ahmadnayfeh.shasha.youtube.PlaybackHandle
import com.ahmadnayfeh.shasha.youtube.SourceErrorKind
import com.ahmadnayfeh.shasha.youtube.SourceException
import com.ahmadnayfeh.shasha.youtube.StreamDetails
import com.ahmadnayfeh.shasha.youtube.SubtitleTrack
import com.ahmadnayfeh.shasha.youtube.VideoItem
import com.ahmadnayfeh.shasha.youtube.VideoTrack
import com.ahmadnayfeh.shasha.youtube.YouTubeLinkParser
import com.ahmadnayfeh.shasha.youtube.newpipe.ItemMapper.epochMs
import com.ahmadnayfeh.shasha.youtube.newpipe.ItemMapper.isLive
import com.ahmadnayfeh.shasha.youtube.newpipe.ItemMapper.nonNegativeOrNull
import com.ahmadnayfeh.shasha.youtube.newpipe.ItemMapper.positiveOrNull
import com.ahmadnayfeh.shasha.youtube.playback.LOW_QUALITY_MAX_HEIGHT
import com.ahmadnayfeh.shasha.youtube.playback.heightOrResolution
import com.ahmadnayfeh.shasha.youtube.playback.lowQualityOnly
import org.schabi.newpipe.extractor.stream.Description
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.SubtitlesStream
import org.schabi.newpipe.extractor.stream.VideoStream
import java.util.Locale

/** StreamInfo → [StreamDetails], rejecting results that cannot play properly. */
internal object StreamMapper {

    private val arabic = Locale.forLanguageTag("ar")

    fun toDetails(info: StreamInfo): StreamDetails {
        val isLive = info.streamType.isLive()
        val adaptive = info.videoOnlyStreams.orEmpty()
        val muxed = info.videoStreams.orEmpty()

        checkPlayable(summaryOf(info))?.let { throw it }

        val channelId = YouTubeLinkParser.channelIdFromUrl(info.uploaderUrl)
            ?: throw SourceException(
                SourceErrorKind.EXTRACTION_BROKEN, "MissingChannelId",
                "No channel id for video ${info.id} (uploader url: ${info.uploaderUrl})", null,
            )

        val video = VideoItem(
            videoId = info.id,
            title = info.name.orEmpty(),
            channelId = channelId,
            channelName = info.uploaderName?.takeIf { it.isNotBlank() },
            durationSeconds = if (isLive) null else info.duration.positiveOrNull(),
            publishedAtEpochMs = info.uploadDate.epochMs(),
            thumbnailUrl = ItemMapper.thumbnail(info.thumbnails),
            viewCount = info.viewCount.nonNegativeOrNull(),
            isLive = isLive,
            isShortForm = info.isShortFormContent || ItemMapper.isShortsUrl(info.originalUrl) || ItemMapper.isShortsUrl(info.url),
            url = info.url,
        )
        return StreamDetails(
            video = video,
            description = info.description?.toPlainText(),
            videoTracks = videoTracks(adaptive.ifEmpty { muxed }),
            subtitles = subtitles(info.subtitles.orEmpty()),
            playback = PlaybackHandle(info),
        )
    }

    internal data class StreamSummary(
        val isLive: Boolean,
        val hasHls: Boolean,
        val hasDash: Boolean,
        val adaptiveHeights: List<Int>,
        val muxedHeights: List<Int>,
        val hasAudio: Boolean,
    )

    internal fun summaryOf(info: StreamInfo) = StreamSummary(
        isLive = info.streamType.isLive(),
        hasHls = !info.hlsUrl.isNullOrBlank(),
        hasDash = !info.dashMpdUrl.isNullOrBlank(),
        adaptiveHeights = info.videoOnlyStreams.orEmpty().map { it.heightOrResolution() },
        muxedHeights = info.videoStreams.orEmpty().filter { !it.content.isNullOrBlank() }.map { it.heightOrResolution() },
        hasAudio = info.audioStreams.orEmpty().any { !it.content.isNullOrBlank() },
    )

    /**
     * Null when playable. The same rules as the PlaybackPlanner, which plays VOD as adaptive video + audio,
     * else HLS, else muxed progressive above 360p:
     * - video without any audio is a broken extraction unless HLS (which carries sound) can replace it;
     *   muxed streams do not count as its audio source (the player never mixes them in);
     * - only ≤360p muxed streams, with no adaptive ladder and no HLS, is the signature of a broken
     *   extraction (e.g. "made for kids" videos in v0.26.x), not a video that simply tops out at 360p.
     */
    internal fun checkPlayable(s: StreamSummary): SourceException? = when {
        s.isLive -> if (s.hasHls || s.hasDash) null else broken("NoLiveManifest", "Live stream without HLS or DASH manifest")
        s.adaptiveHeights.isNotEmpty() && s.hasAudio -> null
        s.hasHls -> null
        s.adaptiveHeights.isNotEmpty() -> broken("NoAudioStreams", "Video-only streams but no audio stream")
        s.muxedHeights.isEmpty() -> broken("NoPlayableStreams", "No video stream at all")
        s.muxedHeights.max() <= LOW_QUALITY_MAX_HEIGHT -> lowQualityOnly(s.muxedHeights.max())
        else -> null
    }

    private fun broken(tag: String, message: String) =
        SourceException(SourceErrorKind.EXTRACTION_BROKEN, tag, message, null)

    private fun videoTracks(streams: List<VideoStream>): List<VideoTrack> = streams
        .map { vs ->
            VideoTrack(
                height = vs.heightOrResolution(),
                fps = vs.fps.takeIf { it > 0 } ?: fpsFromResolution(vs.getResolution()) ?: 30,
                codec = vs.codec?.takeIf { it.isNotBlank() } ?: vs.format?.name ?: "unknown",
                bitrate = vs.bitrate.coerceAtLeast(0),
            )
        }
        .filter { it.height > 0 }
        .distinctBy { Triple(it.height, it.fps, it.codec) }
        .sortedWith(compareByDescending<VideoTrack> { it.height }.thenByDescending { it.fps }.thenByDescending { it.bitrate })

    private fun subtitles(streams: List<SubtitlesStream>): List<SubtitleTrack> = streams
        .mapNotNull { s ->
            val code = s.languageTag?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            SubtitleTrack(languageCode = code, label = languageLabel(code, s.displayLanguageName), isAutoGenerated = s.isAutoGenerated)
        }
        .distinctBy { it.languageCode to it.isAutoGenerated }
        .sortedWith(compareBy<SubtitleTrack> { it.isAutoGenerated }.thenBy { it.label })

    internal fun languageLabel(code: String, fallback: String?): String {
        val locale = Locale.forLanguageTag(code)
        val name = locale.getDisplayName(arabic)
        return when {
            name.isNotBlank() && !name.equals(code, ignoreCase = true) -> name
            !fallback.isNullOrBlank() -> fallback
            else -> code
        }
    }

    private fun fpsFromResolution(resolution: String?): Int? =
        resolution?.substringAfter('p', "")?.toIntOrNull()?.takeIf { it > 0 }

    private fun Description.toPlainText(): String? {
        val text = content?.takeIf { it.isNotBlank() } ?: return null
        return if (type == Description.HTML) HtmlText.toPlain(text) else text
    }
}

/** Minimal HTML → text for YouTube descriptions (only <br>, <a> and entities appear there). */
internal object HtmlText {
    private val lineBreak = Regex("(?i)<br\\s*/?>")
    private val tag = Regex("<[^>]+>")
    private val numericEntity = Regex("&#(x?)([0-9a-fA-F]+);")
    // "&amp;" last so "&amp;lt;" becomes "&lt;", not "<".
    private val named = listOf("&lt;" to "<", "&gt;" to ">", "&quot;" to "\"", "&apos;" to "'", "&nbsp;" to " ", "&amp;" to "&")

    fun toPlain(html: String): String {
        var s = html.replace(lineBreak, "\n").replace(tag, "")
        s = numericEntity.replace(s) { m ->
            val code = m.groupValues[2].toIntOrNull(if (m.groupValues[1].isEmpty()) 10 else 16)
            code?.let { String(Character.toChars(it)) } ?: m.value
        }
        named.forEach { (entity, value) -> s = s.replace(entity, value) }
        return s.trim()
    }
}
