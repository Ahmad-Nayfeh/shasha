package com.ahmadnayfeh.shasha

import com.ahmadnayfeh.shasha.youtube.PlaybackHandle
import com.ahmadnayfeh.shasha.youtube.StreamDetails
import com.ahmadnayfeh.shasha.youtube.VideoItem

/**
 * [StreamDetails] for the app's JVM tests. The app cannot build one: its [PlaybackHandle] holds a NewPipe
 * StreamInfo, and only :youtube may see NewPipe (the handle's constructor is internal to it). So this builds an
 * empty StreamInfo and the handle by reflection. Nothing in it can be played (the factory refuses it): it is for
 * the view models and repositories that only pass streams along.
 */
object TestStreams {
    /** [lowQualityHeight]: as :youtube marks a muxed-only result extracted with allowLowQuality. */
    fun details(video: VideoItem, lowQualityHeight: Int? = null): StreamDetails = StreamDetails(
        video = video,
        description = null,
        videoTracks = emptyList(),
        subtitles = emptyList(),
        playback = handle(video, allowLowQuality = lowQualityHeight != null),
        lowQualityHeight = lowQualityHeight,
    )

    private fun handle(video: VideoItem, allowLowQuality: Boolean): PlaybackHandle {
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
            .newInstance(info, null, allowLowQuality)
    }
}
