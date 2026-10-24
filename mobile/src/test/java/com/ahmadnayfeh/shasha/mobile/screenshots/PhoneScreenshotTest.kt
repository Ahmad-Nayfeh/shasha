package com.ahmadnayfeh.shasha.mobile.screenshots

import android.content.Context
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil3.annotation.ExperimentalCoilApi
import coil3.asImage
import coil3.compose.AsyncImagePreviewHandler
import coil3.compose.LocalAsyncImagePreviewHandler
import com.ahmadnayfeh.shasha.mobile.LaunchRequest
import com.ahmadnayfeh.shasha.mobile.container
import com.ahmadnayfeh.shasha.mobile.data.db.DownloadKind
import com.ahmadnayfeh.shasha.mobile.data.db.WatchProgressEntity
import com.ahmadnayfeh.shasha.mobile.data.toFeedEntity
import com.ahmadnayfeh.shasha.mobile.data.toHistoryEntity
import com.ahmadnayfeh.shasha.mobile.data.toSubscription
import com.ahmadnayfeh.shasha.mobile.ui.PHONE
import com.ahmadnayfeh.shasha.mobile.ui.PHONE_NIGHT
import com.ahmadnayfeh.shasha.mobile.ui.ShashaRoot
import com.ahmadnayfeh.shasha.mobile.ui.awaitText
import com.ahmadnayfeh.shasha.mobile.ui.shorts.ShortsContent
import com.ahmadnayfeh.shasha.mobile.ui.shorts.ShortsQueue
import com.ahmadnayfeh.shasha.mobile.ui.tab
import com.ahmadnayfeh.shasha.mobile.ui.theme.ShashaTheme
import com.ahmadnayfeh.shasha.mobile.ui.watch.WatchContent
import com.ahmadnayfeh.shasha.mobile.data.AppTheme
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Screenshots of the phone app's main screens (Roborazzi on Robolectric's native graphics) with fake Arabic data and no
 * network: the real screens, the real database and settings, over a fake source. They are the pictures of the guide and the
 * video. Record: `./gradlew :mobile:recordRoborazziDebug`; check: `:mobile:verifyRoborazziDebug`. A plain
 * `testDebugUnitTest` only renders them. The PNGs live in mobile/src/test/screenshots/.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ScreenshotApp::class, qualifiers = "ar-$PHONE")
class PhoneScreenshotTest : ScreenshotCases(suffix = "")

/** The same screens in the dark theme. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ScreenshotApp::class, qualifiers = "ar-$PHONE_NIGHT")
class PhoneDarkScreenshotTest : ScreenshotCases(suffix = "_dark")

abstract class ScreenshotCases(private val suffix: String) {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val container get() = context.container

    @Before
    fun seed() {
        val now = System.currentTimeMillis()
        runBlocking {
            // The feed was refreshed a minute ago: nothing is asked of the network.
            container.settings.setFeedRefreshedAt(now - 60_000)
            Samples.channels.forEach { container.database.subscriptions().upsert(it.toSubscription(now)) }
            container.database.feed().upsertAll(Samples.feed.map { it.toFeedEntity(it.channelId!!) })
            // A little of two videos was watched: the bar under their pictures.
            container.database.progress().upsert(WatchProgressEntity(Samples.kabsa.videoId, 330_000, 727_000, now))
            container.database.progress().upsert(WatchProgressEntity(Samples.morocco.videoId, 1_200_000, 1_533_000, now))
        }
    }

    /** [content] over the pictures drawn by [Samples.picture] instead of the network's. */
    @OptIn(ExperimentalCoilApi::class)
    private fun show(content: @Composable () -> Unit) {
        val pictures = AsyncImagePreviewHandler { request ->
            val key = request.data.toString()
            // Avatars are square, thumbnails are 16:9, and a Short's picture is tall.
            when {
                "ggpht" in key -> Samples.picture(key, 240, 240)
                "/vi/sh" in key -> Samples.picture(key, 360, 640)
                else -> Samples.picture(key)
            }.asImage()
        }
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true, LocalAsyncImagePreviewHandler provides pictures) { content() }
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("src/test/screenshots/phone_$name$suffix.png")
    }

    @Test
    fun home() {
        show { ShashaRoot(container) }
        compose.awaitText(Samples.blackHoles.title)
        capture("home")
    }

    @Test
    fun search() {
        container.launch.value = LaunchRequest.Search("الثقوب السوداء")
        show { ShashaRoot(container) }
        // The home screen (the start) shows the first result too: wait for one only the results have.
        compose.awaitText(Samples.documentary.title)
        capture("search")
    }

    @Test
    fun player() = showPlayer(FakeDetails(), "player")

    /** The watch page of a video played from a playlist: the playlist under it, in place of the related videos. */
    @Test
    fun playerPlaylist() = showPlayer(FakeDetails(playlistId = "PLscience", queue = listOf(Samples.blackHoles) + Samples.related), "player_playlist")

    private fun showPlayer(details: FakeDetails, name: String) {
        // The controls stay up: the clock of the test does not run, so nothing fades them out.
        compose.mainClock.autoAdvance = false
        val player = FakePlayer()
        show {
            // The theme setting is left as it is ("as the phone"): the qualifiers of the class say light or dark. As in the app, the page
            // is not inside any Surface (it is drawn over the root Scaffold): its text must take the theme's colour by itself.
            ShashaTheme(AppTheme.SYSTEM) {
                WatchContent(
                    state = Samples.playerState,
                    player = player,
                    controller = container.player,
                    details = details,
                    isFullscreen = false,
                    inPip = false,
                    onRetry = {},
                    onBack = {},
                    onToggleFullscreen = {},
                    onEnterPictureInPicture = {},
                    onVideoBounds = {},
                    onOpenVideo = {},
                    onOpenChannel = {},
                )
            }
        }
        repeat(5) { compose.mainClock.advanceTimeByFrame() }
        capture(name)
    }

    @Test
    fun homeSuggested() {
        val now = System.currentTimeMillis()
        runBlocking {
            // Two videos were watched lately: what YouTube suggests beside them is «مقترحة لك», and its Shorts are the Shorts row.
            container.database.history().upsert(Samples.blackHoles.toHistoryEntity(now - 2 * 24 * 3_600_000L))
            container.database.history().upsert(Samples.kabsa.toHistoryEntity(now - 3_600_000L))
        }
        show { ShashaRoot(container) }
        compose.awaitText("مقترحة لك")
        compose.awaitText(Samples.shorts.first().title)
        compose.awaitText(Samples.related.first().title)
        capture("home_suggested")
    }

    @Test
    fun searchShorts() {
        container.launch.value = LaunchRequest.Search(Samples.SHORTS_QUERY)
        show { ShashaRoot(container) }
        compose.awaitText(Samples.shorts.first().title)
        capture("search_shorts")
    }

    @Test
    fun shorts() {
        // The pager and the hairline draw once, and nothing moves.
        compose.mainClock.autoAdvance = false
        val queue = ShortsQueue.of(Samples.shorts, Samples.shorts[1].videoId)
        show {
            ShashaTheme(AppTheme.SYSTEM) {
                ShortsContent(
                    state = Samples.shortState,
                    player = FakePlayer(durationSeconds = Samples.shorts[1].durationSeconds!!, positionMs = 11_000, bufferedMs = 24_000),
                    queue = queue,
                    pagerState = rememberPagerState(initialPage = queue.startIndex) { queue.size },
                    onTogglePlay = {},
                    onRetry = {},
                    onBack = {},
                    onOpenChannel = {},
                )
            }
        }
        repeat(5) { compose.mainClock.advanceTimeByFrame() }
        capture("shorts")
    }

    @Test
    fun library() {
        seedPlaylists()
        show { ShashaRoot(container) }
        compose.awaitText(Samples.blackHoles.title)
        compose.tab("مكتبتي").performClick()
        compose.awaitText(Samples.userPlaylists.first().first)
        capture("library")
    }

    @Test
    fun playlist() {
        seedPlaylists()
        show { ShashaRoot(container) }
        compose.awaitText(Samples.blackHoles.title)
        compose.tab("مكتبتي").performClick()
        compose.awaitText(Samples.userPlaylists[1].first)
        compose.onNodeWithText(Samples.userPlaylists[1].first).performClick()
        compose.awaitText(Samples.userPlaylists[1].second.last().title)
        capture("playlist")
    }

    /** The viewer's three lists, each with its videos in order. */
    private fun seedPlaylists() = runBlocking {
        for ((name, videos) in Samples.userPlaylists) {
            val id = checkNotNull(container.userPlaylists.create(name))
            for (video in videos) container.userPlaylists.add(id, video)
        }
    }

    @Test
    fun downloads() {
        val file = File(context.cacheDir, "blackholes-720p.mp4").apply { writeBytes(ByteArray(16)) }
        runBlocking {
            container.database.downloads().insert(Samples.finishedDownload(file))
            container.downloads.enqueue(Samples.phones, DownloadKind.VIDEO, 720)
        }
        container.launch.value = LaunchRequest.Downloads
        show { ShashaRoot(container) }
        compose.awaitText("التنزيلات")
        // The download in progress has reported its share (the manager works on another thread).
        compose.awaitText("جارٍ التنزيل 32%", substring = true)
        capture("downloads")
    }

    @Test
    fun settings() {
        show { ShashaRoot(container) }
        compose.awaitText(Samples.blackHoles.title)
        compose.tab("الإعدادات").performClick()
        compose.awaitText("اللغة")
        capture("settings")
    }

    @Test
    fun about() {
        show { ShashaRoot(container) }
        compose.awaitText(Samples.blackHoles.title)
        compose.tab("الإعدادات").performClick()
        compose.awaitText("اللغة")
        compose.onNodeWithText("حول").performScrollTo().performClick()
        compose.awaitText("صُنع بحب لعائلتي")
        capture("about")
    }
}
