package com.ahmadnayfeh.shasha.ui.screens.player

import androidx.test.ext.junit.runners.AndroidJUnit4
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
import com.ahmadnayfeh.shasha.lock.LockController
import com.ahmadnayfeh.shasha.player.PlaybackLocking
import com.ahmadnayfeh.shasha.policy.ALLOWED_PLAYLIST
import com.ahmadnayfeh.shasha.policy.DenyReason
import com.ahmadnayfeh.shasha.policy.LookedUpVideo
import com.ahmadnayfeh.shasha.policy.MutableClock
import com.ahmadnayfeh.shasha.policy.PinHasher
import com.ahmadnayfeh.shasha.policy.PlayOrigin
import com.ahmadnayfeh.shasha.policy.PolicyEngine
import com.ahmadnayfeh.shasha.policy.PolicyGate
import com.ahmadnayfeh.shasha.policy.SessionController
import com.ahmadnayfeh.shasha.policy.SwitchResult
import com.ahmadnayfeh.shasha.policy.UnlockMethod
import com.ahmadnayfeh.shasha.policy.VideoLookup
import com.ahmadnayfeh.shasha.policy.clip
import com.ahmadnayfeh.shasha.policy.instantAt
import com.ahmadnayfeh.shasha.policy.kid
import com.ahmadnayfeh.shasha.policy.owner
import com.ahmadnayfeh.shasha.ui.FakeUiSource
import com.ahmadnayfeh.shasha.ui.lock.lockMethodFor
import com.ahmadnayfeh.shasha.youtube.SourceErrorKind
import com.ahmadnayfeh.shasha.youtube.SourceException
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

/**
 * The player, the policy gate and the lock together, as PlayerScreen wires them: the ways a lock used to be
 * lost or turned against its own viewer (phase-4 review) keep it now.
 *
 * No stream can be built in JVM tests: an allowed video goes on to its extraction and fails there
 * (CONTENT_UNAVAILABLE); what matters is that the gate let it through.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class PlayerLockGateTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var db: ShashaDatabase
    private lateinit var stores: TestDataStores
    private val scope = testScope()
    private val clock = MutableClock(instantAt("2026-09-28T12:00"))
    private val engine = PolicyEngine(clock)
    private lateinit var active: ActiveProfile
    private lateinit var gate: PolicyGate
    private lateinit var lock: LockController
    private val adult = owner()
    private val child = kid()

    @Before
    fun setUp() = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        db = inMemoryDatabase()
        stores = TestDataStores(tmp.root)
        val store = stores.open()
        val profiles = ProfileRepository(db)
        profiles.ensureOwner(adult.name)
        profiles.upsert(child)
        active = ActiveProfile.create(profiles, store, scope, ownerName = adult.name)
        val session = SessionController(engine, active.profile, UsageRepository(db), OverridesRepository(store), scope)
        val pin = ParentPinRepository(store, clock, PinHasher(iterations = 1_000))
        pin.setup("1357")
        val locks = PlaybackLockStore(store, scope)
        gate = PolicyGate(engine, active, profiles, session, locks, pin, VideoLookup { LookedUpVideo(clip(it), null) }, scope)
        lock = LockController(engine, locks, active.profile, gate::verifyParentPin, pin::remainingLockMillis, {}, scope)
        active.awaitProfile()
        Unit
    }

    @After
    fun tearDown() {
        scope.cancelAndWait()
        stores.closeAll()
        db.close()
        Dispatchers.resetMain()
    }

    private fun enterChild() = runBlocking {
        assertThat(gate.switchProfile(child.id, pin = null)).isEqualTo(SwitchResult.Switched)
        withTimeout(5_000) { active.profile.first { it?.id == child.id } }
    }

    /** What the players told the lock (then passed on to it). */
    private val reports = mutableListOf<String>()

    private fun player(videoId: String, origin: PlayOrigin, profileId: String, playlistId: String? = null, check: PlayGate = PlayGate { gate.checkPlay(it, origin) }): PlayerViewModel {
        val hooks = lock.playerHooks(profileId)
        val recording = object : PlaybackLocking by hooks {
            override fun onUnavailable(videoId: String) {
                reports += "unavailable $videoId"
                hooks.onUnavailable(videoId)
            }
        }
        return PlayerViewModel(videoId, StreamRepository(FakeUiSource()), gate = check, lock = recording, playlistId = playlistId)
    }

    private fun PlayerViewModel.settled(): PlayerViewModel.Load = runBlocking { withTimeout(5_000) { load.first { it != PlayerViewModel.Load.Loading } } }

    @Test
    fun aChildsPlaylist_theEndedVideosPlayerAskingAgain_isNotRefusedAsLocked() = runBlocking<Unit> {
        enterChild()
        lock.onVideoStarted(child.id, "v1aaaaaaaaa", ALLOWED_PLAYLIST)
        lock.onVideoEnded(child.id, "v1aaaaaaaaa", nextVideoId = "v2bbbbbbbbb")
        assertThat(lock.current()?.videoId).isEqualTo("v2bbbbbbbbb")

        // v1's player after Home / "time up" in the countdown, or restored with the back stack.
        val shown = player("v1aaaaaaaaa", PlayOrigin.Playlist(ALLOWED_PLAYLIST), child.id, ALLOWED_PLAYLIST).settled()
        assertThat(shown).isNotEqualTo(PlayerViewModel.Load.Denied(DenyReason.LOCKED))
        // The lock stayed with the playlist: still the PIN to leave, still on v2.
        assertThat(lock.current()?.videoId).isEqualTo("v2bbbbbbbbb")
        assertThat(lockMethodFor(lock.current(), child.id, "v1aaaaaaaaa", ALLOWED_PLAYLIST)).isEqualTo(UnlockMethod.PARENT_PIN)
    }

    @Test
    fun aPlayerThatCameFromALink_askingAgainForItsOwnLockedVideo_keepsTheLock() = runBlocking<Unit> {
        lock.onVideoStarted(adult.id, "a1aaaaaaaaa", null)
        val locked = lock.current()
        assertThat(locked?.unlock).isEqualTo(UnlockMethod.EXIT_CHALLENGE)
        val shown = player("a1aaaaaaaaa", PlayOrigin.Link, adult.id).settled()
        assertThat(shown).isNotInstanceOf(PlayerViewModel.Load.Denied::class.java)
        // Only the video's failing extraction (a JVM test has no stream) answers: CONTENT_UNAVAILABLE ends it, as
        // any video YouTube no longer serves. What matters: the gate's "locked" answer never did.
        assertThat((shown as PlayerViewModel.Load.Failed).error.kind).isEqualTo(SourceErrorKind.CONTENT_UNAVAILABLE)
    }

    @Test
    fun aLockRestoredBeforeTheNetworkIsUp_staysLocked() = runBlocking<Unit> {
        enterChild()
        lock.onVideoStarted(child.id, "k1aaaaaaaaa", null)
        val locked = lock.current()
        // The TV restarts before its Wi-Fi is up: the restored player's check has to extract, and that fails.
        val offline = PlayGate { throw SourceException(SourceErrorKind.NETWORK, "UnknownHostException", "offline", null) }
        val shown = player("k1aaaaaaaaa", PlayOrigin.Navigation, child.id, check = offline).settled()
        assertThat((shown as PlayerViewModel.Load.Failed).error.kind).isEqualTo(SourceErrorKind.NETWORK)
        // Nothing was reported: the lock stays until the network is back and the video plays on.
        assertThat(reports).isEmpty()
        assertThat(lock.current()).isEqualTo(locked)
    }
}
