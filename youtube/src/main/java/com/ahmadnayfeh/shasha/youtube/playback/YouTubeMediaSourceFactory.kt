package com.ahmadnayfeh.shasha.youtube.playback

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.dash.DefaultDashChunkSource
import androidx.media3.exoplayer.dash.manifest.DashManifest
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleExtractor
import com.ahmadnayfeh.shasha.youtube.SourceException
import com.ahmadnayfeh.shasha.youtube.StreamDetails
import com.ahmadnayfeh.shasha.youtube.newpipe.HttpStatusException
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.CreationException
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubeOtfDashManifestCreator
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubePostLiveStreamDvrDashManifestCreator
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubeProgressiveDashManifestCreator
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Builds Media3 sources for YouTube streams returned by [com.ahmadnayfeh.shasha.youtube.VideoSource.stream].
 *
 * Create one per process (it keeps an HTTP client and caches decoder capabilities).
 */
@OptIn(UnstableApi::class)
class YouTubeMediaSourceFactory internal constructor(
    okHttpClient: OkHttpClient,
    private val decoders: VideoDecoderCheck,
) {
    constructor(context: Context, okHttpClient: OkHttpClient) :
        this(okHttpClient, MediaCodecVideoDecoderCheck(context.applicationContext))

    // App interceptors (e.g. a global User-Agent) would override the per-client headers googlevideo
    // checks, so media gets a copy of the shared client (same pool) without them.
    private val client: OkHttpClient = okHttpClient.newBuilder()
        .apply {
            interceptors().clear()
            networkInterceptors().clear()
        }
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val dashData = YouTubeHttpDataSource.Factory(client, GoogleVideoQuirks.DASH)
    private val progressiveData = YouTubeHttpDataSource.Factory(client, GoogleVideoQuirks.PROGRESSIVE)
    private val liveData = YouTubeHttpDataSource.Factory(client, GoogleVideoQuirks.LIVE)
    private val planner = PlaybackPlanner(decoders)

    init {
        // NPE caches every manifest it creates for the whole process; a TV session can run for hours.
        YoutubeProgressiveDashManifestCreator.getCache().setMaximumSize(MANIFEST_CACHE_SIZE)
        YoutubeOtfDashManifestCreator.getCache().setMaximumSize(MANIFEST_CACHE_SIZE)
        YoutubePostLiveStreamDvrDashManifestCreator.getCache().setMaximumSize(MANIFEST_CACHE_SIZE)
    }

    /**
     * @param maxHeight quality cap (the short side, i.e. YouTube's "1080p"); ExoPlayer adapts between
     * all decodable qualities up to it. ≤ 0 means no cap. If nothing fits, the lowest quality is used.
     * @param subtitleLanguage a [com.ahmadnayfeh.shasha.youtube.SubtitleTrack.languageCode], or null
     * for none. The track is selected by default (human-made captions preferred over auto-generated).
     *
     * No network I/O happens here: manifests that need it (OTF, ended live streams) are built by
     * Media3's loader threads, and their failures surface as player errors ([classifyPlaybackError]).
     * Safe to call from any thread.
     *
     * A stream extracted with `allowLowQuality` that is low quality ([StreamDetails.lowQualityHeight]) plays its
     * muxed file; any other muxed-only ≤360p stream is LOW_QUALITY_ONLY.
     *
     * @throws SourceException (EXTRACTION_BROKEN, LOW_QUALITY_ONLY) when the stream has nothing playable.
     */
    fun create(stream: StreamDetails, maxHeight: Int, subtitleLanguage: String?): MediaSource {
        val plan = planner.plan(stream.playback.info, maxHeight, subtitleLanguage, stream.playback.allowLowQuality)
        val metadata = MediaMetadata.Builder().setTitle(stream.video.title).setArtist(stream.video.channelName).build()
        // The first source's MediaItem becomes the player's current item: mediaId = the video id.
        val newItem = { MediaItem.Builder().setMediaId(stream.video.videoId).setMediaMetadata(metadata) }
        val sources = plan.parts.mapIndexed { index, part -> build(part, newItem(), "${stream.video.videoId}/$index") }
        val subtitle = plan.subtitle?.let { subtitleSource(it) }
        val all = sources + listOfNotNull(subtitle)
        return if (all.size == 1) all.single() else MergingMediaSource(/* adjustPeriodTimeOffsets= */ true, *all.toTypedArray())
    }

    /**
     * Maps a playback failure (a Media3 PlaybackException, e.g. HTTP 403 from googlevideo) to the
     * [SourceException] shown on the unified error screen.
     *
     * Handle PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW before calling this: it is not an error
     * (seek to the live edge and prepare again).
     */
    fun classifyPlaybackError(error: Throwable): SourceException = PlaybackErrorClassifier.classify(error)

    private fun build(part: MediaPart, item: MediaItem.Builder, key: String): MediaSource = when (part) {
        is MediaPart.Dash -> dashSource(item, key) { part.manifest }
        is MediaPart.LazyDash -> dashSource(item, key, part.create)
        is MediaPart.Progressive -> ProgressiveMediaSource.Factory(progressiveData)
            .createMediaSource(item.setUri(part.url).build())
        is MediaPart.Hls -> HlsMediaSource.Factory(liveData)
            .createMediaSource(
                item.setUri(part.url).setMimeType(MimeTypes.APPLICATION_M3U8)
                    .apply { if (part.live) setLiveConfiguration(liveConfiguration()) }
                    .build(),
            )
        is MediaPart.LiveDash -> DashMediaSource.Factory(DefaultDashChunkSource.Factory(liveData), liveData)
            .setManifestParser(LiveDashManifestParser())
            .createMediaSource(
                item.setUri(part.url).setMimeType(MimeTypes.APPLICATION_MPD).setLiveConfiguration(liveConfiguration()).build(),
            )
    }

    /**
     * The manifest is served from memory through a data source, so Media3 parses it (and, for lazy
     * ones, NPE builds it) on its loader thread and reports failures like any load error.
     */
    private fun dashSource(item: MediaItem.Builder, key: String, manifest: () -> String): MediaSource {
        val bytes = lazy(LazyThreadSafetyMode.SYNCHRONIZED) { manifestBytes(manifest) }
        val manifestData = DataSource.Factory { ByteArrayDataSource { bytes.value } }
        return DashMediaSource.Factory(DefaultDashChunkSource.Factory(dashData), manifestData)
            .createMediaSource(item.setUri(Uri.parse("$MANIFEST_SCHEME://$key")).setMimeType(MimeTypes.APPLICATION_MPD).build())
    }

    private fun manifestBytes(manifest: () -> String): ByteArray = try {
        manifest().toByteArray(Charsets.UTF_8)
    } catch (e: CreationException) {
        // NPE reports googlevideo's answer only in the message ("... response code 403").
        val status = Regex("response code (\\d{3})").find(e.message.orEmpty())?.groupValues?.get(1)?.toInt()
        throw if (status != null) HttpStatusException(status, null).apply { initCause(e) } else ManifestCreationException(e)
    } catch (e: IOException) {
        throw e
    } catch (e: RuntimeException) {
        throw ManifestCreationException(e)
    }

    private fun subtitleSource(subtitle: SubtitlePart): MediaSource {
        val format = Format.Builder()
            .setSampleMimeType(subtitle.mimeType)
            .setLanguage(subtitle.language)
            .setLabel(subtitle.label)
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .setId("subtitle:${subtitle.language}")
            .build()
        val parsers = DefaultSubtitleParserFactory()
        // Parse while loading (Media3's default): the text renderer then gets ready-made cues.
        val extractors = ExtractorsFactory { arrayOf<Extractor>(SubtitleExtractor(parsers.create(format), format)) }
        return ProgressiveMediaSource.Factory(liveData, extractors)
            .createMediaSource(MediaItem.fromUri(subtitle.url))
    }

    private fun liveConfiguration() = MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(LIVE_TARGET_OFFSET_MS).build()

    /** A manifest creator failed for a reason other than an HTTP status. */
    internal class ManifestCreationException(cause: Exception) : IOException(cause.message, cause)

    /**
     * YouTube's live DASH manifests make ExoPlayer start at the oldest period in some cases; pinning
     * availabilityStartTime to the epoch makes it start at the live edge (same workaround as NewPipe).
     */
    private class LiveDashManifestParser : DashManifestParser() {
        override fun buildMediaPresentationDescription(
            availabilityStartTime: Long,
            durationMs: Long,
            minBufferTimeMs: Long,
            dynamic: Boolean,
            minUpdateTimeMs: Long,
            timeShiftBufferDepthMs: Long,
            suggestedPresentationDelayMs: Long,
            publishTimeMs: Long,
            programInformation: androidx.media3.exoplayer.dash.manifest.ProgramInformation?,
            utcTiming: androidx.media3.exoplayer.dash.manifest.UtcTimingElement?,
            serviceDescription: androidx.media3.exoplayer.dash.manifest.ServiceDescriptionElement?,
            periods: List<androidx.media3.exoplayer.dash.manifest.Period>,
            locations: List<androidx.media3.exoplayer.dash.manifest.Location>,
        ): DashManifest = super.buildMediaPresentationDescription(
            0L, durationMs, minBufferTimeMs, dynamic, minUpdateTimeMs, timeShiftBufferDepthMs,
            suggestedPresentationDelayMs, publishTimeMs, programInformation, utcTiming, serviceDescription, periods, locations,
        )
    }

    private companion object {
        const val MANIFEST_SCHEME = "shasha-manifest"
        const val MANIFEST_CACHE_SIZE = 30
        const val LIVE_TARGET_OFFSET_MS = 10_000L
    }
}
