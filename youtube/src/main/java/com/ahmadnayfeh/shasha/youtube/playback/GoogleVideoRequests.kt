package com.ahmadnayfeh.shasha.youtube.playback

import androidx.media3.common.C
import com.ahmadnayfeh.shasha.youtube.newpipe.NewPipeDownloader
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper

/**
 * How requests to googlevideo "/videoplayback" URLs are shaped. YouTube's own apps fetch media this
 * way; requests that look different (plain GET, Range header, wrong User-Agent) get throttled or
 * refused with HTTP 403 more often.
 */
internal data class GoogleVideoQuirks(
    /** Byte range as "&range=a-b" in the URL instead of a Range header. */
    val rangeParameter: Boolean,
    /** Appends "&rn=<n>": a request counter per data source. */
    val requestNumber: Boolean,
    /** POST with the two-byte body {0x78, 0} instead of GET. */
    val post: Boolean,
) {
    companion object {
        /** Byte-range segments: our generated MPD, and NPE's OTF / post-live / progressive manifests. */
        val DASH = GoogleVideoQuirks(rangeParameter = true, requestNumber = true, post = true)

        /** A whole file read progressively (muxed fallback, or one stream without an index). */
        val PROGRESSIVE = GoogleVideoQuirks(rangeParameter = false, requestNumber = true, post = true)

        /** Live HLS/DASH: playlists and segments carry their parameters in the path; keep them as they are. */
        val LIVE = GoogleVideoQuirks(rangeParameter = false, requestNumber = false, post = false)
    }
}

/** A request ready to be sent. [rangeInUrl]: the response body starts exactly at the requested position. */
internal class PlannedRequest(
    val url: String,
    val method: String,
    val body: ByteArray?,
    val headers: Map<String, String>,
    val rangeInUrl: Boolean,
)

internal object GoogleVideoRequests {

    val POST_BODY = byteArrayOf(0x78, 0)
    private const val YOUTUBE_ORIGIN = "https://www.youtube.com"

    fun isVideoPlayback(url: HttpUrl): Boolean =
        (url.host == "googlevideo.com" || url.host.endsWith(".googlevideo.com")) &&
            url.encodedPath.startsWith("/videoplayback")

    /**
     * @param position byte offset, 0 for the start.
     * @param length byte count, or [C.LENGTH_UNSET] for "to the end".
     * @param nextRequestNumber called once when an "rn" parameter is added.
     */
    fun plan(
        url: String,
        position: Long,
        length: Long,
        quirks: GoogleVideoQuirks,
        nextRequestNumber: () -> Long,
    ): PlannedRequest {
        val parsed = url.toHttpUrlOrNull()
        val headers = LinkedHashMap<String, String>()
        if (parsed == null || !isVideoPlayback(parsed)) {
            rangeHeader(position, length)?.let { headers["Range"] = it }
            headers["User-Agent"] = NewPipeDownloader.USER_AGENT
            return PlannedRequest(url, "GET", null, headers, rangeInUrl = false)
        }

        // Plain string appends, like YouTube's players: re-encoding the URL could alter the signature.
        var target = url
        fun appendParam(name: String, value: String) {
            target += (if (target.contains('?')) "&" else "?") + name + "=" + value
        }
        if (quirks.requestNumber && parsed.queryParameter("rn") == null) {
            appendParam("rn", nextRequestNumber().toString())
        }
        val range = rangeValue(position, length)
        val rangeInUrl = quirks.rangeParameter && range != null && parsed.queryParameter("range") == null
        when {
            rangeInUrl -> appendParam("range", range)
            range != null -> headers["Range"] = "bytes=$range"
        }

        val client = clientName(parsed)
        headers["User-Agent"] = userAgentFor(client)
        if (client != null && (client.startsWith("WEB") || client.startsWith("MWEB"))) {
            headers["Origin"] = YOUTUBE_ORIGIN
            headers["Referer"] = "$YOUTUBE_ORIGIN/"
        }
        return if (quirks.post) {
            PlannedRequest(target, "POST", POST_BODY, headers, rangeInUrl)
        } else {
            PlannedRequest(target, "GET", null, headers, rangeInUrl)
        }
    }

    /** The InnerTube client that produced the URL: "c=ANDROID" in the query, or ".../c/IOS/..." in the path. */
    fun clientName(url: HttpUrl): String? {
        url.queryParameter("c")?.let { return it.uppercase() }
        val segments = url.pathSegments
        val index = segments.indexOf("c")
        return if (index >= 0 && index + 1 < segments.size) segments[index + 1].uppercase() else null
    }

    /**
     * googlevideo checks that media requests come from the same kind of client as the player request.
     * NPE builds those User-Agents; using its helpers keeps them in step with extractor upgrades.
     */
    fun userAgentFor(client: String?): String {
        val localization = NewPipe.getPreferredLocalization()
        return when (client) {
            "ANDROID" -> YoutubeParsingHelper.getAndroidUserAgent(localization)
            "IOS" -> YoutubeParsingHelper.getIosUserAgent(localization)
            "VISIONOS" -> YoutubeParsingHelper.getVisionOsUserAgent(localization)
            else -> NewPipeDownloader.USER_AGENT
        }
    }

    /** "a-b" (inclusive), "a-" (open ended), or null for the whole resource. */
    private fun rangeValue(position: Long, length: Long): String? = when {
        position == 0L && length == C.LENGTH_UNSET.toLong() -> null
        length == C.LENGTH_UNSET.toLong() -> "$position-"
        else -> "$position-${position + length - 1}"
    }

    private fun rangeHeader(position: Long, length: Long): String? = rangeValue(position, length)?.let { "bytes=$it" }
}
