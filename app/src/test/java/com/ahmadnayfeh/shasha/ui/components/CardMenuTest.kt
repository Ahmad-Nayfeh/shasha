package com.ahmadnayfeh.shasha.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
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
import com.ahmadnayfeh.shasha.data.video
import com.ahmadnayfeh.shasha.policy.MutableClock
import com.ahmadnayfeh.shasha.policy.PinHasher
import com.ahmadnayfeh.shasha.policy.Profile
import com.ahmadnayfeh.shasha.policy.instantAt
import com.ahmadnayfeh.shasha.ui.parental.ParentUnlock
import com.ahmadnayfeh.shasha.ui.parental.ParentalLists
import com.ahmadnayfeh.shasha.ui.theme.ShashaTheme
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The cards' context menus: they stay inside the screen and scroll when a household has many children, and
 * their parental rows (a child's allowlist, the block list) ask for the parent PIN first, as the settings
 * do, then trust it for a few minutes.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = TV_1080P)
class CardMenuTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var db: ShashaDatabase
    private lateinit var stores: TestDataStores
    private lateinit var profiles: ProfileRepository
    private lateinit var pin: ParentPinRepository
    private var uptime = 5_000_000L

    private val channel = "UCmenu00000000000000000a1"
    private val sara = Profile.newChild("سارة", id = "sara")

    @Before
    fun setUp() = runBlocking {
        db = inMemoryDatabase()
        stores = TestDataStores(tmp.root)
        profiles = ProfileRepository(db)
        profiles.ensureOwner("الكبار")
        profiles.upsert(sara)
        pin = ParentPinRepository(stores.open(), MutableClock(instantAt("2026-09-28T12:00")), PinHasher(iterations = 1_000))
        pin.setup(PIN)
        Unit
    }

    /** The host is on screen; set to false before the database closes, so no flow of it queries a closed one. */
    private var hosting by mutableStateOf(true)

    @After
    fun tearDown() {
        hosting = false
        compose.waitForIdle()
        stores.closeAll()
        db.close()
    }

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    @Test
    fun manyChildren_theMenuStaysOnScreen_andScrollsToEveryRow() {
        var blocked = 0
        val rows = (1..6).map { MenuItem(ShashaIcons.Shield, "أضف إلى قائمة سماح طفل $it", "menu_allow_$it", locked = true) {} } +
            MenuItem(ShashaIcons.Block, "حظر القناة", "menu_block", locked = true) { blocked++ }
        compose.setContent {
            ShashaTheme {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CardMenuPanel("رحلة إلى أعماق المحيط: كائنات لم ترها من قبل", "عالم الطبيعة", rows, Modifier.testTag("menu_panel"))
                }
            }
        }
        compose.waitForIdle()
        val panel = compose.onNodeWithTag("menu_panel").getUnclippedBoundsInRoot()
        assertThat(panel.top.value).isAtLeast(27f) // inside the TV's overscan margins
        assertThat(panel.bottom.value).isAtMost(540f - 27f)

        compose.onNodeWithTag("menu_allow_1").assertIsFocused()
        repeat(6) { press(Key.DirectionDown) }
        compose.onNodeWithTag("menu_block").assertIsFocused().assertIsDisplayed().assertHeightIsEqualTo(56.dp)
        press(Key.DirectionCenter)
        assertThat(blocked).isEqualTo(1)
    }

    @Test
    fun parentalRows_askForThePinFirst_thenTrustItForAFewMinutes() {
        val unlock = ParentUnlock(pin, elapsed = { uptime })
        val parental = ParentalActions(profiles.observeAll(), ParentalLists(profiles), unlock)
        val watch = WatchRepository(db, MutableProfile())
        var opens by mutableIntStateOf(1)
        compose.setContent {
            ShashaTheme {
                if (hosting) {
                    CardMenuHost(watch, onOpenChannel = { _, _ -> }, canOpenChannel = { true }, parental = parental) {
                        val open = LocalVideoMenu.current
                        LaunchedEffect(opens) { open(video("menuvideo01", channel, null, channelName = "عالم الطبيعة")) }
                    }
                }
            }
        }
        compose.waitForIdle()

        // «أضف إلى قائمة سماح سارة», with the adult profile left on: the PIN comes first, nothing changes yet.
        compose.onAllNodesWithTag("menu_row_locked", useUnmergedTree = true).assertCountEquals(2) // her row and «حظر القناة»
        compose.onNodeWithTag("menu_allow_sara").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNodeWithTag("pin_pad").assertExists()
        assertThat(allowed()).isFalse()

        compose.onNodeWithTag("pin_pad").performKeyInput {
            listOf(Key.Two, Key.Four, Key.Six, Key.Eight, Key.Enter).forEach { pressKey(it) }
        }
        compose.waitUntil(5_000) { allowed() }
        compose.onNodeWithTag("pin_pad").assertDoesNotExist()

        // Within the next minutes the rows act at once (the same unlock as the settings).
        uptime += 60_000L
        opens++
        compose.waitForIdle()
        compose.onAllNodesWithTag("menu_row_locked", useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithTag("menu_allow_sara").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(5_000) { !allowed() }
        compose.onNodeWithTag("pin_pad").assertDoesNotExist()

        // Five minutes later it asks again.
        uptime += ParentUnlock.UNLOCK_MS
        opens++
        compose.waitForIdle()
        compose.onNodeWithTag("menu_allow_sara").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNodeWithTag("pin_pad").assertExists()
        assertThat(allowed()).isFalse()
    }

    private fun allowed(): Boolean = runBlocking { profiles.get(sara.id)!!.allowlist.hasChannel(channel) }

    private companion object {
        const val PIN = "2468"
    }
}
