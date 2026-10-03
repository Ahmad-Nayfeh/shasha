package com.ahmadnayfeh.shasha.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil3.annotation.ExperimentalCoilApi
import coil3.asImage
import coil3.compose.AsyncImagePreviewHandler
import coil3.compose.LocalAsyncImagePreviewHandler
import com.ahmadnayfeh.shasha.Fixtures
import com.ahmadnayfeh.shasha.TV_1080P
import com.ahmadnayfeh.shasha.ui.UiFixtures.NOW
import com.ahmadnayfeh.shasha.ui.components.ContentFocus
import com.ahmadnayfeh.shasha.ui.model.ItemUi
import com.ahmadnayfeh.shasha.ui.paging.PagedState
import com.ahmadnayfeh.shasha.ui.screens.channel.ChannelHeader
import com.ahmadnayfeh.shasha.ui.screens.channel.ChannelScreen
import com.ahmadnayfeh.shasha.ui.screens.channel.ChannelSections
import com.ahmadnayfeh.shasha.ui.theme.ShashaTheme
import com.ahmadnayfeh.shasha.youtube.ChannelSort
import com.ahmadnayfeh.shasha.youtube.ChannelTab
import com.ahmadnayfeh.shasha.youtube.SourceErrorKind
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A channel's sections (الفيديوهات · القصيرة · البث المباشر · القوائم) and orders (الأحدث · الأكثر مشاهدة · الأقدم) as the
 * page shows them, and where the D-pad's focus is: on the first card as the page opens, on the section chip after UP, on the
 * order chip after UP from the last card of the line. JVM screenshots at 1920x1080, like the others.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalCoilApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = TV_1080P)
class ChannelSectionsScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun snap(name: String, keys: List<Key> = emptyList(), content: @Composable () -> Unit) {
        val pictures = AsyncImagePreviewHandler { request -> UiFixtures.fakeThumbnail(request.data.toString()).asImage() }
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true, LocalAsyncImagePreviewHandler provides pictures) {
                ShashaTheme { content() }
            }
        }
        compose.waitForIdle()
        keys.forEach { key ->
            compose.onRoot().performKeyInput { pressKey(key) }
            compose.waitForIdle()
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    @Composable
    private fun <T> stateOf(value: T) = remember { mutableStateOf(value) }

    private val allTabs = ChannelTab.entries.toList()

    private fun sections(selected: ChannelTab, sort: ChannelSort = ChannelSort.LATEST, tabs: List<ChannelTab> = allTabs) = ChannelSections(
        tabs = tabs,
        selected = selected,
        sorts = if (selected.listsVideos) ChannelSort.entries else emptyList(),
        sort = sort,
    )

    private fun loaded(items: List<ItemUi>) = PagedState(items = items, loadingFirst = false)

    @Composable
    private fun Channel(
        sections: ChannelSections,
        items: PagedState<ItemUi>,
        watched: Map<String, Float> = emptyMap(),
        adult: Boolean = true,
    ) = ChannelScreen(
        channelId = UiFixtures.calligraphyChannel.channelId,
        nameHint = null,
        header = ChannelHeader.Ready(UiFixtures.channelDetails),
        sections = sections,
        items = items,
        subscribed = true,
        watched = stateOf(watched),
        nowMs = NOW,
        focus = remember { ContentFocus() },
        extractorVersion = "v0.26.5",
        onToggleSubscription = {},
        onUnsubscribe = {},
        onPlay = {},
        onOpenPlaylist = {},
        onSelectTab = {},
        onSelectSort = {},
        onLoadMore = {},
        onRetryMore = {},
        onBack = {},
        onCheckUpdates = {},
        adult = adult,
    )

    private val videos: List<ItemUi> = UiFixtures.channelVideos

    @Test
    fun orderChipHasFocus() = snap(
        "channel_sort_focus",
        keys = listOf(Key.DirectionLeft, Key.DirectionLeft, Key.DirectionLeft, Key.DirectionUp),
    ) {
        // Along the first line to its last card, then UP: the order chips, on the order the list is in («الأكثر مشاهدة»).
        Channel(sections(ChannelTab.VIDEOS, ChannelSort.POPULAR), loaded(videos.reversed()), UiFixtures.watched)
    }

    @Test
    fun sectionChipHasFocus() = snap("channel_tab_focus", keys = listOf(Key.DirectionUp)) {
        // UP from the newest video: the sections, on the one that is open.
        Channel(sections(ChannelTab.VIDEOS), loaded(videos))
    }

    @Test
    fun shorts() = snap("channel_shorts") {
        // The Shorts section: portrait tiles, as many across as a Shorts row has; its orders too.
        Channel(sections(ChannelTab.SHORTS), loaded(UiFixtures.channelShorts), mapOf(UiFixtures.channelShorts[1].video.videoId to 0.5f))
    }

    @Test
    fun live() = snap("channel_live") {
        // The live section: «مباشر» on the stream that is on now, the length of the past ones.
        Channel(sections(ChannelTab.LIVE, ChannelSort.OLDEST), loaded(UiFixtures.channelLive))
    }

    @Test
    fun playlists() = snap("channel_playlists") {
        // The playlists: no orders to choose from.
        Channel(sections(ChannelTab.PLAYLISTS), loaded(UiFixtures.channelPlaylists))
    }

    @Test
    fun aChildsPage() = snap("channel_kids") {
        // A child's page (no subscribe, no parental menu): Shorts are not in its sections (its parent hides them), and the
        // channel has no live streams.
        Channel(
            sections(ChannelTab.VIDEOS, tabs = listOf(ChannelTab.VIDEOS, ChannelTab.PLAYLISTS)),
            loaded(videos),
            adult = false,
        )
    }

    @Test
    fun anEmptySection() = snap("channel_section_empty") {
        // A section with nothing in it: said so, and the focus waits on its chip.
        Channel(sections(ChannelTab.LIVE).copy(sorts = emptyList()), loaded(emptyList()))
    }

    @Test
    fun aSectionBeingRead() = snap("channel_section_loading") {
        Channel(sections(ChannelTab.SHORTS), PagedState(loadingFirst = true))
    }

    @Test
    fun anOrderThatFailed() = snap("channel_section_error") {
        // One order failed: said under the chips, with «إعادة المحاولة»; the other orders and sections stay one press away.
        Channel(
            sections(ChannelTab.VIDEOS, ChannelSort.POPULAR),
            PagedState(loadingFirst = false, error = Fixtures.error(SourceErrorKind.NETWORK, "SocketTimeoutException")),
        )
    }
}
