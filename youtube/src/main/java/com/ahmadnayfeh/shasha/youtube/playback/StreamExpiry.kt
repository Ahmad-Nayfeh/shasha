package com.ahmadnayfeh.shasha.youtube.playback

import org.schabi.newpipe.extractor.stream.StreamInfo

/**
 * googlevideo refuses a URL (HTTP 403) once the time in its `expire` parameter has passed, about 6 h
 * after extraction: "…/videoplayback?expire=1790655850&…", or in the path of HLS/DASH manifests
 * ("…/api/manifest/hls_variant/expire/1790655988/…"). Caption URLs carry the same parameter.
 */
internal object StreamExpiry {

    private val EXPIRE = Regex("[?&]expire=(\\d{1,15})(?:[&#]|$)|/expire/(\\d{1,15})(?:[/?#]|$)")

    /** The earliest expiry (epoch ms) of every URL a player may load for [info]; null when none says. */
    fun of(info: StreamInfo): Long? {
        val urls = sequence {
            info.videoOnlyStreams.orEmpty().forEach { yield(it.content) }
            info.audioStreams.orEmpty().forEach { yield(it.content) }
            info.videoStreams.orEmpty().forEach { yield(it.content) }
            info.subtitles.orEmpty().forEach { yield(it.content) }
            yield(info.hlsUrl)
            yield(info.dashMpdUrl)
        }
        return urls.mapNotNull { url -> url?.let(::expiryOf) }.minOrNull()
    }

    /** Epoch ms from one URL's `expire` (epoch seconds), or null. */
    fun expiryOf(url: String): Long? {
        val match = EXPIRE.find(url) ?: return null
        val seconds = match.groupValues.drop(1).firstOrNull { it.isNotEmpty() }?.toLongOrNull() ?: return null
        return seconds * 1000
    }
}
