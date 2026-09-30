package com.ahmadnayfeh.shasha.lock

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.data.ParentPinRepository
import com.ahmadnayfeh.shasha.data.PinResult
import com.ahmadnayfeh.shasha.data.PlaybackLockStore
import com.ahmadnayfeh.shasha.data.TestDataStores
import com.ahmadnayfeh.shasha.data.UsageRepository
import com.ahmadnayfeh.shasha.data.cancelAndWait
import com.ahmadnayfeh.shasha.data.db.ShashaDatabase
import com.ahmadnayfeh.shasha.data.inMemoryDatabase
import com.ahmadnayfeh.shasha.data.testScope
import com.ahmadnayfeh.shasha.policy.ALLOWED_PLAYLIST
import com.ahmadnayfeh.shasha.policy.MutableClock
import com.ahmadnayfeh.shasha.policy.PinHasher
import com.ahmadnayfeh.shasha.policy.PlaybackLock
import com.ahmadnayfeh.shasha.policy.PlayedPart
import com.ahmadnayfeh.shasha.policy.PolicyEngine
import com.ahmadnayfeh.shasha.policy.Profile
import com.ahmadnayfeh.shasha.policy.SurrenderEvent
import com.ahmadnayfeh.shasha.policy.UnlockMethod
import com.ahmadnayfeh.shasha.policy.epochMsAt
import com.ahmadnayfeh.shasha.policy.instantAt
import com.ahmadnayfeh.shasha.policy.kid
import com.ahmadnayfeh.shasha.policy.owner
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * SPEC §4's in-app lock, app-wide: what locks, what releases (the owner's decision: a natural end frees a
 * child too), the two ways out and the phone's, and that a restart keeps it.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class LockControllerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var db: ShashaDatabase
    private lateinit var stores: TestDataStores
    private lateinit var store: DataStore<Preferences>
    private lateinit var pin: ParentPinRepository
    private lateinit var usage: UsageRepository
    private val scope = testScope()
    private val clock = MutableClock(instantAt("2026-09-28T12:00"))
    private val engine = PolicyEngine(clock)
    private val adult = owner()
    private val child = kid()
    private val current = MutableStateFlow<Profile?>(child)

    @Before
    fun setUp() = runBlocking {
        db = inMemoryDatabase()
        stores = TestDataStores(tmp.root)
        store = stores.open()
        pin = ParentPinRepository(store, clock, PinHasher(iterations = 1_000))
        pin.setup(PIN)
        usage = UsageRepository(db)
    }

    @After
    fun tearDown() {
        scope.cancelAndWait()
        stores.closeAll()
        db.close()
    }

    /** Throws from every surrender while set (a full disk). */
    private var surrendersFail = false

    /** The app's controller over [over] (after a restart: the restarted file, with its own PIN repository). */
    private fun controller(over: DataStore<Preferences> = store): LockController {
        val pins = if (over === store) pin else ParentPinRepository(over, clock, PinHasher(iterations = 1_000))
        return LockController(
            engine = engine,
            store = PlaybackLockStore(over, scope),
            profile = current,
            verifyPin = pins::verify,
            pinLockMillis = pins::remainingLockMillis,
            surrenders = {
                check(!surrendersFail) { "database or disk is full" }
                usage.recordSurrender(it)
            },
            scope = scope,
        )
    }

    private fun <T> wait(block: suspend () -> T): T = runBlocking { withTimeout(5_000) { block() } }

    private fun LockController.shows(expected: PlaybackLock?) = wait { lock.first { it == expected } }

    // ---- Child ------------------------------------------------------------------------------------

    @Test
    fun child_aStartLocks_withThePin_andAStartAgainKeepsTheSameLock() = runBlocking<Unit> {
        val lock = controller()
        lock.onVideoStarted(child.id, "v1", null)
        val locked = PlaybackLock(child.id, UnlockMethod.PARENT_PIN, "v1", null, epochMsAt("2026-09-28T12:00"))
        assertThat(lock.current()).isEqualTo(locked)
        assertThat(lock.lock.value).isEqualTo(locked)

        // Back from Home an hour later: the same video's lock is kept as it was.
        clock.advanceMillis(3_600_000)
        lock.onVideoStarted(child.id, "v1", null)
        assertThat(lock.current()).isEqualTo(locked)
    }

    @Test
    fun child_aNaturalEnd_releases() = runBlocking<Unit> {
        val lock = controller()
        lock.onVideoStarted(child.id, "v1", null)
        lock.onVideoEnded(child.id, "v1", nextVideoId = null)
        assertThat(lock.current()).isNull()
        assertThat(lock.lock.value).isNull()
    }

    @Test
    fun child_aPlaylistKeepsTheChildLockedToItsLastVideo() = runBlocking<Unit> {
        val lock = controller()
        lock.onVideoStarted(child.id, "v1", ALLOWED_PLAYLIST)
        lock.onVideoEnded(child.id, "v1", nextVideoId = "v2")
        assertThat(lock.current()?.videoId).isEqualTo("v2")
        assertThat(lock.current()?.playlistId).isEqualTo(ALLOWED_PLAYLIST)
        // The next video's player starts: the moved lock is kept.
        val moved = lock.current()
        lock.onVideoStarted(child.id, "v2", ALLOWED_PLAYLIST)
        assertThat(lock.current()).isEqualTo(moved)
        // A late report of the first video changes nothing.
        lock.onVideoEnded(child.id, "v1", nextVideoId = null)
        assertThat(lock.current()).isEqualTo(moved)
        lock.onVideoEnded(child.id, "v2", nextVideoId = null)
        assertThat(lock.current()).isNull()
    }

    @Test
    fun child_theParentPin_endsTheLock_aWrongOneDoesNot() = runBlocking<Unit> {
        val lock = controller()
        lock.onVideoStarted(child.id, "v1", null)
        assertThat(lock.unlockWithPin("0000")).isEqualTo(PinUnlock.Refused(PinResult.Wrong(attemptsLeft = 4)))
        assertThat(lock.current()).isNotNull()
        assertThat(lock.unlockWithPin(PIN)).isEqualTo(PinUnlock.Unlocked)
        assertThat(lock.current()).isNull()
        assertThat(lock.lock.value).isNull()
        // Nothing locked any more: the PIN pad just lets the child out.
        assertThat(lock.unlockWithPin("9999")).isEqualTo(PinUnlock.Unlocked)
    }

    @Test
    fun aVideoSkippedToItsEnd_staysLocked_untilItIsPlayed() = runBlocking<Unit> {
        val lock = controller()
        lock.onVideoStarted(child.id, "v1", null)
        val locked = lock.current()
        lock.onVideoEnded(child.id, "v1", nextVideoId = null, played = PlayedPart(playedMs = 12_000, leftMs = 600_000))
        assertThat(lock.current()).isEqualTo(locked)
        assertThat(lock.lock.value).isEqualTo(locked)
        // Replayed and watched through.
        lock.onVideoStarted(child.id, "v1", null)
        lock.onVideoEnded(child.id, "v1", nextVideoId = null, played = PlayedPart(playedMs = 600_000, leftMs = 600_000))
        assertThat(lock.current()).isNull()
    }

    @Test
    fun aVideoThatCannotPlay_releasesOnlyItsOwnLock() = runBlocking<Unit> {
        val lock = controller()
        lock.onVideoStarted(child.id, "v1", null)
        lock.onVideoUnavailable(child.id, "other")
        assertThat(lock.current()?.videoId).isEqualTo("v1")
        lock.onVideoUnavailable(child.id, "v1")
        assertThat(lock.current()).isNull()
    }

    // ---- Adult ------------------------------------------------------------------------------------

    @Test
    fun adult_eachVideoIsItsOwnLock_theEndReleasesEvenInsideAPlaylist() = runBlocking<Unit> {
        current.value = adult
        val lock = controller()
        lock.onVideoStarted(adult.id, "a1", ALLOWED_PLAYLIST)
        assertThat(lock.current()?.unlock).isEqualTo(UnlockMethod.EXIT_CHALLENGE)
        lock.onVideoEnded(adult.id, "a1", nextVideoId = "a2")
        assertThat(lock.current()).isNull()
        lock.onVideoStarted(adult.id, "a2", ALLOWED_PLAYLIST)
        assertThat(lock.current()?.videoId).isEqualTo("a2")
    }

    @Test
    fun adult_thePinDoesNotOpen_theChallengeDoes_andRecordsTheSurrender() = runBlocking<Unit> {
        current.value = adult
        val lock = controller()
        lock.onVideoStarted(adult.id, "a1", null)
        assertThat(lock.unlockWithPin(PIN)).isEqualTo(PinUnlock.NotWithPin)
        assertThat(lock.current()).isNotNull()

        val phrase = "الوقت ثمين"
        assertThat(lock.exitWithChallenge(9_999, phrase, phrase)).isEqualTo(ChallengeResult.Refused)
        assertThat(lock.exitWithChallenge(10_000, phrase, "الوقت")).isEqualTo(ChallengeResult.Refused)
        assertThat(usage.surrenders(adult.id, 0)).isEmpty()

        clock.advanceMillis(60_000)
        val surrender = SurrenderEvent(adult.id, epochMsAt("2026-09-28T12:01"), "a1")
        assertThat(lock.exitWithChallenge(10_000, phrase, phrase)).isEqualTo(ChallengeResult.Exited(surrender))
        assertThat(lock.current()).isNull()
        assertThat(usage.surrenders(adult.id, 0)).containsExactly(surrender)

        // A video that already ended by itself: out without a surrender.
        assertThat(lock.exitWithChallenge(10_000, phrase, phrase)).isEqualTo(ChallengeResult.Exited(null))
        assertThat(usage.surrenders(adult.id, 0)).hasSize(1)
    }

    @Test
    fun adult_aSurrenderThatCannotBeStored_keepsTheLock() = runBlocking<Unit> {
        current.value = adult
        val lock = controller()
        lock.onVideoStarted(adult.id, "a1", null)
        val locked = lock.current()
        surrendersFail = true
        val phrase = "الوقت ثمين"
        // The phrase screen says «تعذّر الخروج. حاول مرة أخرى.»; he is still in the video.
        assertThat(lock.exitWithChallenge(10_000, phrase, phrase)).isEqualTo(ChallengeResult.Refused)
        assertThat(lock.current()).isEqualTo(locked)
        // The phone's unlock does not skip the stats either.
        assertThrows(IllegalStateException::class.java) { runBlocking { lock.unlockByParent() } }
        assertThat(lock.current()).isEqualTo(locked)

        surrendersFail = false
        assertThat(lock.exitWithChallenge(10_000, phrase, phrase)).isInstanceOf(ChallengeResult.Exited::class.java)
        assertThat(lock.current()).isNull()
        assertThat(usage.surrenders(adult.id, 0)).hasSize(1)
    }

    // ---- The phone, other profiles, restarts --------------------------------------------------------

    @Test
    fun theParentFromThePhone_releasesAnyLock_theAdultsAsASurrender() = runBlocking<Unit> {
        val lock = controller()
        assertThat(lock.unlockByParent()).isFalse()
        lock.onVideoStarted(child.id, "v1", null)
        assertThat(lock.unlockByParent()).isTrue()
        assertThat(lock.current()).isNull()
        assertThat(usage.surrenders(child.id, 0)).isEmpty()

        current.value = adult
        lock.onVideoStarted(adult.id, "a1", null)
        clock.advanceMillis(120_000)
        assertThat(lock.unlockByParent()).isTrue()
        assertThat(lock.current()).isNull()
        // Leaving before the end from the phone still shows in his stats (SPEC §4).
        assertThat(usage.surrenders(adult.id, 0)).containsExactly(SurrenderEvent(adult.id, epochMsAt("2026-09-28T12:02"), "a1"))
    }

    @Test
    fun aPlayerOfAProfileThatIsNoLongerCurrent_locksNothing() = runBlocking<Unit> {
        val lock = controller()
        current.value = adult
        lock.onVideoStarted(child.id, "v1", null)
        assertThat(lock.current()).isNull()
    }

    @Test
    fun theLockOfAProfileThatIsGone_isDropped() = runBlocking<Unit> {
        val lock = controller()
        lock.onVideoStarted(child.id, "v1", null)
        // The child's profile was deleted from the phone: the owner's profile takes over.
        current.value = adult
        lock.shows(null)
        assertThat(lock.current()).isNull()
    }

    @Test
    fun theLock_survivesARestart() = runBlocking<Unit> {
        val before = controller()
        before.onVideoStarted(child.id, "v1", ALLOWED_PLAYLIST)
        val locked = before.current()!!

        // The process dies and starts again: a new controller over the same file.
        val after = controller(stores.restart())
        after.shows(locked)
        assertThat(after.current()).isEqualTo(locked)
        assertThat(after.unlockWithPin(PIN)).isEqualTo(PinUnlock.Unlocked)
    }

    @Test
    fun thePlayersReports_areCarriedOutInOrder() {
        val lock = controller()
        val hooks = lock.playerHooks(child.id)
        hooks.onStarted("v1", ALLOWED_PLAYLIST)
        hooks.onEnded("v1", nextVideoId = "v2", played = null)
        hooks.onStarted("v2", ALLOWED_PLAYLIST)
        lock.shows(PlaybackLock(child.id, UnlockMethod.PARENT_PIN, "v2", ALLOWED_PLAYLIST, epochMsAt("2026-09-28T12:00")))
        hooks.onEnded("v2", nextVideoId = null, played = PlayedPart(600_000, 600_000))
        lock.shows(null)
    }

    private companion object {
        const val PIN = "1357"
    }
}
