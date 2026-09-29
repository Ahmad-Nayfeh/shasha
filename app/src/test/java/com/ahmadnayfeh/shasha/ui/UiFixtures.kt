package com.ahmadnayfeh.shasha.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import com.ahmadnayfeh.shasha.ui.model.ChannelUi
import com.ahmadnayfeh.shasha.ui.model.ItemUi
import com.ahmadnayfeh.shasha.ui.model.PlaylistUi
import com.ahmadnayfeh.shasha.ui.model.TimeKind
import com.ahmadnayfeh.shasha.ui.model.VideoUi
import com.ahmadnayfeh.shasha.youtube.ChannelDetails
import com.ahmadnayfeh.shasha.youtube.ChannelItem
import com.ahmadnayfeh.shasha.youtube.PlaylistItem
import com.ahmadnayfeh.shasha.youtube.VideoItem

/** Fake but realistic data for the browsing screens' screenshots and tests. */
object UiFixtures {
    /** 2026-09-28 12:00 UTC. */
    const val NOW = 1_790_596_800_000L
    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE
    private const val DAY = 24 * HOUR

    fun video(
        n: Int,
        title: String,
        channel: String,
        durationSeconds: Long?,
        agoMs: Long,
        live: Boolean = false,
        channelId: String = "UC" + "fixture".padEnd(20, 'x') + n.toString().padStart(2, '0'),
    ) = VideoItem(
        videoId = "vid" + n.toString().padStart(8, '0'),
        title = title,
        channelId = channelId.take(24),
        channelName = channel,
        durationSeconds = durationSeconds,
        publishedAtEpochMs = NOW - agoMs,
        thumbnailUrl = null,
        viewCount = null,
        isLive = live,
        isShortForm = false,
        url = "https://www.youtube.com/watch?v=vid$n",
    )

    val videos: List<VideoItem> = listOf(
        video(1, "رحلة إلى أعماق المحيط: كائنات لم ترها من قبل", "عالم الطبيعة", 754, 2 * HOUR),
        video(2, "How Rockets Actually Work — A Visual Guide", "Space Lab", 1082, 5 * HOUR),
        video(3, "بث مباشر: تلاوة خاشعة من الحرم المكي", "قناة القرآن الكريم", null, 20 * MINUTE, live = true),
        video(4, "وصفة الكنافة النابلسية في البيت خطوة بخطوة", "مطبخ سلمى", 587, DAY + 3 * HOUR),
        video(5, "The Most Beautiful Equation in Mathematics", "Math Corner", 1275, 2 * DAY),
        video(6, "أسرار الخط العربي: الثلث والديواني وكيف تبدأ", "فنون الخط", 860, 3 * DAY),
        video(7, "Building a Tiny Cabin in the Woods — Full Timeline", "Woodland Craft", 2590, 4 * DAY),
        video(8, "ملخص مباراة الأمس: الأهداف والتحليل الكامل", "رياضة اليوم", 485, 5 * DAY),
        video(9, "Why Do Cats Purr? The Science Explained", "Curious Minds", 391, 8 * DAY),
        video(10, "جولة في أسواق دمشق القديمة", "رحّالة", 1548, 15 * DAY),
        video(11, "تجربة سيارة كهربائية لمدة شهر كامل: هل تستحق؟", "تقنية ببساطة", 982, 34 * DAY),
        video(12, "Lo-fi Beats to Study and Relax — 3 Hours", "Calm Studio", 10_800, 70 * DAY),
    )

    val videoUis: List<VideoUi> = videos.map { VideoUi(it, it.publishedAtEpochMs, TimeKind.PUBLISHED) }

    /** Watched fractions: one in progress, one finished. */
    val watched: Map<String, Float> = mapOf(videos[0].videoId to 0.35f, videos[4].videoId to 1f, videos[6].videoId to 0.72f)

    val watchLater: List<VideoUi> = listOf(videos[6], videos[1], videos[9], videos[4], videos[11], videos[3])
        .mapIndexed { i, v -> VideoUi(v.copy(publishedAtEpochMs = null), NOW - (i + 1) * 7 * HOUR, TimeKind.ADDED) }

    val history: List<VideoUi> = listOf(videos[0], videos[4], videos[5], videos[7], videos[2], videos[10])
        .mapIndexed { i, v -> VideoUi(v.copy(publishedAtEpochMs = null), NOW - (i * i + 1) * 3 * HOUR, TimeKind.WATCHED) }

    val calligraphyChannel = ChannelItem("UCfixturecalligraphy0001", "فنون الخط", avatarUrl = "fake://avatar/khat", subscriberCount = 1_250_000)
    private val lessonsChannel = ChannelItem("UCfixturelessons00000002", "Arabic Calligraphy Lessons", avatarUrl = null, subscriberCount = 38_400)

    val searchResults: List<ItemUi> = listOf(
        ChannelUi(calligraphyChannel),
        VideoUi(videos[5], videos[5].publishedAtEpochMs),
        PlaylistUi(PlaylistItem("PLfixture0001", "دروس الخط العربي للمبتدئين", "فنون الخط", "fake://playlist/khat", 24)),
        VideoUi(video(20, "Arabic Calligraphy for Beginners: Your First Letters", "Arabic Calligraphy Lessons", 1320, 9 * DAY), NOW - 9 * DAY),
        ChannelUi(lessonsChannel),
        VideoUi(video(21, "كيف تختار القلم والحبر المناسبين للخط", "فنون الخط", 544, 40 * DAY), NOW - 40 * DAY),
        VideoUi(video(22, "خط الرقعة في عشر دقائق", "مدرسة الخط", 610, 120 * DAY), NOW - 120 * DAY),
        VideoUi(video(23, "Thuluth Script: Master Class, Part 2", "Ink & Reed", 3620, 400 * DAY), NOW - 400 * DAY),
    )

    val subscribedIds: Set<String> = setOf(calligraphyChannel.channelId)

    val channelDetails = ChannelDetails(calligraphyChannel, bannerUrl = "fake://banner/khat", description = null)

    val channelVideos: List<VideoUi> = listOf(
        video(30, "أسرار الخط العربي: الثلث والديواني وكيف تبدأ", "فنون الخط", 860, 3 * DAY),
        video(31, "كيف تختار القلم والحبر المناسبين للخط", "فنون الخط", 544, 11 * DAY),
        video(32, "تمرين يومي لعشر دقائق يحسّن خطك", "فنون الخط", 612, 18 * DAY),
        video(33, "لوحة «بسم الله» بخط الثلث من البداية إلى النهاية", "فنون الخط", 2710, 25 * DAY),
        video(34, "الفرق بين النسخ والرقعة", "فنون الخط", 433, 40 * DAY),
        video(35, "Q&A: Answering Your Calligraphy Questions", "فنون الخط", 1802, 52 * DAY),
        video(36, "أدوات الخطاط: جولة في مرسمي", "فنون الخط", 965, 80 * DAY),
        video(37, "تذهيب اللوحات: التقنية القديمة", "فنون الخط", 1400, 130 * DAY),
    ).map { VideoUi(it, it.publishedAtEpochMs) }

    val playlist = PlaylistItem("PLfixture0001", "دروس الخط العربي للمبتدئين: من الحرف الأول إلى اللوحة", "فنون الخط", "fake://playlist/khat", 24)

    val playlistVideos: List<VideoUi> = listOf(
        "الدرس 1: الأدوات وطريقة مسك القلم" to 612L,
        "الدرس 2: النقطة ميزان الحروف" to 540L,
        "الدرس 3: الألف والباء" to 733L,
        "الدرس 4: الجيم والحاء والخاء" to 801L,
        "Lesson 5: Connecting Letters (with English subtitles)" to 925L,
        "الدرس 6: السين والشين" to 690L,
        "الدرس 7: الصاد والضاد" to 704L,
        "الدرس 8: مراجعة وتمرين" to 1210L,
    ).mapIndexed { i, (title, seconds) -> VideoUi(video(40 + i, title, "فنون الخط", seconds, (60 - i) * DAY), NOW - (60 - i) * DAY) }

    val playlistWatched: Map<String, Float> = mapOf(
        playlistVideos[0].video.videoId to 1f,
        playlistVideos[1].video.videoId to 1f,
        playlistVideos[2].video.videoId to 0.45f,
    )

    val subscriptions: List<ChannelUi> = listOf(
        ChannelItem("UCfixturesub0000000000a1", "عالم الطبيعة", "fake://avatar/1", null),
        ChannelItem("UCfixturesub0000000000a2", "Space Lab", "fake://avatar/22", null),
        ChannelItem("UCfixturesub0000000000a3", "مطبخ سلمى", null, null),
        ChannelItem("UCfixturesub0000000000a4", "Math Corner", "fake://avatar/4444", null),
        ChannelItem("UCfixturesub0000000000a5", "فنون الخط", "fake://avatar/khat", null),
        ChannelItem("UCfixturesub0000000000a6", "Woodland Craft", null, null),
        ChannelItem("UCfixturesub0000000000a7", "رياضة اليوم", "fake://avatar/777", null),
        ChannelItem("UCfixturesub0000000000a8", "رحّالة", null, null),
    ).map { ChannelUi(it) }

    /**
     * A stand-in thumbnail: a two-colour gradient with a soft light, the colours picked from [key] so
     * neighbouring cards differ like real ones do.
     */
    fun fakeThumbnail(key: String, width: Int = 480, height: Int = 270): Bitmap {
        val palettes = listOf(
            intArrayOf(0xFF0B3D5C.toInt(), 0xFF06121F.toInt(), 0xFF2EC4B6.toInt()),
            intArrayOf(0xFF4A1942.toInt(), 0xFF0E0B16.toInt(), 0xFFFFB547.toInt()),
            intArrayOf(0xFF123524.toInt(), 0xFF050B08.toInt(), 0xFF9BE15D.toInt()),
            intArrayOf(0xFF5C2A0B.toInt(), 0xFF140904.toInt(), 0xFFFF8A5B.toInt()),
            intArrayOf(0xFF1D2B64.toInt(), 0xFF080B1C.toInt(), 0xFF8EA7FF.toInt()),
            intArrayOf(0xFF3B3B3B.toInt(), 0xFF101010.toInt(), 0xFFEAF0F6.toInt()),
            intArrayOf(0xFF5A0F2E.toInt(), 0xFF12040A.toInt(), 0xFFFF6B9A.toInt()),
        )
        val hash = key.hashCode()
        val (top, bottom, light) = palettes[Math.floorMod(hash, palettes.size)].let { Triple(it[0], it[1], it[2]) }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, width * 0.4f, height.toFloat(), top, bottom, Shader.TileMode.CLAMP) })
        val cx = width * (0.2f + 0.6f * (Math.floorMod(hash / 7, 100) / 100f))
        val cy = height * (0.25f + 0.4f * (Math.floorMod(hash / 13, 100) / 100f))
        canvas.drawPaint(
            Paint().apply {
                shader = RadialGradient(cx, cy, width * 0.5f, (light and 0x00FFFFFF) or 0x77000000, light and 0x00FFFFFF, Shader.TileMode.CLAMP)
            },
        )
        return bitmap
    }
}
