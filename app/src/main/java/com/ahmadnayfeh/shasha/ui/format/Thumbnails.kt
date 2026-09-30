package com.ahmadnayfeh.shasha.ui.format

/**
 * Video thumbnails by video id, at the size a card needs. Every list (home, search, history, …) asks
 * for the same URL for the same video, so a thumbnail downloaded once is a cache hit everywhere,
 * whatever URL the source item carried (RSS, search results and history all differ).
 */
object Thumbnails {
    /**
     * [Size.LARGE] is hqdefault (480x360: a 16:9 picture with bars that ContentScale.Crop removes), for
     * grid cards (~364 px wide on a 1080p TV). [Size.SMALL] is mqdefault (320x180), for list rows.
     */
    enum class Size(val file: String) { SMALL("mqdefault"), LARGE("hqdefault") }

    fun video(videoId: String, size: Size = Size.LARGE): String = "https://i.ytimg.com/vi/$videoId/${size.file}.jpg"
}
