package com.ahmadnayfeh.shasha.mobile

import com.ahmadnayfeh.shasha.youtube.PlaybackHandle
import com.ahmadnayfeh.shasha.youtube.StreamDetails
import com.ahmadnayfeh.shasha.youtube.VideoItem

/**
 * [StreamDetails] for the phone's JVM tests. The phone cannot build one: its [PlaybackHandle] holds a NewPipe StreamInfo, and
 * only :youtube may see NewPipe (the handle's constructor is internal to it). So this builds an empty StreamInfo and the
 * handle by reflection, as the TV app's tests do. Nothing in it can be played (the factory refuses it): it is for the view
 * models and repositories that only read what the extraction found about a video.
 */
object TestStreams {
    /** [uploaderAvatarUrl]: the picture of the channel that uploaded [video], as the video's own page gives it. */
    fun details(video: VideoItem, uploaderAvatarUrl: String? = null): StreamDetails = StreamDetails(
        video = video,
        description = null,
        videoTracks = emptyList(),
        subtitles = emptyList(),
        playback = handle(video),
        uploaderAvatarUrl = uploaderAvatarUrl,
    )

    private fun handle(video: VideoItem): PlaybackHandle {
        val streamType = Class.forName("org.schabi.newpipe.extractor.stream.StreamType")
        val vod = streamType.enumConstants!!.first { (it as Enum<*>).name == "VIDEO_STREAM" }
        val infoClass = Class.forName("org.schabi.newpipe.extractor.stream.StreamInfo")
        val info = infoClass
            .getConstructor(
                Int::class.javaPrimitiveType, String::class.java, String::class.java, streamType,
                String::class.java, String::class.java, Int::class.javaPrimitiveType,
            )
            .newInstance(0, video.url, video.url, vod, video.videoId, video.title, 0)
        return PlaybackHandle::class.java
            .getConstructor(infoClass, Any::class.java, Boolean::class.javaPrimitiveType)
            .newInstance(info, null, false)
    }
}
