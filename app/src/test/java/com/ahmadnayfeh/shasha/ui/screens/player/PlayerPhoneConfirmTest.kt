package com.ahmadnayfeh.shasha.ui.screens.player

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.R
import com.ahmadnayfeh.shasha.TV_1080P
import com.ahmadnayfeh.shasha.TestStreams
import com.ahmadnayfeh.shasha.data.StreamRepository
import com.ahmadnayfeh.shasha.policy.MutableClock
import com.ahmadnayfeh.shasha.policy.PlaybackLock
import com.ahmadnayfeh.shasha.policy.PolicyConfig
import com.ahmadnayfeh.shasha.policy.PolicyEngine
import com.ahmadnayfeh.shasha.policy.SeriousMode
import com.ahmadnayfeh.shasha.policy.instantAt
import com.ahmadnayfeh.shasha.policy.kid
import com.ahmadnayfeh.shasha.policy.owner
import com.ahmadnayfeh.shasha.remote.ConfirmAnswer
import com.ahmadnayfeh.shasha.remote.PhoneConfirmations
import com.ahmadnayfeh.shasha.ui.FakeUiSource
import com.ahmadnayfeh.shasha.ui.UiFixtures
import com.ahmadnayfeh.shasha.ui.theme.ShashaTheme
import com.ahmadnayfeh.shasha.youtube.SourceErrorKind
import com.ahmadnayfeh.shasha.youtube.SourceException
import com.ahmadnayfeh.shasha.youtube.StreamDetails
import com.ahmadnayfeh.shasha.youtube.VideoSource
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/**
 * v1.2 (owner decision): a video that would start in Serious Mode while nothing on the TV could end it early (the adult
 * with the way out turned off) waits for the parent's «تأكيد» on the phone before anything plays: «رفض», two minutes or
 * the TV's «إلغاء» mean it does not start. A child never asks: the parent PIN on the TV ends a child's lock.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = TV_1080P)
class PlayerPhoneConfirmTest {
    @get:Rule
    val compose = createComposeRule()

    private val video = UiFixtures.videos[0]
    private val id = video.videoId

    /** Every extraction made to play the video. */
    private var extractions = 0
    private val source: VideoSource = object : VideoSource by FakeUiSource() {
        override suspend fun stream(videoId: String, allowLowQuality: Boolean): StreamDetails {
            extractions++
            return TestStreams.details(video)
        }
    }

    /** The phone as a test plays it: whether the «تأكيد» is needed, and the answer it gives (held until completed). */
    private class FakePhone(var needed: Boolean = true) : StartConfirmation {
        val asked = CopyOnWriteArrayList<Pair<String, String>>()
        val answer = CompletableDeferred<ConfirmAnswer>()

        @Volatile var withdrawn = false

        override fun needed(videoId: String) = needed

        override suspend fun ask(videoId: String, title: String): ConfirmAnswer {
            asked += videoId to title
            try {
                return answer.await()
            } catch (e: CancellationException) {
                withdrawn = true
                throw e
            }
        }
    }

    @Before
    fun setUp() = Dispatchers.setMain(Dispatchers.Unconfined)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun player(confirmation: StartConfirmation?) = PlayerViewModel(
        videoId = id,
        streams = StreamRepository(source),
        startConfirmation = confirmation,
        elapsedClock = { 1_000_000L },
        wallClock = { UiFixtures.NOW },
    )

    private fun PlayerViewModel.settled(): PlayerViewModel.Load =
        runBlocking { withTimeout(5_000) { load.first { it != PlayerViewModel.Load.Loading } } }

    private fun text(id: Int, vararg args: Any): String = RuntimeEnvironment.getApplication().getString(id, *args)

    @Test
    fun nothingPlays_untilThePhoneConfirms() {
        val phone = FakePhone()
        val vm = player(phone)
        assertThat(vm.settled()).isEqualTo(PlayerViewModel.Load.AwaitingPhone(video.title))
        compose.waitUntil(5_000) { phone.asked.isNotEmpty() }
        assertThat(phone.asked).containsExactly(id to video.title)

        phone.answer.complete(ConfirmAnswer.CONFIRMED)
        assertThat(vm.settled()).isInstanceOf(PlayerViewModel.Load.Ready::class.java)
        assertThat(extractions).isEqualTo(1)
    }

    @Test
    fun thePhoneRefuses_orDoesNotAnswer_theVideoDoesNotPlay() {
        for (answer in listOf(ConfirmAnswer.REFUSED, ConfirmAnswer.TIMED_OUT)) {
            val phone = FakePhone()
            val vm = player(phone)
            assertThat(vm.settled()).isInstanceOf(PlayerViewModel.Load.AwaitingPhone::class.java)
            phone.answer.complete(answer)
            assertThat(runBlocking { withTimeout(5_000) { vm.load.first { it !is PlayerViewModel.Load.AwaitingPhone } } })
                .isEqualTo(PlayerViewModel.Load.NotConfirmed(answer))
        }
    }

    @Test
    fun noConfirmationNeeded_playsAtOnce_withoutAsking() {
        val phone = FakePhone(needed = false)
        assertThat(player(phone).settled()).isInstanceOf(PlayerViewModel.Load.Ready::class.java)
        assertThat(phone.asked).isEmpty()
        // No phone side at all: the same.
        assertThat(player(null).settled()).isInstanceOf(PlayerViewModel.Load.Ready::class.java)
    }

    @Test
    fun confirmedOnce_laterExtractionsOfTheSameVisit_doNotAskAgain() {
        val phone = FakePhone()
        val vm = player(phone)
        vm.settled()
        phone.answer.complete(ConfirmAnswer.CONFIRMED)
        runBlocking { withTimeout(5_000) { vm.load.first { it is PlayerViewModel.Load.Ready } } }
        // The network drops and comes back: extracted again, not asked again.
        vm.fail(SourceException(SourceErrorKind.NETWORK, "IOException", null, null))
        vm.retry()
        assertThat(runBlocking { withTimeout(5_000) { vm.load.first { it is PlayerViewModel.Load.Ready } } })
            .isInstanceOf(PlayerViewModel.Load.Ready::class.java)
        assertThat(phone.asked).hasSize(1)
        assertThat(extractions).isEqualTo(2)
    }

    @Test
    fun theTvsCancel_withdrawsTheRequest() {
        val phone = FakePhone()
        val vm = player(phone)
        vm.settled()
        vm.cancelStart()
        compose.waitUntil(5_000) { phone.withdrawn }
        assertThat(vm.load.value).isEqualTo(PlayerViewModel.Load.AwaitingPhone(video.title))
    }

    @Test
    fun aReplay_isANewStart_whenTheConfirmationIsNeeded() {
        val phone = FakePhone()
        val vm = player(phone)
        assertThat(vm.replayNeedsConfirmation()).isTrue()
        phone.needed = false
        assertThat(vm.replayNeedsConfirmation()).isFalse()
        assertThat(player(null).replayNeedsConfirmation()).isFalse()
    }

    @Test
    fun whoMustConfirm_isTheAdultWithoutAWayOut_andOnlyForAVideoTheLockDoesNotHoldYet() {
        var config = PolicyConfig(adultEscape = false)
        val engine = PolicyEngine(MutableClock(instantAt("2026-09-28T12:00"))) { config }
        val lock = MutableStateFlow<PlaybackLock?>(null)
        val confirmations = PhoneConfirmations(nowMs = { 0L })
        val adult = PhoneStartConfirmation(engine, lock, owner(), playlistId = null, confirmations)
        assertThat(adult.needed(id)).isTrue()

        // The lock already holds this video (back from Home, a restart): nothing to ask.
        lock.value = engine.lockPlayback(owner(), id)
        assertThat(adult.needed(id)).isFalse()
        // A playlist's lock moving on to its next video: nothing to ask there either.
        lock.value = engine.lockPlayback(owner(), "othervideo1", playlistId = "PLsame")
        assertThat(PhoneStartConfirmation(engine, lock, owner(), playlistId = "PLsame", confirmations).needed(id)).isFalse()
        lock.value = null

        // A child never: the parent PIN on the TV ends a child's lock.
        assertThat(PhoneStartConfirmation(engine, lock, kid(), playlistId = null, confirmations).needed(id)).isFalse()
        // The adult's way out on: never.
        config = PolicyConfig(adultEscape = true)
        assertThat(adult.needed(id)).isFalse()
        // The adult's Serious Mode off: nothing locks, nothing to confirm.
        config = PolicyConfig(seriousMode = SeriousMode(children = true, adult = false), adultEscape = false)
        assertThat(adult.needed(id)).isFalse()
        // The children's switch off changes nothing for the adult.
        config = PolicyConfig(seriousMode = SeriousMode(children = false, adult = true), adultEscape = false)
        assertThat(adult.needed(id)).isTrue()
    }

    @Test
    fun withTheRealPhoneSide_thePhoneSeesTheVideo_andItsAnswerStartsIt_orTheTvWithdrawsIt() {
        val engine = PolicyEngine(MutableClock(instantAt("2026-09-28T12:00"))) { PolicyConfig(adultEscape = false) }
        val confirmations = PhoneConfirmations(nowMs = { 0L })
        val start = PhoneStartConfirmation(engine, MutableStateFlow(null), owner(), playlistId = null, confirmations)

        val vm = player(start)
        assertThat(vm.settled()).isEqualTo(PlayerViewModel.Load.AwaitingPhone(video.title))
        val waiting = confirmations.waiting()
        assertThat(waiting.profileId).isEqualTo(owner().id)
        assertThat(waiting.videoId).isEqualTo(id)
        assertThat(waiting.title).isEqualTo(video.title)
        confirmations.answer(waiting.id, approve = true)
        assertThat(runBlocking { withTimeout(5_000) { vm.load.first { it is PlayerViewModel.Load.Ready } } })
            .isInstanceOf(PlayerViewModel.Load.Ready::class.java)

        // Another visit, cancelled on the TV: withdrawn from the phone at once.
        val other = player(start)
        other.settled()
        val second = confirmations.waiting()
        other.cancelStart()
        runBlocking { withTimeout(5_000) { confirmations.pending.first { it == null } } }
        assertThat(confirmations.answer(second.id, approve = true)).isNull()
    }

    /** What waits for the phone, once the player asked (it asks right after it shows the prompt). */
    private fun PhoneConfirmations.waiting() = runBlocking { withTimeout(5_000) { pending.first { it != null } } }!!

    // ---- On screen ----

    private class Wait(private val connected: Boolean, private val seconds: Int = 105) : PhoneWait {
        override fun secondsLeft() = seconds

        override fun phoneConnected() = connected
    }

    @Test
    fun thePrompt_namesTheVideo_waitsWithTheTimeLeft_andCancelLeaves() {
        val phone = FakePhone()
        val vm = player(phone)
        vm.settled()
        var backs = 0
        compose.setContent {
            ShashaTheme { Content(vm, Wait(connected = true), onBack = { backs++ }) }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("phone_confirm").assertExists()
        compose.onNodeWithTag("phone_confirm_title").assertTextEquals(text(R.string.confirm_phone_title))
        compose.onNodeWithTag("phone_confirm_video").assertTextEquals(text(R.string.quoted_name, video.title))
        compose.onNodeWithTag("phone_confirm_waiting").assertExists()
        compose.onNodeWithTag("phone_confirm_pair").assertDoesNotExist()
        compose.onNodeWithTag("phone_confirm_cancel").assertIsFocused()

        compose.onNodeWithTag("phone_confirm_cancel").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertThat(backs).isEqualTo(1)
        compose.waitUntil(5_000) { phone.withdrawn }
    }

    @Test
    fun noPhoneThere_thePromptSaysHowToGetOne_withTheWayToPairing() {
        val vm = player(FakePhone())
        vm.settled()
        var pairs = 0
        compose.setContent {
            ShashaTheme { Content(vm, Wait(connected = false), onPairPhone = { pairs++ }) }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("phone_confirm_no_phone").assertExists()
        compose.onNodeWithTag("phone_confirm_title").assertTextEquals(text(R.string.confirm_no_phone))
        compose.onNodeWithTag("phone_confirm_waiting").assertDoesNotExist()
        compose.onNodeWithTag("phone_confirm_cancel").assertIsFocused()
        compose.onNodeWithTag("phone_confirm_pair").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertThat(pairs).isEqualTo(1)
    }

    @Test
    fun aRefusal_saysSo_andLeavesThePlayer() {
        val phone = FakePhone()
        val vm = player(phone)
        vm.settled()
        var backs = 0
        val told = CopyOnWriteArrayList<ConfirmAnswer>()
        compose.setContent {
            ShashaTheme { Content(vm, Wait(connected = true), onBack = { backs++ }, onNotConfirmed = { told += it }) }
        }
        compose.waitForIdle()
        phone.answer.complete(ConfirmAnswer.REFUSED)
        compose.waitUntil(5_000) { backs == 1 }
        assertThat(told).containsExactly(ConfirmAnswer.REFUSED)
    }

    @androidx.compose.runtime.Composable
    private fun Content(
        vm: PlayerViewModel,
        wait: PhoneWait,
        onBack: () -> Unit = {},
        onPairPhone: (() -> Unit)? = null,
        onNotConfirmed: (ConfirmAnswer) -> Unit = {},
    ) = PlayerContent(
        vm = vm,
        extractorVersion = "v0.26.5",
        factory = { error("No player in this test") },
        meterFor = { error("Nothing is metered in this test") },
        onBack = onBack,
        onCheckUpdates = {},
        onPlayNext = { _, _ -> },
        phoneWait = wait,
        onPairPhone = onPairPhone,
        onNotConfirmed = onNotConfirmed,
    )
}
