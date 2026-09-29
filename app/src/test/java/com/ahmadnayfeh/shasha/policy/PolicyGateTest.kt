package com.ahmadnayfeh.shasha.policy

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.data.ADULT_PROFILE_ID
import com.ahmadnayfeh.shasha.data.ActiveProfile
import com.ahmadnayfeh.shasha.data.OverridesRepository
import com.ahmadnayfeh.shasha.data.ParentPinRepository
import com.ahmadnayfeh.shasha.data.PinResult
import com.ahmadnayfeh.shasha.data.PlaybackLockStore
import com.ahmadnayfeh.shasha.data.ProfileRepository
import com.ahmadnayfeh.shasha.data.TestDataStores
import com.ahmadnayfeh.shasha.data.UsageRepository
import com.ahmadnayfeh.shasha.data.WatchRepository
import com.ahmadnayfeh.shasha.data.cancelAndWait
import com.ahmadnayfeh.shasha.data.db.ShashaDatabase
import com.ahmadnayfeh.shasha.data.inMemoryDatabase
import com.ahmadnayfeh.shasha.data.testScope
import com.ahmadnayfeh.shasha.youtube.ChannelItem
import com.ahmadnayfeh.shasha.youtube.PlaylistItem
import com.ahmadnayfeh.shasha.youtube.SourceErrorKind
import com.ahmadnayfeh.shasha.youtube.SourceException
import com.ahmadnayfeh.shasha.youtube.VideoItem
import com.ahmadnayfeh.shasha.youtube.YouTubeLink
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

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class PolicyGateTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var db: ShashaDatabase
    private lateinit var stores: TestDataStores
    private val scope = testScope()
    private val clock = MutableClock(instantAt("2026-09-28T12:00"))
    private val engine = PolicyEngine(clock)

    private lateinit var profiles: ProfileRepository
    private lateinit var active: ActiveProfile
    private lateinit var usage: UsageRepository
    private lateinit var session: SessionController
    private lateinit var locks: PlaybackLockStore
    private lateinit var pin: ParentPinRepository
    private lateinit var gate: PolicyGate

    /** What the extraction says about each video; [lookups] records what had to be extracted. */
    private val extracted = mutableMapOf<String, VideoItem>()
    private val lookups = mutableListOf<String>()

    private val child = kid()
    private val adult = owner()

    @Before
    fun setUp() = runBlocking {
        db = inMemoryDatabase()
        stores = TestDataStores(tmp.root)
        val store = stores.open()
        profiles = ProfileRepository(db)
        profiles.ensureOwner(adult.name)
        profiles.upsert(child)
        active = ActiveProfile.create(profiles, store, scope, ownerName = adult.name)
        usage = UsageRepository(db)
        session = SessionController(engine, active.profile, usage, OverridesRepository(store), scope)
        locks = PlaybackLockStore(store, scope)
        pin = ParentPinRepository(store, clock, PinHasher(iterations = 1_000))
        val lookup = VideoLookup { id ->
            lookups += id
            LookedUpVideo(extracted[id] ?: throw SourceException(SourceErrorKind.CONTENT_UNAVAILABLE, "ContentNotAvailableException", id, null), null)
        }
        gate = PolicyGate(engine, active, profiles, session, locks, pin, lookup, scope)
        active.awaitProfile()
        Unit
    }

    @After
    fun tearDown() {
        scope.cancelAndWait()
        stores.closeAll()
        db.close()
    }

    private suspend fun enterChild() {
        if (!pin.isSet()) pin.setup(PIN)
        assertThat(gate.switchProfile(child.id, pin = null)).isEqualTo(SwitchResult.Switched)
        withTimeout(5_000) { gate.filter.first { it.profile?.id == child.id } }
    }

    private suspend fun useAdult(change: (Profile) -> Profile) {
        val edited = profiles.update(adult.id, change)!!
        withTimeout(5_000) { gate.filter.first { it.profile == edited } }
    }

    // ---- Links (SPEC acceptance: a channel outside the allowlist never plays, even through a link) -------

    @Test
    fun child_cannotPlayAVideoOutsideTheAllowlist_throughALink_evenWithAnAllowedPlaylistInIt() = runBlocking<Unit> {
        enterChild()
        extracted["outside0001"] = clip("outside0001", channelId = OTHER_CHANNEL)
        extracted["inside00001"] = clip("inside00001", channelId = ALLOWED_CHANNEL)

        // https://www.youtube.com/watch?v=outside0001&list=<the allowlisted playlist>
        val link = YouTubeLink.Video("outside0001", startSeconds = null, playlistId = ALLOWED_PLAYLIST)
        assertThat(gate.checkLink(link)).isEqualTo(PlayCheck.Denied(DenyReason.NOT_IN_ALLOWLIST))
        // A link never takes the word of a list item either: the extraction decides.
        assertThat(gate.checkPlay("outside0001", PlayOrigin.Link, known = clip("outside0001", channelId = ALLOWED_CHANNEL)))
            .isEqualTo(PlayCheck.Denied(DenyReason.NOT_IN_ALLOWLIST))
        assertThat(lookups).containsExactly("outside0001", "outside0001")

        // A link to a video of an allowlisted channel plays.
        assertThat(gate.checkLink(YouTubeLink.Video("inside00001", null, playlistId = OTHER_PLAYLIST))).isEqualTo(PlayCheck.Allowed(null))
        // The same outside video, taken from the allowlisted playlist's own page, plays: the app itself vouches for it.
        assertThat(gate.checkPlay("outside0001", PlayOrigin.Playlist(ALLOWED_PLAYLIST), known = extracted["outside0001"]))
            .isEqualTo(PlayCheck.Allowed(null))
        assertThat(gate.checkPlay("outside0001", PlayOrigin.Playlist(OTHER_PLAYLIST), known = extracted["outside0001"]))
            .isEqualTo(PlayCheck.Denied(DenyReason.NOT_IN_ALLOWLIST))
    }

    @Test
    fun checkPlay_extractsOnlyWhenTheItemDoesNotSayEnough() = runBlocking<Unit> {
        enterChild()
        val known = clip("known000001")
        assertThat(gate.checkPlay(known.videoId, PlayOrigin.Navigation, known)).isEqualTo(PlayCheck.Allowed(null))
        assertThat(lookups).isEmpty()

        // No channel on the item (or only an id, e.g. after the app was restarted in the player): extracted.
        extracted["nochannel01"] = clip("nochannel01", channelId = OTHER_CHANNEL)
        assertThat(gate.checkPlay("nochannel01", PlayOrigin.Navigation, clip("nochannel01", channelId = null)))
            .isEqualTo(PlayCheck.Denied(DenyReason.NOT_IN_ALLOWLIST))
        // An RSS item has no duration: where shorts are hidden, the extraction tells whether it is one.
        extracted["rssshort001"] = clip("rssshort001", durationSeconds = 45)
        assertThat(gate.checkPlay("rssshort001", PlayOrigin.Navigation, clip("rssshort001", durationSeconds = null)))
            .isEqualTo(PlayCheck.Denied(DenyReason.SHORTS_HIDDEN))
        assertThat(lookups).containsExactly("nochannel01", "rssshort001").inOrder()

        // Extraction failures reach the caller (the unified error screen), never an "allowed".
        assertThrows(SourceException::class.java) { runBlocking { gate.checkPlay("missing0001", PlayOrigin.Navigation) } }
    }

    // ---- Lists -------------------------------------------------------------------------------------

    @Test
    fun child_searchResultsAreFiltered_thoughTheChildHasNoSearch() = runBlocking<Unit> {
        enterChild()
        val filter = gate.filter.value
        assertThat(filter.features!!.search).isFalse()
        assertThat(filter.features!!.textInput).isFalse()

        val results = listOf(
            clip("allowed0001"),
            clip("other000001", channelId = OTHER_CHANNEL),
            clip("short000001", short = true),
            ChannelItem(ALLOWED_CHANNEL, "مسموحة", null, null),
            ChannelItem(OTHER_CHANNEL, "أخرى", null, null),
            PlaylistItem(ALLOWED_PLAYLIST, "قائمة مسموحة", null, null, 10),
            PlaylistItem(OTHER_PLAYLIST, "قائمة أخرى", null, null, 10),
        )
        assertThat(filter.items(results)).containsExactly(results[0], results[3], results[5]).inOrder()
        assertThat(gate.canOpenChannel(OTHER_CHANNEL)).isEqualTo(Decision.Deny(DenyReason.NOT_IN_ALLOWLIST))
        assertThat(gate.canOpenChannel(ALLOWED_CHANNEL)).isEqualTo(Decision.Allow)
        assertThat(gate.canOpenPlaylist(OTHER_PLAYLIST, ownerChannelId = null)).isEqualTo(Decision.Deny(DenyReason.NOT_IN_ALLOWLIST))
        assertThat(gate.canOpenPlaylist(OTHER_PLAYLIST, ownerChannelId = ALLOWED_CHANNEL)).isEqualTo(Decision.Allow)
    }

    @Test
    fun child_pagesOfChannelsAndPlaylists() = runBlocking<Unit> {
        enterChild()
        val filter = gate.filter.value
        // A channel's own Videos tab: items without a channel id are that channel's uploads.
        val tab = listOf(clip("a0000000001", channelId = null), clip("a0000000002", durationSeconds = 50))
        assertThat(filter.channelVideos(ALLOWED_CHANNEL, tab).map { it.videoId }).containsExactly("a0000000001")
        assertThat(filter.channelVideos(OTHER_CHANNEL, tab)).isEmpty()

        // An allowlisted playlist shows its videos whatever their channel (shorts still hidden)...
        val page = listOf(clip("p0000000001", channelId = OTHER_CHANNEL), clip("p0000000002", channelId = OTHER_CHANNEL, short = true), clip("p0000000003"))
        assertThat(filter.playlistVideos(ALLOWED_PLAYLIST, page).map { it.videoId }).containsExactly("p0000000001", "p0000000003").inOrder()
        // ...a playlist that is only visible through its allowlisted channel shows that channel's videos only.
        assertThat(filter.playlistVideos(OTHER_PLAYLIST, page).map { it.videoId }).containsExactly("p0000000003")
        // Outside a playlist page, the same videos follow the channel rule.
        assertThat(filter.videos(page).map { it.videoId }).containsExactly("p0000000003")
    }

    @Test
    fun adult_blocklistAndShorts() = runBlocking<Unit> {
        val home = listOf(
            clip("fine0000001", channelId = OTHER_CHANNEL),
            clip("blocked0001", channelId = BLOCKED_CHANNEL),
            clip("keyword0001", channelId = OTHER_CHANNEL, title = "مقلب مرعب جداً"),
            clip("short000001", channelId = OTHER_CHANNEL, short = true),
            clip("shortlen001", channelId = OTHER_CHANNEL, durationSeconds = 170),
            clip("live0000001", channelId = OTHER_CHANNEL, durationSeconds = 60, live = true),
        )
        // The adult sees everything, shorts included, until the blocklist says otherwise.
        assertThat(gate.filter.value.videos(home)).isEqualTo(home)
        assertThat(gate.filter.value.features!!.search).isTrue()

        useAdult { it.copy(blocklist = it.blocklist.withChannel(ListedChannel(BLOCKED_CHANNEL, "محظورة")).withKeyword("مُرعب")) }
        assertThat(gate.filter.value.videos(home).map { it.videoId })
            .containsExactly("fine0000001", "short000001", "shortlen001", "live0000001").inOrder()
        assertThat(gate.canOpenChannel(BLOCKED_CHANNEL)).isEqualTo(Decision.Deny(DenyReason.BLOCKED_CHANNEL))
        assertThat(gate.filter.value.items(listOf(ChannelItem(BLOCKED_CHANNEL, "محظورة", null, null)))).isEmpty()
        assertThat(gate.checkPlay("blocked0001", PlayOrigin.Navigation, home[1])).isEqualTo(PlayCheck.Denied(DenyReason.BLOCKED_CHANNEL))
        assertThat(gate.checkPlay("keyword0001", PlayOrigin.Navigation, home[2])).isEqualTo(PlayCheck.Denied(DenyReason.BLOCKED_KEYWORD))

        // Shorts hidden on request: the /shorts/ link, the flag, 3 minutes or less; a live stream is no short.
        useAdult { it.copy(hideShorts = true) }
        assertThat(gate.filter.value.videos(home).map { it.videoId }).containsExactly("fine0000001", "live0000001").inOrder()
        assertThat(gate.checkPlay("shortlen001", PlayOrigin.Navigation, home[4])).isEqualTo(PlayCheck.Denied(DenyReason.SHORTS_HIDDEN))
    }

    // ---- Session and lock ---------------------------------------------------------------------------

    @Test
    fun timeUpAndBedtime_stopNewPlayback_contentRulesComeFirst() = runBlocking<Unit> {
        enterChild()
        val allowed = clip("allowed0001")
        val outside = clip("outside0001", channelId = OTHER_CHANNEL)
        usage.addForeground(child.id, engine.today(), 3600)

        assertThat(gate.checkPlay(allowed.videoId, PlayOrigin.Navigation, allowed)).isEqualTo(PlayCheck.Denied(DenyReason.TIME_UP))
        assertThat(gate.checkPlay(outside.videoId, PlayOrigin.Navigation, outside)).isEqualTo(PlayCheck.Denied(DenyReason.NOT_IN_ALLOWLIST))

        session.grantUnlock(15)
        assertThat(gate.checkPlay(allowed.videoId, PlayOrigin.Navigation, allowed)).isEqualTo(PlayCheck.Allowed(null))

        clock.set("2026-09-28T22:30")
        assertThat(gate.checkPlay(allowed.videoId, PlayOrigin.Navigation, allowed)).isEqualTo(PlayCheck.Denied(DenyReason.OUTSIDE_SCHEDULE))
        assertThat(withTimeout(5_000) { gate.lockState.first { it.session is SessionState.OutsideSchedule } }.playbackLocked).isFalse()
    }

    @Test
    fun theGuardsState_keepsACurrentChildIn_untilTheParentLetsItOut() = runBlocking<Unit> {
        val letOut = MutableStateFlow(false)
        val guarded = PolicyGate(engine, active, profiles, session, locks, pin, VideoLookup { error("unused") }, scope, childLetOut = letOut)
        // The adult, nothing locked, no limit: the TV is free.
        val adultState = withTimeout(5_000) { guarded.lockState.first { !it.childProfile } }
        assertThat(engine.guardShouldEnforce(adultState)).isFalse()

        enterChild()
        val inside = withTimeout(5_000) { guarded.lockState.first { it.childProfile } }
        assertThat(inside.playbackLocked).isFalse()
        assertThat(engine.guardShouldEnforce(inside)).isTrue()

        // «خروج» with the parent PIN (RootViewModel sets the flag): the guard lets go while the picker is up.
        letOut.value = true
        assertThat(engine.guardShouldEnforce(withTimeout(5_000) { guarded.lockState.first { !it.childProfile } })).isFalse()
    }

    @Test
    fun aLockedVideo_letsNothingElseStart_butItselfContinues() = runBlocking<Unit> {
        enterChild()
        val locked = clip("locked00001")
        val other = clip("other000001")
        extracted[locked.videoId] = locked
        locks.set(engine.lockPlayback(child, locked.videoId))

        assertThat(gate.checkPlay(other.videoId, PlayOrigin.Navigation, other)).isEqualTo(PlayCheck.Denied(DenyReason.LOCKED))
        assertThat(gate.checkPlay(locked.videoId, PlayOrigin.Navigation, locked)).isEqualTo(PlayCheck.Allowed(null))
        // A link from the phone never replaces a locked video (nor starts it again: it is on screen)...
        assertThat(gate.checkLink(YouTubeLink.Video(locked.videoId, null, null))).isEqualTo(PlayCheck.Denied(DenyReason.LOCKED))
        // ...but a player that came from a link asks again for its own locked video (after Home, standby): it
        // continues, and is not taken for "unavailable" (that would release its lock).
        assertThat(gate.checkPlay(locked.videoId, PlayOrigin.Link)).isEqualTo(PlayCheck.Allowed(null))
        extracted[other.videoId] = other
        assertThat(gate.checkPlay(other.videoId, PlayOrigin.Link)).isEqualTo(PlayCheck.Denied(DenyReason.LOCKED))
        assertThat(withTimeout(5_000) { gate.lockState.first { it.playbackLocked } }.playbackLocked).isTrue()
        assertThat(engine.guardShouldEnforce(gate.lockState.value)).isTrue()
    }

    @Test
    fun aChildsPlaylistLock_letsItsOwnPlaylistGoOn_whileTheEndedVideoCountsDown() = runBlocking<Unit> {
        enterChild()
        val first = clip("playlist001", channelId = OTHER_CHANNEL)
        val second = clip("playlist002", channelId = OTHER_CHANNEL)
        // The first video ended: the lock moved on to the second while the first still shows its countdown.
        locks.set(engine.onVideoEnded(engine.lockPlayback(child, first.videoId, ALLOWED_PLAYLIST), nextVideoId = second.videoId))

        // Home (or "time up") in the countdown: the first video's player asks again; it is the lock's own playlist.
        assertThat(gate.checkPlay(first.videoId, PlayOrigin.Playlist(ALLOWED_PLAYLIST), first)).isEqualTo(PlayCheck.Allowed(null))
        assertThat(gate.checkPlay(second.videoId, PlayOrigin.Playlist(ALLOWED_PLAYLIST), second)).isEqualTo(PlayCheck.Allowed(null))
        // Nothing else: another playlist, the same video outside its playlist, a card of the home.
        assertThat(gate.checkPlay(first.videoId, PlayOrigin.Playlist(OTHER_PLAYLIST), first)).isEqualTo(PlayCheck.Denied(DenyReason.NOT_IN_ALLOWLIST))
        val allowed = clip("allowed0001")
        assertThat(gate.checkPlay(allowed.videoId, PlayOrigin.Playlist(OTHER_PLAYLIST), allowed)).isEqualTo(PlayCheck.Denied(DenyReason.LOCKED))
        assertThat(gate.checkPlay(allowed.videoId, PlayOrigin.Navigation, allowed)).isEqualTo(PlayCheck.Denied(DenyReason.LOCKED))
    }

    // ---- Profiles and the parent PIN ------------------------------------------------------------------

    @Test
    fun switching_asksForThePinWhereTheEngineSays() = runBlocking<Unit> {
        // No PIN yet: nobody could let a child out, so no child profile can be entered.
        assertThat(gate.switchProfile(child.id, pin = null)).isEqualTo(SwitchResult.PinNotSet)
        assertThat(gate.verifyParentPin(PIN)).isEqualTo(PinResult.NotSet)
        pin.setup(PIN)

        assertThat(gate.switchNeedsPin(child)).isFalse()
        assertThat(gate.switchProfile(child.id, pin = null)).isEqualTo(SwitchResult.Switched)
        withTimeout(5_000) { gate.profile.first { it?.id == child.id } }

        // Out of the child profile only with the PIN, whether to the adult or to another child.
        assertThat(gate.switchNeedsPin(adult)).isTrue()
        assertThat(gate.switchNeedsPin(kid(id = "kid2"))).isTrue()
        assertThat(gate.switchProfile(adult.id, pin = null)).isEqualTo(SwitchResult.PinRequired)
        assertThat(gate.switchProfile(adult.id, pin = "0000")).isEqualTo(SwitchResult.WrongPin(4))
        assertThat(gate.switchProfile("nobody", pin = PIN)).isEqualTo(SwitchResult.NotFound)
        assertThat(active.id.value).isEqualTo(child.id)

        assertThat(gate.switchProfile(adult.id, pin = PIN)).isEqualTo(SwitchResult.Switched)
        assertThat(active.id.value).isEqualTo(ADULT_PROFILE_ID)
    }

    @Test
    fun theWrongPinFromTheTvAndThePhone_locksBothOut() = runBlocking<Unit> {
        enterChild()
        repeat(4) { gate.switchProfile(adult.id, pin = "0000") }
        assertThat(gate.verifyParentPin("0000")).isEqualTo(PinResult.LockedOut(PinAttemptLimiter.LOCKOUT_MILLIS))
        assertThat(gate.switchProfile(adult.id, pin = PIN)).isEqualTo(SwitchResult.LockedOut(PinAttemptLimiter.LOCKOUT_MILLIS))
        assertThat(active.id.value).isEqualTo(child.id)
    }

    @Test
    fun switching_andThePlaybackLock() = runBlocking<Unit> {
        pin.setup(PIN)
        // The adult's lock: only its exit challenge ends it, not a switch.
        locks.set(engine.lockPlayback(adult, "adultvideo1"))
        assertThat(gate.switchProfile(child.id, pin = null)).isEqualTo(SwitchResult.PlaybackLocked)
        locks.set(null)

        enterChild()
        // A child's lock ends with the parent PIN, so the switch that needs it ends the lock too.
        locks.set(engine.lockPlayback(child, "kidvideo001"))
        assertThat(gate.switchProfile(adult.id, pin = null)).isEqualTo(SwitchResult.PinRequired)
        assertThat(locks.current()).isNotNull()
        assertThat(gate.switchProfile(adult.id, pin = PIN)).isEqualTo(SwitchResult.Switched)
        assertThat(locks.current()).isNull()
    }

    @Test
    fun theAdultsHistory_isNotVisibleInTheChildProfile() = runBlocking<Unit> {
        val watch = WatchRepository(db, active)
        watch.addToHistory(clip("adulthist01", channelId = OTHER_CHANNEL))
        enterChild()
        assertThat(watch.observeHistory().first()).isEmpty()
        watch.addToHistory(clip("kidhist0001"))
        assertThat(watch.observeHistory().first().map { it.videoId }).containsExactly("kidhist0001")

        assertThat(gate.switchProfile(adult.id, pin = PIN)).isEqualTo(SwitchResult.Switched)
        assertThat(watch.observeHistory().first().map { it.videoId }).containsExactly("adulthist01")
    }

    private companion object {
        const val PIN = "2468"
    }
}
