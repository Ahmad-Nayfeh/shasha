package com.ahmadnayfeh.shasha.ui.screens.player

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.TV_1080P
import com.ahmadnayfeh.shasha.data.ActiveProfile
import com.ahmadnayfeh.shasha.data.OverridesRepository
import com.ahmadnayfeh.shasha.data.ParentPinRepository
import com.ahmadnayfeh.shasha.data.PlaybackLockStore
import com.ahmadnayfeh.shasha.data.ProfileRepository
import com.ahmadnayfeh.shasha.data.StreamRepository
import com.ahmadnayfeh.shasha.data.TestDataStores
import com.ahmadnayfeh.shasha.data.UsageRepository
import com.ahmadnayfeh.shasha.data.cancelAndWait
import com.ahmadnayfeh.shasha.data.db.ShashaDatabase
import com.ahmadnayfeh.shasha.data.inMemoryDatabase
import com.ahmadnayfeh.shasha.data.testScope
import com.ahmadnayfeh.shasha.policy.ALLOWED_CHANNEL
import com.ahmadnayfeh.shasha.policy.ALLOWED_PLAYLIST
import com.ahmadnayfeh.shasha.policy.DenyReason
import com.ahmadnayfeh.shasha.policy.LookedUpVideo
import com.ahmadnayfeh.shasha.policy.MutableClock
import com.ahmadnayfeh.shasha.policy.OTHER_CHANNEL
import com.ahmadnayfeh.shasha.policy.PinHasher
import com.ahmadnayfeh.shasha.policy.PlayOrigin
import com.ahmadnayfeh.shasha.policy.PolicyEngine
import com.ahmadnayfeh.shasha.policy.PolicyGate
import com.ahmadnayfeh.shasha.policy.SessionController
import com.ahmadnayfeh.shasha.policy.SwitchResult
import com.ahmadnayfeh.shasha.policy.VideoLookup
import com.ahmadnayfeh.shasha.policy.clip
import com.ahmadnayfeh.shasha.policy.instantAt
import com.ahmadnayfeh.shasha.policy.kid
import com.ahmadnayfeh.shasha.policy.owner
import com.ahmadnayfeh.shasha.ui.FakeUiSource
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
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/**
 * SPEC acceptance: a video from a channel outside the child's allowlist never plays, even as a link from
 * the phone that names an allowlisted playlist. The player asks the real [PolicyGate] before anything else:
 * no stream is extracted for playing, no player is created, the child sees why and «رجوع».
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = TV_1080P)
class PlayerPolicyTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @get:Rule
    val compose = createComposeRule()

    private lateinit var db: ShashaDatabase
    private lateinit var stores: TestDataStores
    private val scope = testScope()
    private val clock = MutableClock(instantAt("2026-09-28T12:00"))
    private val engine = PolicyEngine(clock)
    private lateinit var gate: PolicyGate

    /** What the extraction says each video is (the link names an allowlisted playlist; the video is not in it). */
    private val videos = listOf(clip("outside0001", channelId = OTHER_CHANNEL), clip("inside00001", channelId = ALLOWED_CHANNEL)).associateBy { it.videoId }

    /** Every stream asked for in order to play it. */
    private val playStreams = CopyOnWriteArrayList<String>()
    private val source: VideoSource = object : VideoSource by FakeUiSource() {
        override suspend fun stream(videoId: String, allowLowQuality: Boolean): StreamDetails {
            playStreams += videoId
            throw SourceException(SourceErrorKind.CONTENT_UNAVAILABLE, "NotInTests", "StreamDetails cannot be built outside :youtube", null)
        }
    }
    private val streams = StreamRepository(source)

    @Before
    fun setUp() = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        db = inMemoryDatabase()
        stores = TestDataStores(tmp.root)
        val store = stores.open()
        val profiles = ProfileRepository(db)
        val adult = owner()
        profiles.ensureOwner(adult.name)
        val child = kid()
        profiles.upsert(child)
        val active = ActiveProfile.create(profiles, store, scope, ownerName = adult.name)
        val session = SessionController(engine, active.profile, UsageRepository(db), OverridesRepository(store), scope)
        val pin = ParentPinRepository(store, clock, PinHasher(iterations = 1_000))
        // The link's video is looked up for its channel only; nothing playable comes back from it.
        val lookup = VideoLookup { id -> LookedUpVideo(videos.getValue(id), stream = null) }
        gate = PolicyGate(engine, active, profiles, session, PlaybackLockStore(store, scope), pin, lookup, scope)
        active.awaitProfile()
        pin.setup("1357")
        assertThat(gate.switchProfile(child.id, pin = null)).isEqualTo(SwitchResult.Switched)
        withTimeout(5_000) { gate.filter.first { it.profile?.id == child.id } }
        Unit
    }

    @After
    fun tearDown() {
        scope.cancelAndWait()
        stores.closeAll()
        db.close()
        Dispatchers.resetMain()
    }

    /** The player of a link from the phone: `watch?v=<id>&list=<the allowlisted playlist>`. */
    private fun playerForLink(videoId: String) = PlayerViewModel(
        videoId = videoId,
        streams = streams,
        gate = PlayGate { id -> gate.checkPlay(id, playOrigin(fromLink = true, playlistId = ALLOWED_PLAYLIST)) },
    )

    private fun PlayerViewModel.settled(): PlayerViewModel.Load =
        runBlocking { withTimeout(5_000) { load.first { it != PlayerViewModel.Load.Loading } } }

    @Test
    fun aLinkToAVideoOutsideTheAllowlist_isDenied_andNothingIsExtractedToPlay() {
        val vm = playerForLink("outside0001")
        assertThat(vm.settled()).isEqualTo(PlayerViewModel.Load.Denied(DenyReason.NOT_IN_ALLOWLIST))
        assertThat(playStreams).isEmpty()

        // On screen: why, and «رجوع». No player is created (its factory is never even asked for).
        var factoryAsked = 0
        var backs = 0
        compose.setContent {
            ShashaTheme {
                PlayerContent(
                    vm = vm,
                    extractorVersion = "test",
                    factory = {
                        factoryAsked++
                        error("No player for a denied video")
                    },
                    meterFor = { error("Nothing is metered for a denied video") },
                    onBack = { backs++ },
                    onCheckUpdates = {},
                    onPlayNext = { _, _ -> },
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("policy_notice").assertExists()
        compose.onNodeWithTag("policy_notice_title").assertTextEquals("غير مسموح في هذا الملف")
        compose.onNodeWithTag("policy_notice_back").performSemanticsAction(SemanticsActions.OnClick)
        assertThat(backs).isEqualTo(1)
        assertThat(factoryAsked).isEqualTo(0)
        assertThat(playStreams).isEmpty()
    }

    @Test
    fun aLinkToAnAllowlistedChannelsVideo_goesOnToPlay() {
        val vm = playerForLink("inside00001")
        // Allowed: the player extracts the stream (which cannot be built in a JVM test, hence the failure).
        val settled = vm.settled()
        assertThat(settled).isInstanceOf(PlayerViewModel.Load.Failed::class.java)
        assertThat((settled as PlayerViewModel.Load.Failed).error.technicalName).isEqualTo("NotInTests")
        assertThat(playStreams).containsExactly("inside00001")
    }

    @Test
    fun theOriginOfAPlay() {
        assertThat(playOrigin(fromLink = true, playlistId = ALLOWED_PLAYLIST)).isEqualTo(PlayOrigin.Link)
        assertThat(playOrigin(fromLink = false, playlistId = ALLOWED_PLAYLIST)).isEqualTo(PlayOrigin.Playlist(ALLOWED_PLAYLIST))
        assertThat(playOrigin(fromLink = false, playlistId = null)).isEqualTo(PlayOrigin.Navigation)
    }
}
