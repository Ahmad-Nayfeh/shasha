package com.ahmadnayfeh.shasha.ui.screens.player

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.Fixtures
import com.ahmadnayfeh.shasha.TV_1080P
import com.ahmadnayfeh.shasha.TV_1080P_EN
import com.ahmadnayfeh.shasha.TestStreams
import com.ahmadnayfeh.shasha.data.AppLanguage
import com.ahmadnayfeh.shasha.data.StreamRepository
import com.ahmadnayfeh.shasha.player.PlaybackLocking
import com.ahmadnayfeh.shasha.player.QualityChoice
import com.ahmadnayfeh.shasha.player.ResumeState
import com.ahmadnayfeh.shasha.policy.PlayedPart
import com.ahmadnayfeh.shasha.ui.FakeUiSource
import com.ahmadnayfeh.shasha.ui.UiFixtures
import com.ahmadnayfeh.shasha.ui.theme.ShashaTheme
import com.ahmadnayfeh.shasha.youtube.SourceErrorKind
import com.ahmadnayfeh.shasha.youtube.SourceException
import com.ahmadnayfeh.shasha.youtube.StreamDetails
import com.ahmadnayfeh.shasha.youtube.VideoSource
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import java.util.concurrent.atomic.AtomicInteger

/**
 * The TV's own decoder failing on a video (SourceErrorKind.DEVICE_DECODER, the owner's TCL on a Short): nobody changed
 * anything at YouTube, so the first failure is not an error screen. The same stream plays again, once, from where it
 * stopped, with the safe plan; only the second failure is the error, and its screen blames no one and offers another try.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = TV_1080P)
class PlayerDecoderRetryTest {
    @get:Rule
    val compose = createComposeRule()

    private val video = UiFixtures.videos[0]
    private val id = video.videoId

    /** How many times the video was extracted. */
    private val extractions = AtomicInteger()

    private val source: VideoSource = object : VideoSource by FakeUiSource() {
        override suspend fun stream(videoId: String, allowLowQuality: Boolean): StreamDetails {
            extractions.incrementAndGet()
            return TestStreams.details(video)
        }
    }

    private val unavailable = mutableListOf<String>()
    private val locking = object : PlaybackLocking {
        override fun onStarted(videoId: String, playlistId: String?) = Unit
        override fun onEnded(videoId: String, nextVideoId: String?, played: PlayedPart?) = Unit
        override fun onUnavailable(videoId: String) {
            unavailable += videoId
        }
    }

    private val decoderFailure = SourceException(SourceErrorKind.DEVICE_DECODER, "ERROR_CODE_DECODING_FAILED", "MediaCodec died", null)

    @Before
    fun setUp() = Dispatchers.setMain(Dispatchers.Unconfined)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun player() = PlayerViewModel(
        videoId = id,
        streams = StreamRepository(source),
        elapsedClock = { 1_000_000L },
        wallClock = { UiFixtures.NOW },
        lock = locking,
    )

    /** The first loaded video that is not [other] (a player of a new extraction starts for it). */
    private fun PlayerViewModel.readyOtherThan(other: PlayerViewModel.Load.Ready? = null): PlayerViewModel.Load.Ready =
        runBlocking { withTimeout(5_000) { load.first { it is PlayerViewModel.Load.Ready && it !== other } } } as PlayerViewModel.Load.Ready

    @Test
    fun theFirstDecoderFailure_playsTheSameVideoAgainFromWhereItStopped_withTheSafePlan_andNoErrorScreen() {
        val vm = player()
        val first = vm.readyOtherThan()
        assertThat(vm.safePlan).isFalse()

        vm.onPlaybackError(first, decoderFailure, ResumeState(positionMs = 42_000, quality = QualityChoice.Fixed(720)))

        // Not the error screen: a new player starts for the same stream (it is not extracted again: its URLs are fine).
        val again = vm.readyOtherThan(first)
        assertThat(vm.load.value).isSameInstanceAs(again)
        assertThat(again.stream).isSameInstanceAs(first.stream)
        assertThat(extractions.get()).isEqualTo(1)
        assertThat(vm.safePlan).isTrue()
        assertThat(vm.resume.positionMs).isEqualTo(42_000)
        assertThat(vm.resume.quality).isEqualTo(QualityChoice.Fixed(720))
        assertThat(unavailable).isEmpty()
    }

    @Test
    fun aSecondDecoderFailure_isTheErrorScreen_andNothingPlaysAgainByItself() {
        val vm = player()
        val first = vm.readyOtherThan()
        vm.onPlaybackError(first, decoderFailure, ResumeState(positionMs = 10_000))
        val second = vm.readyOtherThan(first)

        vm.onPlaybackError(second, decoderFailure, ResumeState(positionMs = 11_000))

        val failed = vm.load.value as PlayerViewModel.Load.Failed
        assertThat(failed.error.kind).isEqualTo(SourceErrorKind.DEVICE_DECODER)
        assertThat(failed.error.technicalName).isEqualTo("ERROR_CODE_DECODING_FAILED")
        assertThat(vm.resume.positionMs).isEqualTo(11_000)
        // The video cannot play here: the lock ends like for any video that is unavailable (a child is not held to it).
        assertThat(unavailable).containsExactly(id)
    }

    @Test
    fun theTryAgainButton_playsSafely_butAFailureThenIsTheErrorAtOnce() {
        val vm = player()
        val first = vm.readyOtherThan()
        vm.onPlaybackError(first, decoderFailure, ResumeState())
        val second = vm.readyOtherThan(first)
        vm.onPlaybackError(second, decoderFailure, ResumeState())
        assertThat(vm.load.value).isInstanceOf(PlayerViewModel.Load.Failed::class.java)

        vm.retry()
        val third = vm.readyOtherThan(second)
        assertThat(vm.safePlan).isTrue() // the picture that failed is not the one that plays now
        vm.onPlaybackError(third, decoderFailure, ResumeState())
        assertThat(vm.load.value).isInstanceOf(PlayerViewModel.Load.Failed::class.java)
    }

    @Test
    fun anyOtherFailure_isNotRetriedAndLeavesThePlanAlone() {
        for (kind in SourceErrorKind.entries.filter { it != SourceErrorKind.DEVICE_DECODER && it != SourceErrorKind.BLOCKED_403 }) {
            val vm = player()
            val ready = vm.readyOtherThan()
            vm.onPlaybackError(ready, SourceException(kind, "x", null, null), ResumeState())
            assertThat((vm.load.value as PlayerViewModel.Load.Failed).error.kind).isEqualTo(kind)
            assertThat(vm.safePlan).isFalse()
        }
    }

    @Test
    fun aPlayerThatWasAlreadyReplaced_doesNotUseUpTheRetry() {
        val vm = player()
        val first = vm.readyOtherThan()
        vm.onPlaybackError(first, decoderFailure, ResumeState(positionMs = 5_000))
        val second = vm.readyOtherThan(first)

        // The first player's last words, after its screen was replaced: ignored.
        vm.onPlaybackError(first, decoderFailure, ResumeState(positionMs = 99_000))
        assertThat(vm.load.value).isSameInstanceAs(second)
        assertThat(vm.resume.positionMs).isEqualTo(5_000)
    }

    @Test
    fun theErrorScreenOfTheTvsDecoder_blamesNoOne_offersAnotherTry_andNoUpdate() {
        var retries = 0
        compose.setContent {
            ShashaTheme {
                PlayerErrorScreen(
                    error = Fixtures.error(SourceErrorKind.DEVICE_DECODER, "ERROR_CODE_DECODING_FAILED"),
                    extractorVersion = "v0.26.5",
                    onCheckUpdates = {},
                    onBack = {},
                    onRetry = { retries++ },
                    onPlayLowQuality = null,
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("error_title").assertTextEquals("تعذّر على شاشتك تشغيل هذا الفيديو")
        compose.onNodeWithTag("error_explanation").assertTextEquals("جرّب فيديو آخر؛ وإن تكرر فأعد تشغيل الشاشة.")
        compose.onNodeWithTag("error_check_updates").assertDoesNotExist()
        compose.onNodeWithTag("error_retry").performSemanticsAction(SemanticsActions.OnClick)
        assertThat(retries).isEqualTo(1)
    }

    @Test
    @Config(sdk = [35], qualifiers = TV_1080P_EN)
    fun theSameScreenInEnglish() {
        compose.setContent {
            ShashaTheme(language = AppLanguage.ENGLISH) {
                PlayerErrorScreen(
                    error = Fixtures.error(SourceErrorKind.DEVICE_DECODER, "ERROR_CODE_DECODING_FAILED"),
                    extractorVersion = "v0.26.5",
                    onCheckUpdates = {},
                    onBack = {},
                    onRetry = {},
                    onPlayLowQuality = null,
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("error_title").assertTextEquals("This TV couldn't play this video")
        compose.onNodeWithTag("error_explanation").assertTextEquals("Try another video. If it keeps happening, restart the TV.")
        compose.onNodeWithTag("error_check_updates").assertDoesNotExist()
    }
}
