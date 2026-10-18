package com.ahmadnayfeh.shasha.ui.screens.playlist

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.TV_1080P
import com.ahmadnayfeh.shasha.data.MutableProfile
import com.ahmadnayfeh.shasha.data.WatchRepository
import com.ahmadnayfeh.shasha.data.db.ShashaDatabase
import com.ahmadnayfeh.shasha.data.inMemoryDatabase
import com.ahmadnayfeh.shasha.player.PlayQueues
import com.ahmadnayfeh.shasha.policy.Blocklist
import com.ahmadnayfeh.shasha.policy.ContentFilter
import com.ahmadnayfeh.shasha.policy.PolicyEngine
import com.ahmadnayfeh.shasha.policy.Profile
import com.ahmadnayfeh.shasha.ui.UiFixtures
import com.ahmadnayfeh.shasha.ui.components.ContentFocus
import com.ahmadnayfeh.shasha.ui.format.Thumbnails
import com.ahmadnayfeh.shasha.ui.model.PlaylistUi
import com.ahmadnayfeh.shasha.ui.paging.PagedState
import com.ahmadnayfeh.shasha.ui.paging.ScriptedSource
import com.ahmadnayfeh.shasha.ui.screens.library.WatchLaterScreen
import com.ahmadnayfeh.shasha.ui.screens.library.WatchLaterViewModel
import com.ahmadnayfeh.shasha.ui.theme.ShashaTheme
import com.ahmadnayfeh.shasha.youtube.PlaylistItem
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Clock

/**
 * The owner's request: the phone saves YouTube playlists, the TV could not. The adult's playlist page has «حفظ القائمة» /
 * «محفوظة» (a playlist card's menu too, see LongPressMenuTest), kept per profile like watch later, and «شاهد لاحقاً» lists
 * the saved playlists above its videos; OK on one opens its page. A child's page has no such button (no library there).
 */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = TV_1080P)
class SavedPlaylistsTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var db: ShashaDatabase
    private lateinit var watch: WatchRepository
    private val engine = PolicyEngine(Clock.systemUTC())
    private val adult = Profile.newAdult("الكبار", id = "adult")
    private val rules = MutableStateFlow(ContentFilter.of(engine, adult))
    private val playlistId = "PLscripted0000000000000000000000001"
    private var hosting by mutableStateOf(true)

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        db = inMemoryDatabase()
        watch = WatchRepository(db, MutableProfile("adult"))
    }

    @After
    fun tearDown() {
        hosting = false
        compose.waitForIdle()
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun thePage_savesThePlaylist_withItsFirstVideosPicture_andTakesItOutAgain() = runBlocking<Unit> {
        val source = ScriptedSource().apply { playlistPages = ScriptedSource.pagesOf(5) }
        val vm = PlaylistViewModel(playlistId, source, watch, PlayQueues(), rules)
        withTimeout(5_000) { vm.videos.first { it.items.isNotEmpty() } }
        val firstVideo = vm.videos.value.items.first().video.videoId

        val answers = mutableListOf<Boolean>()
        vm.toggleSaved { saved, _ -> answers += saved }
        withTimeout(5_000) { watch.observeSavedPlaylistIds().first { playlistId in it } }
        val saved = watch.observeSavedPlaylists().first().single()
        assertThat(saved.title).isEqualTo("Playlist")
        // YouTube gave no picture: the first video's stands in, so the library's card is not blank.
        assertThat(saved.thumbnailUrl).isEqualTo(Thumbnails.video(firstVideo))

        vm.toggleSaved { saved, _ -> answers += saved }
        withTimeout(5_000) { watch.observeSavedPlaylistIds().first { playlistId !in it } }
        assertThat(answers).containsExactly(true, false).inOrder()
    }

    @Test
    fun theLibrary_hidesASavedPlaylistTheRulesRefuseNow() = runBlocking<Unit> {
        watch.savePlaylist(PlaylistItem("PLfine", "رحلات بحرية", null, null, 4))
        watch.savePlaylist(PlaylistItem("PLscary", "قصص رعب", null, null, 4))
        rules.value = ContentFilter.of(engine, adult.copy(blocklist = Blocklist(titleKeywords = listOf("رعب"))))
        val vm = WatchLaterViewModel(watch, rules)
        val shown = withTimeout(5_000) { vm.playlists.filterNotNull().first() }
        assertThat(shown.map { it.playlist.playlistId }).containsExactly("PLfine")
    }

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    @Test
    fun theAdultsPage_hasTheToggleUnderPlayAll_reachedWithTheDpad() {
        var saved by mutableStateOf(false)
        compose.setContent {
            ShashaTheme {
                PlaylistScreen(
                    details = UiFixtures.playlist,
                    videos = PagedState(items = UiFixtures.playlistVideos, loadingFirst = false),
                    watched = remember { mutableStateOf(emptyMap()) },
                    focus = remember { ContentFocus() },
                    extractorVersion = "test",
                    onPlayFrom = {},
                    onLoadMore = {},
                    onRetryMore = {},
                    onBack = {},
                    onCheckUpdates = {},
                    save = SaveToggle(saved) { saved = !saved },
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("playlist_play_all").assertIsFocused()
        press(Key.DirectionDown)
        compose.onNodeWithTag("playlist_save").assertIsFocused().assertTextContains("حفظ القائمة")
        press(Key.DirectionCenter)
        assertThat(saved).isTrue()
        compose.onNodeWithTag("playlist_save").assertIsFocused().assertTextContains("محفوظة")
    }

    @Test
    fun aChildsPage_hasNoToggle() {
        compose.setContent {
            ShashaTheme {
                PlaylistScreen(
                    details = UiFixtures.playlist,
                    videos = PagedState(items = UiFixtures.playlistVideos, loadingFirst = false),
                    watched = remember { mutableStateOf(emptyMap()) },
                    focus = remember { ContentFocus() },
                    extractorVersion = "test",
                    onPlayFrom = {},
                    onLoadMore = {},
                    onRetryMore = {},
                    onBack = {},
                    onCheckUpdates = {},
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("playlist_save").assertDoesNotExist()
    }

    @Test
    fun watchLater_withOnlySavedPlaylists_listsThem_andOkOpensOne() {
        val opened = mutableListOf<String>()
        val playlists = listOf(
            PlaylistUi(PlaylistItem("PLone", "رحلات بحرية", "قناة", null, 4)),
            PlaylistUi(PlaylistItem("PLtwo", "طبخ", null, null, 9)),
        )
        compose.setContent {
            ShashaTheme {
                WatchLaterScreen(
                    items = emptyList(),
                    watched = remember { mutableStateOf(emptyMap()) },
                    nowMs = 0,
                    focus = remember { ContentFocus() },
                    onPlay = {},
                    playlists = playlists,
                    onOpenPlaylist = { opened += it },
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("saved_playlists").assertExists()
        press(Key.DirectionCenter)
        assertThat(opened).containsExactly("PLone")
    }
}
