package com.ahmadnayfeh.shasha.mobile.screenshots

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.ahmadnayfeh.shasha.mobile.AppContainer
import com.ahmadnayfeh.shasha.mobile.FakeVideoSource
import com.ahmadnayfeh.shasha.mobile.MobileApp
import com.ahmadnayfeh.shasha.mobile.common.Loadable
import com.ahmadnayfeh.shasha.mobile.common.PagedState
import com.ahmadnayfeh.shasha.mobile.data.db.DownloadEntity
import com.ahmadnayfeh.shasha.mobile.data.db.DownloadKind
import com.ahmadnayfeh.shasha.mobile.data.db.DownloadStatus
import com.ahmadnayfeh.shasha.mobile.download.DeleteOutcome
import com.ahmadnayfeh.shasha.mobile.download.DownloadManager
import com.ahmadnayfeh.shasha.mobile.download.DownloadNotifications
import com.ahmadnayfeh.shasha.mobile.download.MediaSaver
import com.ahmadnayfeh.shasha.mobile.download.OptionsSource
import com.ahmadnayfeh.shasha.mobile.download.PendingMedia
import com.ahmadnayfeh.shasha.mobile.download.Remuxer
import com.ahmadnayfeh.shasha.mobile.download.StreamFetcher
import com.ahmadnayfeh.shasha.mobile.player.PlayerPhase
import com.ahmadnayfeh.shasha.mobile.player.PlayerUiState
import com.ahmadnayfeh.shasha.mobile.player.QualityOption
import com.ahmadnayfeh.shasha.mobile.sponsorblock.SegmentAction
import com.ahmadnayfeh.shasha.mobile.sponsorblock.SponsorCategory
import com.ahmadnayfeh.shasha.mobile.sponsorblock.SponsorSegment
import com.ahmadnayfeh.shasha.mobile.ui.watch.WatchDetailsSource
import com.ahmadnayfeh.shasha.youtube.ChannelDetails
import com.ahmadnayfeh.shasha.youtube.ChannelItem
import com.ahmadnayfeh.shasha.youtube.ItemsPage
import com.ahmadnayfeh.shasha.youtube.PlaylistItem
import com.ahmadnayfeh.shasha.youtube.SearchFilter
import com.ahmadnayfeh.shasha.youtube.SourceItem
import com.ahmadnayfeh.shasha.youtube.VideoItem
import com.ahmadnayfeh.shasha.youtube.VideoSource
import com.ahmadnayfeh.shasha.youtube.download.DownloadOptions
import com.ahmadnayfeh.shasha.youtube.download.DownloadStream
import com.ahmadnayfeh.shasha.youtube.download.VideoDownloadOption
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.FileDescriptor
import kotlin.math.abs

/*
 * Everything the screenshot tests show is made up here: Arabic channels and videos, pictures drawn on the spot, a fake
 * source for the container and a stand-in for the player. Nothing asks the network.
 */

/** The app for the screenshot tests: the real container over fake sources. */
class ScreenshotApp : MobileApp() {
    override fun createContainer(): AppContainer = ScreenshotContainer(this)
}

class ScreenshotContainer(private val application: Application) : AppContainer(application) {
    override val videoSource: VideoSource = ScreenshotSource()

    /** A manager whose one download never gets past 36%: the Downloads screen shows a download in progress. */
    override val downloads: DownloadManager by lazy {
        DownloadManager(
            dao = database.downloads(),
            source = OptionsSource { Samples.downloadOptions },
            fetcher = StreamFetcher { _, _, onProgress ->
                onProgress(Samples.DOWNLOADED_BYTES)
                awaitCancellation()
            },
            remuxer = NoRemuxer,
            saver = EverythingIsThere,
            workDir = File(application.cacheDir, "screenshot-downloads"),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            notifications = Silent,
        )
    }
}

private object NoRemuxer : Remuxer {
    override suspend fun mux(video: File?, audio: File, output: FileDescriptor, onProgress: (Float) -> Unit) = error("not used")
}

private object EverythingIsThere : MediaSaver {
    override fun create(kind: DownloadKind, displayName: String): PendingMedia = error("not used")

    override fun delete(uri: String): DeleteOutcome = DeleteOutcome.Deleted

    override fun exists(uri: String): Boolean = true
}

private object Silent : DownloadNotifications {
    override fun completed(download: DownloadEntity) = Unit

    override fun failed(download: DownloadEntity) = Unit
}

class ScreenshotSource : FakeVideoSource() {
    init {
        onSearch = { query, filter, _ -> ItemsPage(Samples.searchResults(filter, withShorts = query == Samples.SHORTS_QUERY), null) }
        onSuggestions = { _, _ -> Samples.suggestions }
        // What YouTube suggests beside a watched video (the Home's «مقترحة لك»): videos, and Shorts for the Shorts row.
        onRelated = { Samples.related + Samples.shorts }
        onChannel = { id -> ChannelDetails(Samples.channels.first { it.channelId == id }, null, null) }
    }
}

object Samples {
    private val base = System.currentTimeMillis()
    private fun hoursAgo(hours: Double) = base - (hours * 3_600_000).toLong()

    // --- channels ----------------------------------------------------------------------------------------------------

    val academy = ChannelItem("UCacademy00000000000000A", "أكاديمية المعرفة", "https://yt3.ggpht.com/academy", 2_400_000)
    val chef = ChannelItem("UCchef0000000000000000B", "مطبخ سارة", "https://yt3.ggpht.com/chef", 1_300_000)
    val tech = ChannelItem("UCtech000000000000000000C", "عالم التقنية", "https://yt3.ggpht.com/tech", 3_100_000)
    val travel = ChannelItem("UCtravel0000000000000000D", "رحلات وأسفار", "https://yt3.ggpht.com/travel", 890_000)
    val kids = ChannelItem("UCkids00000000000000000E", "قناة الأطفال", "https://yt3.ggpht.com/kids", 5_600_000)
    val science = ChannelItem("UCscience0000000000000F", "ثقافة وعلوم", "https://yt3.ggpht.com/science", 410_000)
    val channels = listOf(academy, chef, tech, travel, kids, science)

    // --- videos ------------------------------------------------------------------------------------------------------

    private fun video(id: String, title: String, channel: ChannelItem, seconds: Long, views: Long, hoursAgo: Double, short: Boolean = false) = VideoItem(
        videoId = id,
        title = title,
        channelId = channel.channelId,
        channelName = channel.name,
        durationSeconds = seconds,
        publishedAtEpochMs = hoursAgo(hoursAgo),
        thumbnailUrl = "https://i.ytimg.com/vi/$id/hqdefault.jpg",
        viewCount = views,
        isLive = false,
        isShortForm = short,
        url = "https://www.youtube.com/watch?v=$id",
    )

    val blackHoles = video("bh000000001", "كيف تعمل الثقوب السوداء؟ رحلة إلى أعماق الكون", academy, 1122, 2_400_000, 3.5)
    val kabsa = video("kb000000002", "طريقة عمل الكبسة السعودية الأصلية بخطوات سهلة", chef, 727, 860_000, 27.0)
    val phones = video("ph000000003", "مراجعة أفضل الهواتف الذكية لهذا العام", tech, 1275, 540_000, 52.0)
    val morocco = video("mo000000004", "جولة في أجمل مدن المغرب: من الدار البيضاء إلى شفشاون", travel, 1533, 1_100_000, 100.0)
    val letters = video("lt000000005", "أنشودة الحروف العربية للأطفال", kids, 256, 12_000_000, 8 * 24.0)
    val dreams = video("dr000000006", "لماذا نحلم؟ أسرار النوم والأحلام", science, 890, 320_000, 15 * 24.0)
    val feed = listOf(blackHoles, kabsa, phones, morocco, letters, dreams)

    /** What the player's «related videos» list shows. */
    val related = listOf(
        video("rl000000011", "ما هو الزمن؟ وهل يمكن السفر عبره؟", academy, 1004, 1_900_000, 6 * 24.0),
        video("rl000000012", "أسرار المجرات: كم عددها وما أحجامها؟", science, 1311, 760_000, 9 * 24.0),
        video("rl000000013", "الكون في ساعة واحدة | وثائقي كامل", academy, 3540, 5_300_000, 30 * 24.0),
        video("rl000000014", "كيف تُصنع النجوم وتموت؟", science, 968, 430_000, 21 * 24.0),
    )

    // --- Shorts ------------------------------------------------------------------------------------------------------

    val shorts = listOf(
        video("sh000000001", "٣ حقائق مدهشة عن الفضاء", academy, 38, 1_800_000, 5.0, short = true),
        video("sh000000002", "أسرع طريقة لتحضير القهوة المختصة", chef, 27, 920_000, 9.0, short = true),
        video("sh000000003", "خدعة بسيطة توفّر بطارية هاتفك", tech, 44, 2_300_000, 14.0, short = true),
        video("sh000000004", "منظر خلاب في جبال عسير", travel, 31, 640_000, 30.0, short = true),
    )

    /** What the Shorts viewer shows while the Short at the top of [shorts] plays (the picture of the fake player stands for the video). */
    val shortState = PlayerUiState(phase = PlayerPhase.READY, video = shorts[1], isPlaying = true)

    /** What a search for this finds Shorts among (the general results have a row of them). */
    const val SHORTS_QUERY = "مقاطع قصيرة"

    // --- search ------------------------------------------------------------------------------------------------------

    val suggestions = listOf("الثقوب السوداء", "الثقوب السوداء وثائقي", "الثقوب السوداء للأطفال", "الثقوب الدودية", "الثقب الأسود في مركز المجرة")

    val documentary = video("sr000000021", "الثقوب السوداء بين العلم والخيال: فيلم وثائقي", science, 2715, 3_200_000, 40 * 24.0)
    val inside = video("sr000000022", "ماذا يوجد داخل الثقب الأسود؟", academy, 645, 4_100_000, 70 * 24.0)
    private val astronomy = PlaylistItem("PLastronomy00000000000000", "سلسلة الفيزياء الفلكية", academy.name, "https://i.ytimg.com/vi/pl1/hqdefault.jpg", 12)

    private val firstPhoto = video("sr000000023", "كيف التقطوا أول صورة لثقب أسود؟", academy, 812, 2_800_000, 100 * 24.0)
    private val strangest = video("sr000000024", "أغرب ما اكتشفه العلماء عن الثقوب السوداء", science, 1180, 640_000, 130 * 24.0)
    private val forKids = video("sr000000025", "الثقوب السوداء للأطفال بطريقة مبسّطة", kids, 421, 1_500_000, 200 * 24.0)

    // --- the viewer's own playlists (after the videos they hold) -------------------------------------------------------

    /** The lists of the viewer, with the videos in them in order. */
    val userPlaylists = listOf(
        "للمشاهدة مع العائلة" to listOf(blackHoles, kabsa),
        "علوم وفضاء" to listOf(documentary, inside, firstPhoto),
        "طبخ" to listOf(kabsa),
    )

    fun searchResults(filter: SearchFilter, withShorts: Boolean = false): List<SourceItem> = when (filter) {
        SearchFilter.CHANNELS -> listOf(academy, science)
        SearchFilter.PLAYLISTS -> listOf(astronomy)
        else -> listOf(blackHoles, academy, documentary, astronomy, inside, firstPhoto, strangest, forKids) + if (withShorts) shorts else emptyList()
    }

    // --- downloads ---------------------------------------------------------------------------------------------------

    private val videoStream = DownloadStream("https://download.invalid/v", "video/mp4", "avc1.64001f", 2_500_000, 45_000_000, 136)
    private val audioStream = DownloadStream("https://download.invalid/a", "audio/mp4", "mp4a.40.2", 128_000, 7_000_000, 140)
    val downloadOptions = DownloadOptions(listOf(VideoDownloadOption(720, 30, videoStream, audioStream)), audioStream, phones.durationSeconds!!)

    /** 19 of the 52 MB: 36% on the progress bar (fetching is 90% of a download). */
    const val DOWNLOADED_BYTES = 19_000_000L

    fun finishedDownload(file: File) = DownloadEntity(
        videoId = blackHoles.videoId,
        title = blackHoles.title,
        channelName = blackHoles.channelName,
        thumbnailUrl = blackHoles.thumbnailUrl,
        durationSeconds = blackHoles.durationSeconds,
        kind = DownloadKind.VIDEO,
        requestedHeight = 720,
        height = 720,
        status = DownloadStatus.DONE,
        fileBytes = 58_400_000,
        uri = file.toURI().toString(),
        displayName = "blackholes-720p.mp4",
        error = null,
        createdAt = base - 3_600_000,
        completedAt = base - 3_000_000,
    )

    // --- the player screen -------------------------------------------------------------------------------------------

    const val POSITION_MS = 435_000L
    const val BUFFERED_MS = 570_000L

    val playerState = PlayerUiState(
        phase = PlayerPhase.READY,
        video = blackHoles,
        description = "نأخذكم في رحلة إلى أعماق الكون لنفهم كيف تتكوّن الثقوب السوداء، وماذا يحدث عند أفق الحدث.",
        isPlaying = true,
        speed = 1.25f,
        qualities = listOf(
            QualityOption(1080, 30, 0, 0, 4_000_000, 0),
            QualityOption(720, 30, 0, 1, 2_500_000, 0),
            QualityOption(480, 30, 0, 2, 1_100_000, 0),
        ),
        playingHeight = 1080,
        segments = listOf(
            SponsorSegment("s1", SponsorCategory.SPONSOR, SegmentAction.SKIP, 130_000, 175_000),
            SponsorSegment("s2", SponsorCategory.SELF_PROMO, SegmentAction.SKIP, 570_000, 590_000),
            SponsorSegment("s3", SponsorCategory.INTERACTION, SegmentAction.SKIP, 1_050_000, 1_075_000),
        ),
    )

    // --- pictures ----------------------------------------------------------------------------------------------------

    /** Top colour, bottom colour and the colour of the soft light, for each look. */
    private val palettes = listOf(
        intArrayOf(0xFF0B3D5C.toInt(), 0xFF040A14.toInt(), 0xFF2EC4B6.toInt()),
        intArrayOf(0xFF8A3B12.toInt(), 0xFF1A0B08.toInt(), 0xFFFFB547.toInt()),
        intArrayOf(0xFF3B2A6B.toInt(), 0xFF0E0B16.toInt(), 0xFFFF7AA8.toInt()),
        intArrayOf(0xFF1E5B34.toInt(), 0xFF06100A.toInt(), 0xFFB5E86A.toInt()),
        intArrayOf(0xFF1B3F8B.toInt(), 0xFF0A1226.toInt(), 0xFFF4D03F.toInt()),
        intArrayOf(0xFF7B2339.toInt(), 0xFF160A0E.toInt(), 0xFF6CB8F0.toInt()),
        intArrayOf(0xFF0F5C5C.toInt(), 0xFF05100F.toInt(), 0xFFFF8A65.toInt()),
        intArrayOf(0xFF3A3F9E.toInt(), 0xFF0B0C22.toInt(), 0xFF5CE1E6.toInt()),
    )

    /** Which look each known picture has (by the id in its address), so the same video looks the same on every screen. */
    private val looks = listOf(
        "bh000000001", "kb000000002", "ph000000003", "mo000000004", "lt000000005", "dr000000006",
        "rl000000011", "rl000000012", "rl000000013", "rl000000014",
        "sr000000021", "sr000000022", "sr000000023", "sr000000024", "sr000000025", "pl1",
        "ggpht.com/academy", "ggpht.com/chef", "ggpht.com/tech", "ggpht.com/travel", "ggpht.com/kids", "ggpht.com/science",
        // Added last: a look is chosen by its place in this list, and the pictures of the older screens keep theirs.
        "sh000000001", "sh000000002", "sh000000003", "sh000000004",
    )

    private fun paletteFor(key: String): IntArray {
        val known = looks.indexOfFirst { it in key }
        return palettes[(if (known >= 0) known else abs(key.hashCode())) % palettes.size]
    }

    /**
     * A stand-in for a thumbnail or an avatar: a deep gradient with a soft light (and, for the black-hole film, a black
     * hole with its glowing ring), the same for the same [key].
     */
    fun picture(key: String, width: Int = 640, height: Int = 360): Bitmap {
        val (top, bottom, light) = paletteFor(key).let { Triple(it[0], it[1], it[2]) }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, 0f, height.toFloat(), top, bottom, Shader.TileMode.CLAMP) })
        canvas.drawPaint(
            Paint().apply {
                shader = RadialGradient(
                    width * 0.7f, height * 0.3f, width * 0.5f,
                    (light and 0x00FFFFFF) or 0x77000000, light and 0x00FFFFFF, Shader.TileMode.CLAMP,
                )
            },
        )
        if ("bh000000001" in key || "sr000000021" in key) drawBlackHole(canvas, width, height)
        return bitmap
    }

    private fun drawBlackHole(canvas: Canvas, width: Int, height: Int) {
        // Up and to the left: the player's buttons sit in the middle of the picture.
        val cx = width * 0.27f
        val cy = height * 0.33f
        val radius = height * 0.12f
        // The glow around it, then the tilted ring of hot gas, then the black disc over the middle of the ring.
        canvas.drawCircle(
            cx, cy, radius * 3f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(cx, cy, radius * 3f, intArrayOf(0xAAFFC46B.toInt(), 0x33FF8A3D, 0x00000000), floatArrayOf(0.25f, 0.55f, 1f), Shader.TileMode.CLAMP)
            },
        )
        canvas.save()
        canvas.rotate(-14f, cx, cy)
        canvas.drawOval(
            cx - radius * 2.3f, cy - radius * 0.42f, cx + radius * 2.3f, cy + radius * 0.42f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = radius * 0.22f
                shader = LinearGradient(cx - radius * 2.3f, 0f, cx + radius * 2.3f, 0f, 0xFFFFE9B0.toInt(), 0xFFFF7A1A.toInt(), Shader.TileMode.CLAMP)
            },
        )
        canvas.restore()
        canvas.drawCircle(cx, cy, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF000000.toInt() })
    }
}

/** What the player screen shows under the video, over the fake data: the related videos, or (with a [playlistId]) the playlist [queue]. */
class FakeDetails(
    override val playlistId: String? = null,
    queue: List<VideoItem> = emptyList(),
) : WatchDetailsSource {
    override val videoId: String = Samples.blackHoles.videoId
    override val related: StateFlow<Loadable<List<VideoItem>>> = MutableStateFlow(Loadable.Loaded(Samples.related))
    override val queueState: StateFlow<PagedState<VideoItem>> = MutableStateFlow(PagedState(items = queue, started = true, endReached = true))
    override val subscribed: StateFlow<Boolean> = MutableStateFlow(false)
    override val savedForLater: StateFlow<Boolean> = MutableStateFlow(false)
    override val channel: StateFlow<ChannelItem?> = MutableStateFlow(Samples.academy)

    override fun retryRelated() = Unit

    override fun loadMoreQueue() = Unit

    override fun toggleSubscribe() = Unit
}

/** A player that is "playing" 7:15 into an 18:42 video, for the timeline and the clock (or wherever it is told to be); it plays nothing. */
@androidx.annotation.OptIn(UnstableApi::class)
class FakePlayer(
    private val durationSeconds: Long = Samples.blackHoles.durationSeconds!!,
    private val positionMs: Long = Samples.POSITION_MS,
    private val bufferedMs: Long = Samples.BUFFERED_MS,
) : SimpleBasePlayer(Looper.getMainLooper()) {
    override fun getState(): SimpleBasePlayer.State = SimpleBasePlayer.State.Builder()
        .setAvailableCommands(Player.Commands.EMPTY)
        .setPlayWhenReady(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        .setPlaybackState(Player.STATE_READY)
        .setPlaylist(listOf(SimpleBasePlayer.MediaItemData.Builder("video").setDurationUs(durationSeconds * 1_000_000).build()))
        .setCurrentMediaItemIndex(0)
        .setContentPositionMs(positionMs)
        .setContentBufferedPositionMs { bufferedMs }
        .build()
}
