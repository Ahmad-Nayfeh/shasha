package com.ahmadnayfeh.shasha.ui.components

import android.view.KeyEvent.ACTION_DOWN
import android.view.KeyEvent.ACTION_UP
import android.view.KeyEvent.KEYCODE_DPAD_CENTER
import android.view.KeyEvent.KEYCODE_DPAD_DOWN
import android.view.KeyEvent.KEYCODE_ENTER
import android.view.KeyEvent.KEYCODE_MENU
import android.view.KeyEvent.KEYCODE_NUMPAD_ENTER
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyPress
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.TV_1080P
import com.ahmadnayfeh.shasha.data.MutableProfile
import com.ahmadnayfeh.shasha.data.ParentPinRepository
import com.ahmadnayfeh.shasha.data.ProfileRepository
import com.ahmadnayfeh.shasha.data.TestDataStores
import com.ahmadnayfeh.shasha.data.WatchRepository
import com.ahmadnayfeh.shasha.data.db.ShashaDatabase
import com.ahmadnayfeh.shasha.data.inMemoryDatabase
import com.ahmadnayfeh.shasha.policy.MutableClock
import com.ahmadnayfeh.shasha.policy.PinHasher
import com.ahmadnayfeh.shasha.policy.Profile
import com.ahmadnayfeh.shasha.policy.instantAt
import com.ahmadnayfeh.shasha.ui.UiFixtures
import com.ahmadnayfeh.shasha.ui.format.rememberFormats
import com.ahmadnayfeh.shasha.ui.model.PlaylistUi
import com.ahmadnayfeh.shasha.ui.model.VideoUi
import com.ahmadnayfeh.shasha.ui.parental.ParentUnlock
import com.ahmadnayfeh.shasha.ui.parental.ParentalLists
import com.ahmadnayfeh.shasha.ui.theme.ShashaTheme
import com.ahmadnayfeh.shasha.youtube.ChannelItem
import com.ahmadnayfeh.shasha.youtube.PlaylistItem
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * v1.1.1 bug: a card's menu opened by holding OK closed as soon as OK was let go. The rest of that press (its repeats
 * and its release) reaches the menu's window, and a release on its focused first row is a click (tv-material clicks on
 * the key's release): the row acted and the menu closed. Now the menu swallows that press's leftovers
 * ([HeldSelectGuard]) and stays until a row is chosen with a fresh press or BACK closes it. For every long-press menu:
 * a video's, a channel's and a playlist's.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = TV_1080P)
class LongPressMenuTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val tmp = TemporaryFolder()

    // ---- The guard itself ----

    @Test
    fun theOpeningPress_itsRepeatsAndItsRelease_areSwallowed_thenAFreshPressPasses() {
        val guard = HeldSelectGuard()
        assertThat(guard.swallow(KEYCODE_DPAD_CENTER, ACTION_DOWN, repeatCount = 2)).isTrue()
        assertThat(guard.swallow(KEYCODE_DPAD_CENTER, ACTION_DOWN, repeatCount = 3)).isTrue()
        // Moving while still holding OK: other keys always pass.
        assertThat(guard.swallow(KEYCODE_DPAD_DOWN, ACTION_DOWN, repeatCount = 0)).isFalse()
        assertThat(guard.swallow(KEYCODE_DPAD_CENTER, ACTION_UP, repeatCount = 0)).isTrue()
        // The menu's own press: down and up both pass (a click).
        assertThat(guard.swallow(KEYCODE_DPAD_CENTER, ACTION_DOWN, repeatCount = 0)).isFalse()
        assertThat(guard.swallow(KEYCODE_DPAD_CENTER, ACTION_UP, repeatCount = 0)).isFalse()
        // And every press after it.
        assertThat(guard.swallow(KEYCODE_DPAD_CENTER, ACTION_DOWN, repeatCount = 0)).isFalse()
        assertThat(guard.swallow(KEYCODE_DPAD_CENTER, ACTION_DOWN, repeatCount = 1)).isFalse()
        assertThat(guard.swallow(KEYCODE_DPAD_CENTER, ACTION_UP, repeatCount = 0)).isFalse()
    }

    @Test
    fun aMenuOpenedWithoutHoldingOk_takesItsFirstPressAsAClick() {
        val guard = HeldSelectGuard()
        assertThat(guard.swallow(KEYCODE_DPAD_CENTER, ACTION_DOWN, repeatCount = 0)).isFalse()
        assertThat(guard.swallow(KEYCODE_DPAD_CENTER, ACTION_UP, repeatCount = 0)).isFalse()
    }

    @Test
    fun everyKeyTheSurfacesClickWith_isGuarded() {
        for (code in listOf(KEYCODE_DPAD_CENTER, KEYCODE_ENTER, KEYCODE_NUMPAD_ENTER)) {
            val guard = HeldSelectGuard()
            assertThat(guard.swallow(code, ACTION_DOWN, repeatCount = 1)).isTrue()
            assertThat(guard.swallow(code, ACTION_UP, repeatCount = 0)).isTrue()
            assertThat(guard.swallow(code, ACTION_UP, repeatCount = 0)).isFalse()
        }
        assertThat(HeldSelectGuard().swallow(KEYCODE_MENU, ACTION_UP, repeatCount = 0)).isFalse()
    }

    // ---- On screen: down, repeats, up ----

    private lateinit var db: ShashaDatabase
    private lateinit var stores: TestDataStores
    private lateinit var profiles: ProfileRepository
    private lateinit var watch: WatchRepository
    private lateinit var parental: ParentalActions
    private val video = UiFixtures.videos[0]

    /** The host is on screen; false before the database closes, so no flow of it queries a closed one. */
    private var hosting by mutableStateOf(true)

    @Before
    fun setUp() = runBlocking {
        db = inMemoryDatabase()
        stores = TestDataStores(tmp.root)
        profiles = ProfileRepository(db)
        profiles.ensureOwner("الكبار")
        profiles.upsert(Profile.newChild("سارة", id = "sara"))
        val pin = ParentPinRepository(stores.open(), MutableClock(instantAt("2026-09-28T12:00")), PinHasher(iterations = 1_000))
        pin.setup("2468")
        watch = WatchRepository(db, MutableProfile())
        parental = ParentalActions(profiles.observeAll(), ParentalLists(profiles), ParentUnlock(pin, elapsed = { 5_000_000L }))
        Unit
    }

    @After
    fun tearDown() {
        hosting = false
        compose.waitForIdle()
        stores.closeAll()
        db.close()
    }

    private fun show(card: @Composable () -> Unit) {
        compose.setContent {
            ShashaTheme {
                if (hosting) {
                    CardMenuHost(watch, onOpenChannel = { _, _ -> }, canOpenChannel = { true }, parental = parental) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Box(Modifier.width(320.dp)) { card() }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("card").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
    }

    private fun key(code: Int, action: Int, repeat: Int = 0) = KeyEvent(android.view.KeyEvent(0L, 0L, action, code, repeat))

    /**
     * OK held on the card: its first two downs reach the card (the second, a repeat, opens the menu); the rest of the
     * press (more repeats, then the release) reaches the menu's window, as Android sends it once the menu has the focus.
     */
    private fun holdOkOnTheCard(menuTag: String) {
        compose.onNodeWithTag("card").performKeyPress(key(KEYCODE_DPAD_CENTER, ACTION_DOWN, repeat = 0))
        compose.onNodeWithTag("card").performKeyPress(key(KEYCODE_DPAD_CENTER, ACTION_DOWN, repeat = 1))
        compose.waitForIdle()
        compose.onNodeWithTag(menuTag).assertExists()
        for (repeat in 2..6) compose.onNodeWithTag(menuTag).performKeyPress(key(KEYCODE_DPAD_CENTER, ACTION_DOWN, repeat))
        compose.onNodeWithTag(menuTag).performKeyPress(key(KEYCODE_DPAD_CENTER, ACTION_UP))
        compose.waitForIdle()
    }

    /** A fresh press of OK in the menu: its focused first row acts. */
    private fun pressOkIn(menuTag: String) {
        compose.onNodeWithTag(menuTag).performKeyPress(key(KEYCODE_DPAD_CENTER, ACTION_DOWN))
        compose.onNodeWithTag(menuTag).performKeyPress(key(KEYCODE_DPAD_CENTER, ACTION_UP))
        compose.waitForIdle()
    }

    private fun savedForLater(): Boolean = runBlocking { watch.isSaved(video.videoId).first() }

    @Test
    fun aVideoCard_theMenuOpenedByHoldingOk_staysOpenWhenOkIsLetGo() {
        show {
            VideoCard(VideoUi(video, null), watched = null, formats = rememberFormats(), nowMs = UiFixtures.NOW, onClick = {}, thumbnailModifier = Modifier.testTag("card"))
        }
        holdOkOnTheCard("video_menu")
        compose.onNodeWithTag("video_menu").assertExists()
        assertThat(savedForLater()).isFalse() // «شاهد لاحقاً», its first row, did not act

        // A fresh press chooses the first row: it acts, the menu closes.
        pressOkIn("video_menu")
        compose.waitUntil(5_000) { savedForLater() }
        compose.onNodeWithTag("video_menu").assertDoesNotExist()
    }

    @Test
    fun aChannelCard_theMenuOpenedByHoldingOk_staysOpenWhenOkIsLetGo() {
        show {
            ChannelCard(ChannelItem("UClongpress0000000000001", "قناة", null, 1_000), subtitle = null, onClick = {}, tileModifier = Modifier.testTag("card"))
        }
        holdOkOnTheCard("card_menu")
        compose.onNodeWithTag("card_menu").assertExists()
        compose.onNodeWithTag("pin_pad").assertDoesNotExist() // its first row (sara's allowlist, behind the PIN) did not act

        pressOkIn("card_menu")
        compose.onNodeWithTag("card_menu").assertDoesNotExist()
        compose.onNodeWithTag("pin_pad").assertExists()
    }

    @Test
    fun aPlaylistCard_theMenuOpenedByHoldingOk_staysOpenWhenOkIsLetGo() {
        show {
            PlaylistCard(
                PlaylistUi(PlaylistItem("PLlongpress0000000000001", "قائمة", "قناة", null, 3)),
                formats = rememberFormats(),
                onClick = {},
                tileModifier = Modifier.testTag("card"),
            )
        }
        holdOkOnTheCard("card_menu")
        compose.onNodeWithTag("card_menu").assertExists()
        assertThat(playlistSaved()).isFalse() // «حفظ القائمة», its first row, did not act

        // A fresh press chooses the first row: the playlist is saved (the profile's own library: no PIN), the menu closes.
        pressOkIn("card_menu")
        compose.waitUntil(5_000) { playlistSaved() }
        compose.onNodeWithTag("card_menu").assertDoesNotExist()
        compose.onNodeWithTag("pin_pad").assertDoesNotExist()
    }

    private fun playlistSaved(): Boolean = runBlocking { "PLlongpress0000000000001" in watch.observeSavedPlaylistIds().first() }

    @Test
    fun aMenuOpenedWithMenu_actsOnTheFirstPressOfOk() {
        show {
            VideoCard(VideoUi(video, null), watched = null, formats = rememberFormats(), nowMs = UiFixtures.NOW, onClick = {}, thumbnailModifier = Modifier.testTag("card"))
        }
        compose.onNodeWithTag("card").performKeyPress(key(KEYCODE_MENU, ACTION_DOWN))
        compose.onNodeWithTag("card").performKeyPress(key(KEYCODE_MENU, ACTION_UP))
        compose.waitForIdle()
        compose.onNodeWithTag("video_menu").assertExists()
        pressOkIn("video_menu")
        compose.waitUntil(5_000) { savedForLater() }
        compose.onNodeWithTag("video_menu").assertDoesNotExist()
    }
}
