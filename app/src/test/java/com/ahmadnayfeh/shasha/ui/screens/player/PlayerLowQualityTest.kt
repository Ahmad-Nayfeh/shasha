package com.ahmadnayfeh.shasha.ui.screens.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.Fixtures
import com.ahmadnayfeh.shasha.TV_1080P
import com.ahmadnayfeh.shasha.TestStreams
import com.ahmadnayfeh.shasha.data.StreamRepository
import com.ahmadnayfeh.shasha.player.PlaybackLocking
import com.ahmadnayfeh.shasha.player.ResumeState
import com.ahmadnayfeh.shasha.policy.PlayCheck
import com.ahmadnayfeh.shasha.policy.PlayedPart
import com.ahmadnayfeh.shasha.ui.FakeUiSource
import com.ahmadnayfeh.shasha.ui.UiFixtures
import com.ahmadnayfeh.shasha.ui.theme.ShashaTheme
import com.ahmadnayfeh.shasha.youtube.SourceErrorKind
import com.ahmadnayfeh.shasha.youtube.SourceException
import com.ahmadnayfeh.shasha.youtube.StreamDetails
import com.ahmadnayfeh.shasha.youtube.VideoSource
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
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
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Owner decision: "made for kids" videos, which the extractor only gets as a muxed 360p file. A child profile
 * plays that file (the stream carries the height the player's badge shows); the adult gets the unified error
 * screen (LOW_QUALITY_ONLY) with «شغّل بجودة 360p على أي حال», which asks for the same video with low quality
 * allowed. A 360p file that then fails to play is the error screen again, in both profiles.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = TV_1080P)
class PlayerLowQualityTest {
    @get:Rule
    val compose = createComposeRule()

    private val video = UiFixtures.videos[0]
    private val id = video.videoId

    /** How each extraction was asked: with low quality allowed or not. */
    private val asked = CopyOnWriteArrayList<Boolean>()

    /** Held while set: the allowed extraction waits (the screen then stays on its loader). */
    private var allowedGate: CompletableDeferred<Unit>? = null

    /** A "made for kids" video as :youtube answers it. */
    private val source: VideoSource = object : VideoSource by FakeUiSource() {
        override suspend fun stream(videoId: String, allowLowQuality: Boolean): StreamDetails {
            asked += allowLowQuality
            if (!allowLowQuality) throw SourceException(SourceErrorKind.LOW_QUALITY_ONLY, "MuxedOnly360p", "Only muxed 360p", null)
            allowedGate?.await()
            return TestStreams.details(video, lowQualityHeight = 360)
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

    @Before
    fun setUp() = Dispatchers.setMain(Dispatchers.Unconfined)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun player(child: Boolean, gate: PlayGate? = null) = PlayerViewModel(
        videoId = id,
        streams = StreamRepository(source),
        gate = gate,
        elapsedClock = { 1_000_000L },
        wallClock = { UiFixtures.NOW },
        lock = locking,
        allowLowQuality = child,
    )

    private fun PlayerViewModel.settled(): PlayerViewModel.Load =
        runBlocking { withTimeout(5_000) { load.first { it != PlayerViewModel.Load.Loading } } }

    @Test
    fun aChildProfile_playsTheLowQualityFile_andItsStreamCarriesTheBadge() {
        val vm = player(child = true)
        val ready = vm.settled() as PlayerViewModel.Load.Ready
        assertThat(ready.stream.lowQualityHeight).isEqualTo(360)
        assertThat(asked).containsExactly(true)
        assertThat(vm.lowQualityAllowed).isTrue()
        assertThat(unavailable).isEmpty()
    }

    @Test
    fun theAdult_seesLowQualityOnly_andTheButtonAsksForTheSameVideoWithLowQualityAllowed() {
        val vm = player(child = false)
        val failed = vm.settled() as PlayerViewModel.Load.Failed
        assertThat(failed.error.kind).isEqualTo(SourceErrorKind.LOW_QUALITY_ONLY)
        assertThat(asked).containsExactly(false)

        vm.playLowQuality()
        val ready = vm.settled() as PlayerViewModel.Load.Ready
        assertThat(ready.stream.lowQualityHeight).isEqualTo(360)
        assertThat(asked).containsExactly(false, true).inOrder()
        assertThat(vm.lowQualityAllowed).isTrue()
    }

    @Test
    fun thePolicysLowQualityLookUp_isNotPlayedForTheAdultUntilHeChoosesIt() {
        // A link's check extracts the video itself (with low quality allowed: it only needs the video's facts).
        val lookedUp = TestStreams.details(video, lowQualityHeight = 360)
        val vm = player(child = false, gate = PlayGate { PlayCheck.Allowed(lookedUp) })
        assertThat((vm.settled() as PlayerViewModel.Load.Failed).error.kind).isEqualTo(SourceErrorKind.LOW_QUALITY_ONLY)
        assertThat(asked).containsExactly(false)

        vm.playLowQuality()
        assertThat((vm.settled() as PlayerViewModel.Load.Ready).stream).isSameInstanceAs(lookedUp)
        assertThat(asked).containsExactly(false) // the check's own extraction, no third one
    }

    @Test
    fun onlyLowQualityOnlyOffersTheLowQualityFile() {
        val blocked = object : VideoSource by FakeUiSource() {
            override suspend fun stream(videoId: String, allowLowQuality: Boolean): StreamDetails {
                asked += allowLowQuality
                throw SourceException(SourceErrorKind.BLOCKED_403, "HTTP 403", null, null)
            }
        }
        val vm = PlayerViewModel(videoId = id, streams = StreamRepository(blocked))
        assertThat((vm.settled() as PlayerViewModel.Load.Failed).error.kind).isEqualTo(SourceErrorKind.BLOCKED_403)
        vm.playLowQuality()
        assertThat(vm.lowQualityAllowed).isFalse()
        assertThat(asked).containsExactly(false)
    }

    @Test
    fun aLowQualityFileRefusedWhilePlaying_isTheErrorScreen_inBothProfiles() {
        for (child in listOf(true, false)) {
            val vm = player(child)
            if (!child) {
                vm.settled()
                vm.playLowQuality()
            }
            val ready = vm.settled() as PlayerViewModel.Load.Ready
            // googlevideo answers 403 on a fresh extraction: no second try, the unified error screen.
            vm.onPlaybackError(ready, SourceException(SourceErrorKind.BLOCKED_403, "HTTP 403", null, null), ResumeState())
            val failed = vm.load.value as PlayerViewModel.Load.Failed
            assertThat(failed.error.kind).isEqualTo(SourceErrorKind.BLOCKED_403)
        }
    }

    @Test
    fun theAdultsErrorScreen_offersTheLowQualityFile_andChoosingItAsksForIt() {
        allowedGate = CompletableDeferred() // the loader stays: nothing below tries to build a player
        val vm = player(child = false)
        vm.settled()
        compose.setContent { ShashaTheme { content(vm, offerLowQuality = true) } }
        compose.waitForIdle()
        compose.onNodeWithTag("error_screen").assertExists()
        compose.onNodeWithTag("error_check_updates").assertExists()
        compose.onNodeWithTag("error_play_low_quality").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertThat(asked).containsExactly(false, true).inOrder()
        // Still extracting (held above): no player is built in this test.
        assertThat(vm.load.value).isEqualTo(PlayerViewModel.Load.Loading)
    }

    @Test
    fun withoutTheOffer_theErrorScreenHasNoLowQualityButton() {
        val vm = player(child = false)
        vm.settled()
        compose.setContent { ShashaTheme { content(vm, offerLowQuality = false) } }
        compose.waitForIdle()
        compose.onNodeWithTag("error_screen").assertExists()
        compose.onNodeWithTag("error_play_low_quality").assertDoesNotExist()
    }

    @Test
    fun theOsdShowsThe360pBadge_onlyForALowQualityFile() {
        var state by mutableStateOf(Fixtures.osd.copy(lowQualityHeight = 360))
        compose.setContent {
            ShashaTheme { PlayerOsd(state, { Fixtures.progress }, {}, {}, autoHideMs = 3_600_000L) }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("osd_low_quality").assertTextEquals("360p")
        compose.onNodeWithTag("osd_low_quality").assertContentDescriptionEquals("جودة منخفضة: 360p")

        state = state.copy(lowQualityHeight = null)
        compose.waitForIdle()
        compose.onNodeWithTag("osd_low_quality").assertDoesNotExist()
    }

    @androidx.compose.runtime.Composable
    private fun content(vm: PlayerViewModel, offerLowQuality: Boolean) = PlayerContent(
        vm = vm,
        extractorVersion = "v0.26.5",
        factory = { error("No player in this test") },
        meterFor = { error("Nothing is metered in this test") },
        onBack = {},
        onCheckUpdates = {},
        onPlayNext = { _, _ -> },
        offerLowQuality = offerLowQuality,
    )
}
