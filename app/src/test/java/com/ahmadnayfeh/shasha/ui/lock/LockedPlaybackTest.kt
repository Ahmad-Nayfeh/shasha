package com.ahmadnayfeh.shasha.ui.lock

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.Fixtures
import com.ahmadnayfeh.shasha.TV_1080P
import com.ahmadnayfeh.shasha.data.ParentPinRepository
import com.ahmadnayfeh.shasha.data.PlaybackLockStore
import com.ahmadnayfeh.shasha.data.StreamRepository
import com.ahmadnayfeh.shasha.data.TestDataStores
import com.ahmadnayfeh.shasha.data.UsageRepository
import com.ahmadnayfeh.shasha.data.cancelAndWait
import com.ahmadnayfeh.shasha.data.db.ShashaDatabase
import com.ahmadnayfeh.shasha.data.inMemoryDatabase
import com.ahmadnayfeh.shasha.data.testScope
import com.ahmadnayfeh.shasha.lock.LockController
import com.ahmadnayfeh.shasha.player.PlaybackProgress
import com.ahmadnayfeh.shasha.player.PlayerAction
import com.ahmadnayfeh.shasha.policy.MutableClock
import com.ahmadnayfeh.shasha.policy.PinHasher
import com.ahmadnayfeh.shasha.policy.PolicyEngine
import com.ahmadnayfeh.shasha.policy.Profile
import com.ahmadnayfeh.shasha.policy.UnlockMethod
import com.ahmadnayfeh.shasha.policy.clip
import com.ahmadnayfeh.shasha.policy.instantAt
import com.ahmadnayfeh.shasha.policy.kid
import com.ahmadnayfeh.shasha.policy.owner
import com.ahmadnayfeh.shasha.ui.FakeUiSource
import com.ahmadnayfeh.shasha.ui.screens.player.EndCard
import com.ahmadnayfeh.shasha.ui.screens.player.PlayGate
import com.ahmadnayfeh.shasha.ui.screens.player.PlayerOsd
import com.ahmadnayfeh.shasha.ui.screens.player.PlayerViewModel
import com.ahmadnayfeh.shasha.ui.theme.KidsAccent
import com.ahmadnayfeh.shasha.ui.theme.ShashaTheme
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * SPEC §4 on screen, with the app's real lock (DataStore, the parent PIN's limiter, the surrenders table):
 * a child's BACK only opens the PIN pad, a wrong PIN stays, the right one leaves; the natural end frees
 * the child; the adult leaves only after a 10-second hold and the phrase, which is recorded.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = TV_1080P)
class LockedPlaybackTest {
    @get:Rule(order = 0)
    val tmp = TemporaryFolder()

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var db: ShashaDatabase
    private lateinit var stores: TestDataStores
    private lateinit var usage: UsageRepository
    private lateinit var lock: LockController
    private val scope = testScope()
    private val clock = MutableClock(instantAt("2026-09-28T12:00"))
    private val engine = PolicyEngine(clock)
    private val child = kid()
    private val adult = owner()
    private val current = MutableStateFlow<Profile?>(child)

    /** What the screen did: left the player, went to the child home, recorded a surrender, the player's actions. */
    private var left = 0
    private var home = 0
    private var surrendered = 0
    private val actions = mutableListOf<PlayerAction>()

    @Before
    fun setUp() = runBlocking {
        db = inMemoryDatabase()
        stores = TestDataStores(tmp.root)
        val store = stores.open()
        val pin = ParentPinRepository(store, clock, PinHasher(iterations = 1_000))
        pin.setup(PIN)
        usage = UsageRepository(db)
        lock = LockController(engine, PlaybackLockStore(store, scope), current, pin::verify, pin::remainingLockMillis, { usage.recordSurrender(it) }, scope)
    }

    @After
    fun tearDown() {
        scope.cancelAndWait()
        stores.closeAll()
        db.close()
    }

    // ---- Harness ------------------------------------------------------------------------------------

    /** The player of [VIDEO] for [profile], inside the lock; [content] is the video's screen (the OSD by default). */
    private fun show(profile: Profile, content: @Composable (LockedPlaybackState) -> Unit = { Osd(it) }) {
        current.value = profile
        compose.setContent {
            ShashaTheme {
                val methods = remember { lock.methodFor(profile.id, VIDEO, null) }
                val method by methods.collectAsState(null)
                val screen: @Composable () -> Unit = {
                    LockedPlayback(
                        method = method,
                        actions = lock.playerActions(),
                        onLeave = { left++ },
                        onSurrendered = { surrendered++ },
                        newPhrase = { PHRASE },
                    ) { state -> content(state) }
                }
                if (profile.isChild) KidsAccent(screen) else screen()
            }
        }
        compose.waitForIdle()
    }

    @Composable
    private fun Osd(state: LockedPlaybackState) {
        PlayerOsd(
            state = Fixtures.osd,
            progress = { Fixtures.progress },
            onAction = { actions += it },
            onExit = state::requestExit,
            autoHideMs = NEVER,
            suspended = state.suspended,
        )
    }

    /** [VIDEO] starts playing in [profile]: it is locked, and the screen knows. */
    private fun startVideo(profile: Profile, expected: UnlockMethod) {
        runBlocking { lock.onVideoStarted(profile.id, VIDEO, null) }
        compose.waitUntil(5_000) { lock.lock.value?.unlock == expected }
        compose.waitForIdle()
    }

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private fun press(keys: List<Key>) = keys.forEach { press(it) }

    /** BACK on the playing video: the first one hides the controls, the next one is "leave". */
    private fun backTwice() = press(listOf(Key.Back, Key.Back))

    private fun systemBack() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun exists(tag: String) = compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, tag)).fetchSemanticsNodes().isNotEmpty()

    private fun storedLock() = runBlocking { lock.current() }

    // ---- Child ----------------------------------------------------------------------------------------

    @Test
    fun child_back_opensThePinPad_andTheVideoStays() {
        show(child)
        startVideo(child, UnlockMethod.PARENT_PIN)

        backTwice()
        compose.onNodeWithTag("lock_pin_prompt").assertExists()
        compose.onNodeWithTag("pin_key_5").assertIsFocused()
        assertThat(left).isEqualTo(0)

        // BACK on the pad returns to the video; BACK again asks again. Never out without the PIN.
        press(Key.Back)
        assertThat(exists("lock_pin_prompt")).isFalse()
        assertThat(left).isEqualTo(0)
        press(Key.Back)
        compose.onNodeWithTag("lock_pin_prompt").assertExists()
        systemBack() // the system's BACK closes it too
        assertThat(exists("lock_pin_prompt")).isFalse()
        repeat(3) { press(Key.Back) }
        assertThat(left).isEqualTo(0)
        assertThat(storedLock()).isNotNull()
    }

    @Test
    fun child_aWrongPin_staysLocked() {
        show(child)
        startVideo(child, UnlockMethod.PARENT_PIN)
        backTwice()

        press(listOf(Key.Two, Key.Four, Key.Six, Key.Eight, Key.DirectionCenter))
        compose.waitUntil(5_000) { exists("pin_notice") }
        compose.onNodeWithTag("pin_notice").assertTextEquals("رمز غير صحيح. بقيت 4 محاولات.")
        compose.onNodeWithTag("lock_pin_prompt").assertExists()
        assertThat(left).isEqualTo(0)
        assertThat(storedLock()?.videoId).isEqualTo(VIDEO)
    }

    @Test
    fun child_theRightPin_leavesThePlayer_andEndsTheLock() {
        show(child)
        startVideo(child, UnlockMethod.PARENT_PIN)
        backTwice()

        press(listOf(Key.One, Key.Three, Key.Five, Key.Seven, Key.DirectionCenter))
        compose.waitUntil(5_000) { left == 1 }
        assertThat(storedLock()).isNull()
        assertThat(lock.lock.value).isNull()
    }

    @Test
    fun child_theNaturalEnd_releasesTheLock_andGoesBackToTheChildHome() {
        // The real player's reports: its view model tells the lock (it stays "loading": no stream in JVM tests).
        val vm = PlayerViewModel(VIDEO, StreamRepository(FakeUiSource()), gate = PlayGate { awaitCancellation() }, lock = lock.playerHooks(child.id))
        show(child) { state ->
            val end by vm.end.collectAsState()
            val ending = end
            if (ending == null) {
                Osd(state)
            } else {
                EndCard(
                    ending,
                    "حكاية الأرنب الشجاع",
                    onBack = state::requestExit,
                    onReplay = vm::onReplay,
                    onPlayNext = {},
                    tickMs = NEVER,
                    paused = state.covered,
                    kidsHome = { home++ },
                )
            }
        }
        compose.runOnUiThread { vm.onPlaybackStarted(clip(VIDEO)) }
        compose.waitUntil(5_000) { lock.lock.value != null }
        backTwice()
        compose.onNodeWithTag("lock_pin_prompt").assertExists()
        press(Key.Back)

        compose.runOnUiThread { vm.onEnded(PlaybackProgress(600_000, 600_000, 600_000)) }
        compose.waitUntil(5_000) { lock.lock.value == null }
        compose.waitForIdle()
        assertThat(storedLock()).isNull()
        compose.onNodeWithTag("kids_home_countdown").assertTextEquals("نعود إلى الرئيسية خلال 10 ثوانٍ")

        // Free: BACK takes the child home, no PIN asked.
        systemBack()
        assertThat(home).isEqualTo(1)
        assertThat(exists("lock_pin_prompt")).isFalse()
    }

    // ---- Adult ------------------------------------------------------------------------------------------

    @Test
    fun adult_aShortBack_onlySaysHowToLeave() {
        show(adult)
        startVideo(adult, UnlockMethod.EXIT_CHALLENGE)
        backTwice()
        compose.onNodeWithTag("lock_hint").assertExists()
        assertThat(exists("lock_pin_prompt")).isFalse()
        assertThat(left).isEqualTo(0)
    }

    @Test
    fun adult_holdingLessThanTenSeconds_cancels_andLetsGoOfNothing() {
        show(adult)
        startVideo(adult, UnlockMethod.EXIT_CHALLENGE)

        compose.onRoot().performKeyInput {
            keyDown(Key.DirectionCenter)
            advanceEventTime(3_000)
        }
        compose.waitForIdle()
        compose.onNodeWithTag("lock_hold_ring").assertExists()
        compose.onNodeWithTag("lock_hold_seconds").assertTextEquals("7")
        // The controls stepped back behind the ring.
        assertThat(exists("osd_controls")).isFalse()

        compose.onRoot().performKeyInput {
            advanceEventTime(1_000)
            keyUp(Key.DirectionCenter)
        }
        compose.waitForIdle()
        assertThat(exists("lock_hold_ring")).isFalse()
        assertThat(exists("lock_phrase")).isFalse()
        // Letting go was not an OK press: the video did not pause.
        assertThat(actions).doesNotContain(PlayerAction.TogglePlayPause)
        assertThat(left).isEqualTo(0)
        assertThat(storedLock()?.videoId).isEqualTo(VIDEO)

        // BACK held for 9.9 seconds is still not enough.
        compose.onRoot().performKeyInput {
            keyDown(Key.Back)
            advanceEventTime(9_900)
            keyUp(Key.Back)
        }
        compose.waitForIdle()
        assertThat(exists("lock_phrase")).isFalse()
        assertThat(left).isEqualTo(0)
    }

    @Test
    fun adult_aTenSecondHold_thenThePhrase_leaves_andRecordsASurrender() {
        show(adult)
        startVideo(adult, UnlockMethod.EXIT_CHALLENGE)

        compose.onRoot().performKeyInput {
            keyDown(Key.Back)
            advanceEventTime(10_000)
        }
        compose.waitForIdle()
        compose.onNodeWithTag("lock_phrase").assertExists()
        compose.onNodeWithTag("lock_phrase_text").assertTextEquals(PHRASE)
        // The release of the held BACK belongs to the hold: it does not close the phrase.
        compose.onRoot().performKeyInput {
            advanceEventTime(300)
            keyUp(Key.Back)
        }
        compose.waitForIdle()
        compose.onNodeWithTag("lock_phrase").assertExists()

        // The app's own keyboard, on the D-pad: «ا» first (top right), «ب» to its left, «س» under it.
        compose.onNodeWithTag("kb_key_ا").assertIsFocused()
        press(Key.DirectionCenter)
        typed("ا")
        press(Key.DirectionLeft)
        compose.onNodeWithTag("kb_key_ب").assertIsFocused()
        press(Key.DirectionDown)
        compose.onNodeWithTag("kb_key_س").assertIsFocused()

        // A wrong phrase stays.
        click("kb_key_س")
        click("lock_phrase_exit")
        compose.onNodeWithTag("lock_phrase_notice").assertTextEquals("الجملة غير مطابقة. اكتبها كما تظهر تماماً.")
        // Back on the keyboard, on «حذف», to fix it.
        compose.onNodeWithTag("kb_delete").assertIsFocused()
        press(Key.DirectionCenter)
        typed("ا")

        // The rest of «الوقت ثمين».
        PHRASE.drop(1).forEach { char -> click(if (char == ' ') "kb_space" else "kb_key_$char") }
        typed(PHRASE)
        // Typed right: «خروج» has the focus, one OK away.
        compose.onNodeWithTag("lock_phrase_exit").assertIsFocused()
        press(Key.DirectionCenter)
        compose.waitUntil(5_000) { left == 1 }
        assertThat(surrendered).isEqualTo(1)
        assertThat(storedLock()).isNull()
        val surrenders = runBlocking { withTimeout(5_000) { usage.surrenders(adult.id, 0) } }
        assertThat(surrenders.map { it.videoId }).containsExactly(VIDEO)
    }

    @Test
    fun adult_aHoldInterruptedByHome_isForgotten() {
        show(adult)
        startVideo(adult, UnlockMethod.EXIT_CHALLENGE)
        compose.onRoot().performKeyInput {
            keyDown(Key.DirectionCenter)
            advanceEventTime(6_000)
        }
        compose.waitForIdle()
        compose.onNodeWithTag("lock_hold_ring").assertExists()

        // HOME with the button still down (the activity pauses): its release goes to the launcher, never here.
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
        assertThat(exists("lock_hold_ring")).isFalse()
        assertThat(exists("lock_phrase")).isFalse()
        assertThat(storedLock()?.videoId).isEqualTo(VIDEO)
    }

    @Test
    fun adult_thePhraseCanBeLeft_forTheVideo_andTheLockStays() {
        show(adult)
        startVideo(adult, UnlockMethod.EXIT_CHALLENGE)
        compose.onRoot().performKeyInput {
            keyDown(Key.DirectionCenter)
            advanceEventTime(10_050)
            keyUp(Key.DirectionCenter)
        }
        compose.waitForIdle()
        compose.onNodeWithTag("lock_phrase").assertExists()
        press(Key.Back)
        assertThat(exists("lock_phrase")).isFalse()
        assertThat(left).isEqualTo(0)
        assertThat(storedLock()?.videoId).isEqualTo(VIDEO)
        assertThat(runBlocking { usage.surrenders(adult.id, 0) }).isEmpty()
    }

    @Test
    fun withoutALock_backLeaves() {
        show(adult)
        backTwice()
        assertThat(left).isEqualTo(1)
    }

    private fun click(tag: String) {
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    private fun typed(text: String) =
        compose.onNodeWithTag("lock_phrase_typed").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, text))

    private companion object {
        const val PIN = "1357"
        const val VIDEO = "lockedvid01"
        const val PHRASE = "الوقت ثمين"
        const val NEVER = 3_600_000L
    }
}
