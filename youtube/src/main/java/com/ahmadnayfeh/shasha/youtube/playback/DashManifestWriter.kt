package com.ahmadnayfeh.shasha.youtube.playback

import java.util.Locale

/** One byte-range addressed stream (YouTube's "adaptive format") in a generated MPD. */
internal data class DashRepresentation(
    val id: String,
    val url: String,
    val codecs: String,
    val bandwidth: Int,
    val initStart: Long,
    val initEnd: Long,
    val indexStart: Long,
    val indexEnd: Long,
    val width: Int = 0,
    val height: Int = 0,
    val fps: Int = 0,
    val sampleRate: Int = 0,
    val channels: Int = 0,
)

internal enum class DashContentType(val xml: String) { VIDEO("video"), AUDIO("audio") }

internal data class DashAdaptationSet(
    val type: DashContentType,
    val mimeType: String,
    val representations: List<DashRepresentation>,
    val language: String? = null,
)

/**
 * Writes a static on-demand MPD: every representation is one file on googlevideo, with its
 * initialization segment and segment index (sidx / WebM Cues) at known byte ranges. ExoPlayer can
 * then switch between the qualities of an adaptation set (ABR) as with any DASH stream.
 *
 * Plain string building on purpose: no DOM, cheap on a weak TV CPU.
 */
internal object DashManifestWriter {

    fun write(durationMs: Long, adaptationSets: List<DashAdaptationSet>): String = buildString {
        val duration = String.format(Locale.ROOT, "PT%.3fS", durationMs / 1000.0)
        append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        append("""<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" profiles="urn:mpeg:dash:profile:isoff-on-demand:2011" """)
        append("""type="static" minBufferTime="PT1.500S" mediaPresentationDuration="$duration">""").append('\n')
        append("""<Period id="0" start="PT0S" duration="$duration">""").append('\n')
        adaptationSets.filter { it.representations.isNotEmpty() }.forEachIndexed { index, set ->
            append("""<AdaptationSet id="$index" contentType="${set.type.xml}" mimeType="${escape(set.mimeType)}"""")
            set.language?.let { append(""" lang="${escape(it)}"""") }
            append(""" subsegmentAlignment="true" subsegmentStartsWithSAP="1">""").append('\n')
            if (set.type == DashContentType.AUDIO) {
                append("""<Role schemeIdUri="urn:mpeg:dash:role:2011" value="main"/>""").append('\n')
            }
            set.representations.forEach { appendRepresentation(set.type, it) }
            append("</AdaptationSet>\n")
        }
        append("</Period>\n</MPD>\n")
    }

    private fun StringBuilder.appendRepresentation(type: DashContentType, r: DashRepresentation) {
        append("""<Representation id="${escape(r.id)}" codecs="${escape(r.codecs)}" bandwidth="${r.bandwidth}"""")
        if (type == DashContentType.VIDEO) {
            if (r.width > 0) append(""" width="${r.width}"""")
            if (r.height > 0) append(""" height="${r.height}"""")
            if (r.fps > 0) append(""" frameRate="${r.fps}"""")
        } else if (r.sampleRate > 0) {
            append(""" audioSamplingRate="${r.sampleRate}"""")
        }
        append(">\n")
        if (type == DashContentType.AUDIO && r.channels > 0) {
            append("""<AudioChannelConfiguration schemeIdUri="urn:mpeg:dash:23003:3:audio_channel_configuration:2011" value="${r.channels}"/>""")
            append('\n')
        }
        append("<BaseURL>").append(escape(r.url)).append("</BaseURL>\n")
        append("""<SegmentBase indexRange="${r.indexStart}-${r.indexEnd}">""")
        append("""<Initialization range="${r.initStart}-${r.initEnd}"/></SegmentBase>""").append('\n')
        append("</Representation>\n")
    }

    fun escape(text: String): String {
        if (text.none { it == '&' || it == '<' || it == '>' || it == '"' || it == '\'' }) return text
        return buildString(text.length + 16) {
            for (c in text) {
                when (c) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '"' -> append("&quot;")
                    '\'' -> append("&apos;")
                    else -> append(c)
                }
            }
        }
    }
}
