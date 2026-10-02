package com.ahmadnayfeh.shasha.remote

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.R
import com.ahmadnayfeh.shasha.data.PinResult
import com.ahmadnayfeh.shasha.player.PlayerCommand
import com.ahmadnayfeh.shasha.policy.ALLOWED_CHANNEL
import com.ahmadnayfeh.shasha.policy.ALLOWED_PLAYLIST
import com.ahmadnayfeh.shasha.policy.BLOCKED_CHANNEL
import com.ahmadnayfeh.shasha.policy.Blocklist
import com.ahmadnayfeh.shasha.policy.DenyReason
import com.ahmadnayfeh.shasha.policy.ListedChannel
import com.ahmadnayfeh.shasha.policy.OTHER_CHANNEL
import com.ahmadnayfeh.shasha.policy.OTHER_PLAYLIST
import com.ahmadnayfeh.shasha.policy.SessionState
import com.ahmadnayfeh.shasha.policy.clip
import com.ahmadnayfeh.shasha.ui.navigation.Screen
import com.ahmadnayfeh.shasha.youtube.ChannelItem
import com.ahmadnayfeh.shasha.youtube.PlaylistItem
import com.ahmadnayfeh.shasha.youtube.SourceErrorKind
import com.ahmadnayfeh.shasha.youtube.SourceException
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The phone remote's controller over the TV's real rules (PolicyGate, SessionController, LockController,
 * the repositories); only YouTube, the player and the screens are fakes ([RemoteTestEnv]).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class TvRemoteControllerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var env: RemoteTestEnv
    private val controller get() = env.controller

    @Before
    fun setUp() {
        env = remoteEnv(tmp.root)
    }

    @After
    fun tearDown() = env.close()

    private fun text(id: Int, vararg args: Any) = env.texts.get(id, *args)

    private fun deny(reason: DenyReason) = env.texts.deny(reason)

    // ---- Links (SPEC acceptance: a channel outside the allowlist does not appear, even through a phone link) --

    @Test
    fun child_aLinkOutsideTheAllowlist_isRefused_andTheTvShowsNothing() = runBlocking<Unit> {
        env.enterChild()
        // The link names the allowlisted playlist: the video's own channel decides all the same.
        val result = env.playAccepted(watchLink(OUTSIDE_VIDEO, list = ALLOWED_PLAYLIST))
        assertThat(result.ok).isFalse()
        assertThat(result.message).isEqualTo(deny(DenyReason.NOT_IN_ALLOWLIST))
        assertThat(env.display.shown).isEmpty()
    }

    @Test
    fun child_aLinkInsideTheAllowlist_opensThePlayer_asALink_inTheChildsScreensOnly() = runBlocking<Unit> {
        env.enterChild()
        val result = env.playAccepted(watchLink(INSIDE_VIDEO))
        assertThat(result.ok).isTrue()
        assertThat(env.display.shown).containsExactly(Screen.Player(INSIDE_VIDEO, fromLink = true, phoneApproved = true))
        // Bound to the profile it was checked for: another profile's screens never open it.
        assertThat(env.display.shownFor).containsExactly("kid")
    }

    @Test
    fun aSharedLinksStartTime_isWhereTheVideoStarts() = runBlocking<Unit> {
        assertThat(env.playAccepted("https://youtu.be/$ADULT_VIDEO?t=754").ok).isTrue()
        assertThat(env.playAccepted(watchLink(ADULT_VIDEO)).ok).isTrue()
        assertThat(env.display.shown).containsExactly(
            Screen.Player(ADULT_VIDEO, fromLink = true, startSeconds = 754, phoneApproved = true),
            Screen.Player(ADULT_VIDEO, fromLink = true, phoneApproved = true),
        ).inOrder()
        assertThat(env.display.shownFor).containsExactly("adult", "adult")
    }

    @Test
    fun theTvShowingAnotherProfile_meanwhile_isSaid() = runBlocking<Unit> {
        env.display.result = ShowResult.PROFILE_CHANGED
        val result = env.playAccepted(watchLink(ADULT_VIDEO))
        assertThat(result.ok).isFalse()
        assertThat(result.message).isEqualTo(text(R.string.remote_profile_changed))
    }

    @Test
    fun aVideoLinkWithAList_keepsThePlaylist_forWhatFollows_butStaysALink() = runBlocking<Unit> {
        val result = env.playAccepted(watchLink(ADULT_VIDEO, list = OTHER_PLAYLIST))
        assertThat(result.ok).isTrue()
        assertThat(env.display.shown).containsExactly(Screen.Player(ADULT_VIDEO, playlistId = OTHER_PLAYLIST, fromLink = true, phoneApproved = true))
    }

    @Test
    fun child_channelAndPlaylistLinks_followTheAllowlist() = runBlocking<Unit> {
        env.enterChild()
        env.source.addPlaylist(ALLOWED_PLAYLIST, "Nasheeds", listOf(clip("pl000000001", channelId = OTHER_CHANNEL), clip("pl000000002", channelId = OTHER_CHANNEL)))
        env.source.addPlaylist(OTHER_PLAYLIST, "Other list", listOf(clip("pl000000003", channelId = ALLOWED_CHANNEL)))

        // An allowlisted playlist plays from its first video, as its own page would (whatever the videos' channel).
        assertThat(env.playAccepted(ALLOWED_PLAYLIST_LINK).ok).isTrue()
        assertThat(env.display.shown.last()).isEqualTo(Screen.Player("pl000000001", playlistId = ALLOWED_PLAYLIST, playlistIndex = 0, phoneApproved = true))
        // Another playlist is refused, even full of allowed videos: a child opens only allowlisted playlists.
        val refused = env.playAccepted("https://www.youtube.com/playlist?list=$OTHER_PLAYLIST")
        assertThat(refused.ok).isFalse()
        assertThat(refused.message).isEqualTo(deny(DenyReason.NOT_IN_ALLOWLIST))

        assertThat(env.playAccepted("https://www.youtube.com/channel/$ALLOWED_CHANNEL").ok).isTrue()
        assertThat(env.display.shown.last()).isEqualTo(Screen.Channel(ALLOWED_CHANNEL))
        assertThat(env.playAccepted("https://www.youtube.com/channel/$OTHER_CHANNEL").ok).isFalse()
        assertThat(env.display.shown).hasSize(2)
    }

    @Test
    fun aLink_isRefused_whenTheTvCannotShowIt_orItIsNotYouTube() = runBlocking<Unit> {
        assertThat(env.playAccepted("https://example.com/watch?v=x").message).isEqualTo(text(R.string.remote_link_not_youtube))
        env.display.result = ShowResult.NO_PROFILE
        val result = env.playAccepted(watchLink(ADULT_VIDEO))
        assertThat(result.ok).isFalse()
        assertThat(result.message).isEqualTo(text(R.string.remote_no_profile))
        // A deleted video: YouTube's own reason, in Arabic.
        env.display.result = ShowResult.SHOWN
        val gone = env.playAccepted(watchLink("deleted0001"))
        assertThat(gone.ok).isFalse()
        assertThat(gone.message).isEqualTo(text(R.string.error_explain_content_unavailable))
    }

    @Test
    fun timeUp_refusesLinks_andTheTvShowsNothing() = runBlocking<Unit> {
        env.enterChild()
        env.used("kid", 60 * 60)
        val result = env.playAccepted(watchLink(INSIDE_VIDEO))
        assertThat(result.ok).isFalse()
        assertThat(result.message).isEqualTo(deny(DenyReason.TIME_UP))
        assertThat(env.playAccepted("https://www.youtube.com/channel/$ALLOWED_CHANNEL").message).isEqualTo(deny(DenyReason.TIME_UP))
        assertThat(env.display.shown).isEmpty()
    }

    // ---- Search ------------------------------------------------------------------------------------------

    @Test
    fun child_hasNoSearch() = runBlocking<Unit> {
        env.enterChild()
        assertThat(controller.status().canSearch).isFalse()
        val refused = assertThrows(RemoteRejection::class.java) { runBlocking { controller.search("cartoons") } }
        assertThat(refused.userMessage).isEqualTo(text(R.string.remote_search_disabled))
        assertThat(env.source.searches).isEmpty()
    }

    @Test
    fun adult_searchResults_passTheRules() = runBlocking<Unit> {
        env.profiles.update("adult") { it.copy(blocklist = Blocklist(channels = listOf(ListedChannel(BLOCKED_CHANNEL, "Blocked")))) }
        withTimeout(5_000) { env.gate.filter.first { it.profile?.blocklist?.channels?.isNotEmpty() == true } }
        env.source.searchResults = listOf(
            clip("fine0000001", channelId = OTHER_CHANNEL, title = "Fine"),
            clip("blocked0001", channelId = BLOCKED_CHANNEL, title = "Blocked one"),
            ChannelItem(OTHER_CHANNEL, "A channel", avatarUrl = "https://yt3.ggpht.com/a", subscriberCount = 1_200_000),
            PlaylistItem("PLsomething000001", "A list", "Owner", null, videoCount = 12),
        )
        assertThat(controller.status().canSearch).isTrue()
        val items = controller.search("anything")
        assertThat(items.map { it.id }).containsExactly("fine0000001", OTHER_CHANNEL, "PLsomething000001").inOrder()
        assertThat(items[0].type).isEqualTo(RemoteItemType.VIDEO)
        assertThat(items[0].thumbnailUrl).isEqualTo("https://i.ytimg.com/vi/fine0000001/mqdefault.jpg")
        assertThat(items[1].subtitle).isEqualTo("1.2 مليون مشترك")
        assertThat(items[2].subtitle).isEqualTo("Owner · 12 فيديو")
    }

    @Test
    fun aSearchYouTubeRefuses_isARejection_withItsReason() = runBlocking<Unit> {
        env.source.searchFailure = SourceException(SourceErrorKind.NETWORK, "UnknownHostException", null, null)
        val refused = assertThrows(RemoteRejection::class.java) { runBlocking { controller.search("x") } }
        assertThat(refused.userMessage).isEqualTo(text(R.string.remote_offline))
    }

    // ---- The playback lock (SPEC §4): STOP, new links, switches and restores are refused ------------------

    @Test
    fun aLockedVideo_refusesStopLinksSwitchesAndRestores() = runBlocking<Unit> {
        env.enterChild()
        val player = env.playOnScreen(INSIDE_VIDEO)
        env.lock.onVideoStarted("kid", INSIDE_VIDEO, null)
        assertThat(controller.status().videoLocked).isTrue()

        assertThat(controller.control(PlaybackCommand(PlaybackAction.STOP)).message).isEqualTo(text(R.string.remote_video_locked))
        assertThat(player.left).isEqualTo(0)
        assertThat(env.playAccepted(watchLink(INSIDE_VIDEO)).message).isEqualTo(deny(DenyReason.LOCKED))
        assertThat(controller.switchProfile("adult", PIN).message).isEqualTo(text(R.string.remote_switch_locked))
        assertThat(env.current().id).isEqualTo("kid")
        assertThat(controller.importBackup(controller.exportBackup()).message).isEqualTo(text(R.string.remote_backup_locked))
        assertThat(env.display.shown).isEmpty()

        // Pausing and seeking stay possible: they do not leave the video.
        assertThat(controller.control(PlaybackCommand(PlaybackAction.PAUSE)).ok).isTrue()
        assertThat(controller.control(PlaybackCommand(PlaybackAction.SEEK_BY, -10)).ok).isTrue()
        assertThat(player.commands).containsExactly(PlayerCommand.Pause, PlayerCommand.SeekBy(-10_000)).inOrder()

        // The parent's «فك قفل الفيديو» ends it: then STOP leaves the player.
        assertThat(controller.unlockVideo()).isEqualTo(RemoteResult.ok(text(R.string.remote_video_unlocked)))
        assertThat(env.lock.current()).isNull()
        assertThat(controller.control(PlaybackCommand(PlaybackAction.STOP))).isEqualTo(RemoteResult.ok(text(R.string.remote_stopped)))
        assertThat(player.left).isEqualTo(1)
    }

    @Test
    fun unlockVideo_ofTheAdult_recordsASurrender() = runBlocking<Unit> {
        env.lock.onVideoStarted("adult", ADULT_VIDEO, null)
        assertThat(controller.unlockVideo().message).isEqualTo(text(R.string.remote_video_unlocked_adult))
        assertThat(env.usage.surrenders("adult", 0)).hasSize(1)
        assertThat(controller.unlockVideo().message).isEqualTo(text(R.string.remote_video_not_locked))
    }

    // ---- The player on screen -------------------------------------------------------------------------------

    @Test
    fun control_drivesThePlayerOnScreen_andStatusShowsIt() = runBlocking<Unit> {
        assertThat(controller.control(PlaybackCommand(PlaybackAction.TOGGLE)).message).isEqualTo(text(R.string.remote_nothing_playing))
        assertThat(controller.status().nowPlaying).isNull()

        val player = env.playOnScreen(ADULT_VIDEO)
        val now = controller.status().nowPlaying!!
        assertThat(now.videoId).isEqualTo(ADULT_VIDEO)
        assertThat(now.positionSeconds).isEqualTo(42)
        assertThat(now.durationSeconds).isEqualTo(600)
        assertThat(now.isPlaying).isTrue()

        assertThat(controller.control(PlaybackCommand(PlaybackAction.TOGGLE)).ok).isTrue()
        assertThat(controller.control(PlaybackCommand(PlaybackAction.SEEK_BY, 30)).ok).isTrue()
        assertThat(player.commands).containsExactly(PlayerCommand.Toggle, PlayerCommand.SeekBy(30_000)).inOrder()
        assertThat(controller.status().nowPlaying!!.isPlaying).isFalse()

        // An ended video starts again from the TV only (its replay locks it again).
        player.ended = true
        assertThat(controller.control(PlaybackCommand(PlaybackAction.PLAY)).message).isEqualTo(text(R.string.remote_player_ended))
    }

    @Test
    fun aLiveStream_cannotBeSeeked() = runBlocking<Unit> {
        env.playOnScreen(ADULT_VIDEO, live = true)
        assertThat(controller.status().nowPlaying!!.durationSeconds).isNull()
        assertThat(controller.control(PlaybackCommand(PlaybackAction.SEEK_BY, 10)).message).isEqualTo(text(R.string.remote_player_live))
    }

    // ---- Profiles and the parent PIN (owner decision: the TV's lockout never closes the phone's route) -------

    @Test
    fun profiles_sayWhichNeedsThePin() = runBlocking<Unit> {
        val adultSide = controller.profiles().associateBy { it.id }
        assertThat(adultSide.getValue("adult").isCurrent).isTrue()
        assertThat(adultSide.getValue("adult").needsPin).isFalse()
        assertThat(adultSide.getValue("kid").needsPin).isFalse()
        assertThat(adultSide.getValue("kid").dailyQuotaMinutes).isEqualTo(60)
        env.enterChild()
        val childSide = controller.profiles().associateBy { it.id }
        assertThat(childSide.getValue("adult").needsPin).isTrue()
        assertThat(childSide.getValue("kid").isCurrent).isTrue()
    }

    @Test
    fun parentLogin_checksThePin_evenWhileTheTvIsLockedOut() = runBlocking<Unit> {
        env.lockOutTheTvPin()
        assertThat(env.parentPin.verify(PIN)).isInstanceOf(PinResult.LockedOut::class.java)
        assertThat(controller.parentLogin(PIN)).isEqualTo(ParentLoginResult.Success)
        assertThat(controller.parentLogin(WRONG_PIN)).isEqualTo(ParentLoginResult.WrongPin(4))
        // The phone's attempts leave the TV's lockout as it was.
        assertThat(env.parentPin.remainingLockMillis()).isGreaterThan(0L)
    }

    @Test
    fun thePhonesOwnCount_locksItOut_forEveryPhoneRoute_butNeverTheTv() = runBlocking<Unit> {
        env.enterChild()
        val results = (1..5).map { controller.parentLogin(WRONG_PIN) }
        assertThat(results.take(4)).containsExactly(
            ParentLoginResult.WrongPin(4), ParentLoginResult.WrongPin(3), ParentLoginResult.WrongPin(2), ParentLoginResult.WrongPin(1),
        ).inOrder()
        assertThat(results.last()).isEqualTo(ParentLoginResult.LockedOut(300))
        // Locked: even the right PIN, on the login and on a switch that needs it.
        assertThat(controller.parentLogin(PIN)).isEqualTo(ParentLoginResult.LockedOut(300))
        assertThat(controller.switchProfile("adult", PIN).message).isEqualTo(text(R.string.remote_pin_locked, "5 دقائق"))
        assertThat(env.current().id).isEqualTo("kid")
        assertThat(env.phonePin.status()).isEqualTo(PhonePinStatus(wrong = 5, lockedMillis = 300_000))
        // The TV's PIN pad keeps its own count: the parent still gets in on the TV.
        assertThat(env.parentPin.verify(PIN)).isEqualTo(PinResult.Success)

        // A restart of the app (a new PhonePin over the same store) keeps the phone's lockout.
        env.clock.advanceMillis(60_000)
        val restarted = PhonePin(env.policyStore, env.clock)
        assertThat(restarted.check(PIN)).isEqualTo(PinResult.LockedOut(240_000))
        // Once it ends, the right PIN opens and forgets the count.
        env.clock.advanceMillis(240_000)
        assertThat(restarted.check(PIN)).isEqualTo(PinResult.Success)
        assertThat(restarted.status()).isEqualTo(PhonePinStatus(wrong = 0, lockedMillis = 0))
    }

    @Test
    fun aWrongPinOnASwitch_saysHowManyTriesAreLeft() = runBlocking<Unit> {
        env.enterChild()
        assertThat(controller.switchProfile("adult", WRONG_PIN).message).isEqualTo(env.texts.wrongPin(4))
        assertThat(env.texts.wrongPin(4)).isEqualTo("رمز PIN غير صحيح. بقيت 4 محاولات.")
        assertThat(controller.switchProfile("adult", WRONG_PIN).message).isEqualTo("رمز PIN غير صحيح. بقيت 3 محاولات.")
    }

    @Test
    fun theTvHearsWhichProfileThePhoneChose() = runBlocking<Unit> {
        val heard = CopyOnWriteArrayList<String>()
        val following = env.scope.launch(start = CoroutineStart.UNDISPATCHED) { env.profileChoices.chosen.collect { heard += it } }
        try {
            assertThat(controller.switchProfile("kid", null).ok).isTrue() // a switch
            assertThat(controller.switchProfile("kid", null).ok).isTrue() // the current profile, chosen again
            assertThat(controller.switchProfile("adult", WRONG_PIN).ok).isFalse() // refused: nothing to hear
            withTimeout(5_000) { while (heard.size < 2) delay(10) }
            delay(100)
            assertThat(heard).containsExactly("kid", "kid")
        } finally {
            following.cancel()
        }
    }

    @Test
    fun switchProfile_fromAChild_withThePin_evenWhileTheTvIsLockedOut() = runBlocking<Unit> {
        env.enterChild()
        env.lockOutTheTvPin()
        assertThat(controller.switchProfile("adult", null).message).isEqualTo(text(R.string.remote_profile_needs_pin))
        assertThat(controller.switchProfile("adult", WRONG_PIN).message).isEqualTo(env.texts.wrongPin(4))
        assertThat(env.current().id).isEqualTo("kid")
        val switched = controller.switchProfile("adult", PIN)
        assertThat(switched.ok).isTrue()
        assertThat(switched.message).isEqualTo(text(R.string.remote_profile_switched_adult, "الكبار"))
        assertThat(env.current().id).isEqualTo("adult")
        // Into a child profile no PIN is needed.
        assertThat(controller.switchProfile("kid", null).ok).isTrue()
        assertThat(controller.switchProfile("kid", null).message).isEqualTo(text(R.string.remote_profile_current, "سارة"))
        assertThat(controller.switchProfile("nobody", null).message).isEqualTo(text(R.string.remote_profile_not_found))
    }

    // ---- Time (the TV's time-up screen promises the phone can open it) ------------------------------------

    @Test
    fun unlockTimeUp_opensAUsedUpQuota() = runBlocking<Unit> {
        env.enterChild()
        env.used("kid", 60 * 60)
        assertThat(env.session.current()).isEqualTo(SessionState.TimeUp)
        assertThat(controller.status().sessionState).isEqualTo(RemoteSessionState.TIME_UP)

        val result = controller.unlockTimeUp(15)
        assertThat(result).isEqualTo(RemoteResult.ok(text(R.string.remote_time_added, "سارة", env.texts.minutes(15))))
        assertThat(env.session.current().canWatch).isTrue()
        assertThat(controller.status().timeLeftMinutes).isEqualTo(15)
        assertThat(env.session.state.value.canWatch).isTrue() // what the TV's time-up screen follows

        // «بقية اليوم»: no quota until midnight.
        env.used("kid", 60 * 60)
        assertThat(controller.unlockTimeUp(null)).isEqualTo(RemoteResult.ok(text(R.string.remote_time_rest_of_today, "سارة")))
        assertThat(env.session.current().canWatch).isTrue()
    }

    @Test
    fun unlockTimeUp_opensBedtime_forSomeMinutes_orTheRestOfTheDay() = runBlocking<Unit> {
        env.enterChild()
        env.clock.set("2026-09-28T23:00") // the child's schedule ends at 22:00
        assertThat(env.session.current()).isInstanceOf(SessionState.OutsideSchedule::class.java)
        assertThat(controller.status().sessionState).isEqualTo(RemoteSessionState.OUTSIDE_SCHEDULE)

        assertThat(controller.unlockTimeUp(30).ok).isTrue()
        assertThat(env.session.current().canWatch).isTrue()
        env.clock.set("2026-09-28T23:31")
        assertThat(env.session.current().canWatch).isFalse()

        assertThat(controller.unlockTimeUp(null).ok).isTrue()
        assertThat(env.session.current().canWatch).isTrue()
        env.clock.set("2026-09-28T23:59")
        assertThat(env.session.current().canWatch).isTrue()
    }

    @Test
    fun addingMinutes_whileTimeIsLeft_addsThemOnTop() = runBlocking<Unit> {
        env.enterChild()
        env.used("kid", 40 * 60) // 20 of 60 minutes left
        assertThat(controller.status().timeLeftMinutes).isEqualTo(20)
        val result = controller.unlockTimeUp(15)
        assertThat(result).isEqualTo(RemoteResult.ok(text(R.string.remote_time_added, "سارة", env.texts.minutes(15))))
        // The message is true: 35 minutes now, not "at least 15" (which 20 already were).
        assertThat(controller.status().timeLeftMinutes).isEqualTo(35)
        // Near bedtime the bedtime moves too: 21:50, quota plenty, the schedule ends at 22:00.
        env.clock.set("2026-09-28T21:50")
        env.session.refresh()
        assertThat(controller.status().timeLeftMinutes).isEqualTo(10)
        assertThat(controller.unlockTimeUp(30).ok).isTrue()
        assertThat(controller.status().timeLeftMinutes).isEqualTo(40)
    }

    @Test
    fun unlockTimeUp_forAProfileWithoutLimits_saysSo() = runBlocking<Unit> {
        assertThat(controller.unlockTimeUp(15)).isEqualTo(RemoteResult.ok(text(R.string.remote_time_no_limit, "الكبار")))
    }

    @Test
    fun setQuota_editsTheProfile() = runBlocking<Unit> {
        assertThat(controller.setQuota("kid", 90)).isEqualTo(RemoteResult.ok(text(R.string.remote_quota_saved, "سارة", "90 دقيقة")))
        assertThat(env.profiles.get("kid")!!.dailyQuotaMinutes).isEqualTo(90)
        assertThat(controller.setQuota("kid", null)).isEqualTo(RemoteResult.ok(text(R.string.remote_quota_unlimited, "سارة")))
        assertThat(env.profiles.get("kid")!!.dailyQuotaMinutes).isNull()
        assertThat(controller.setQuota("nobody", 30).ok).isFalse()
    }

    // ---- The child's allowlist -------------------------------------------------------------------------------

    @Test
    fun allowlist_addsChannelsAndPlaylists_byLink_andRemovesThem() = runBlocking<Unit> {
        env.source.handles["@kidsscience"] = "UCkidsscience00000000000"
        env.source.channels["UCkidsscience00000000000"] = ChannelItem("UCkidsscience00000000000", "Kids Science", null, null)
        env.source.addPlaylist("PLletters0000000001", "Letters", emptyList())

        val added = controller.addToAllowlist("kid", "https://www.youtube.com/@kidsscience")
        assertThat(added).isEqualTo(RemoteResult.ok(text(R.string.remote_allowlist_added, "Kids Science", "سارة")))
        assertThat(controller.addToAllowlist("kid", "https://youtube.com/playlist?list=PLletters0000000001").ok).isTrue()
        assertThat(controller.addToAllowlist("kid", "https://www.youtube.com/@kidsscience").message)
            .isEqualTo(text(R.string.remote_allowlist_exists, "Kids Science", "سارة"))
        assertThat(controller.addToAllowlist("kid", watchLink(INSIDE_VIDEO)).message).isEqualTo(text(R.string.remote_allowlist_bad_link))
        assertThat(controller.addToAllowlist("adult", "https://www.youtube.com/@kidsscience").message).isEqualTo(text(R.string.remote_allowlist_child_only))

        val lists = controller.allowlist("kid")
        assertThat(lists.channels.map { it.id }).containsExactly(ALLOWED_CHANNEL, "UCkidsscience00000000000").inOrder()
        assertThat(lists.playlists.map { it.title }).containsExactly("قائمة مسموحة", "Letters").inOrder()

        assertThat(controller.removeFromAllowlist("kid", "UCkidsscience00000000000").ok).isTrue()
        assertThat(env.profiles.get("kid")!!.allowlist.hasChannel("UCkidsscience00000000000")).isFalse()
        assertThat(controller.removeFromAllowlist("kid", "UCkidsscience00000000000").message).isEqualTo(text(R.string.remote_allowlist_not_there))
        assertThrows(RemoteRejection::class.java) { runBlocking { controller.allowlist("adult") } }
    }

    // ---- Subscriptions and Takeout ----------------------------------------------------------------------------

    @Test
    fun subscriptions_addListRemove_andTakeout() = runBlocking<Unit> {
        env.source.handles["@docs"] = "UCdocs000000000000000000"
        env.source.channels["UCdocs000000000000000000"] = ChannelItem("UCdocs000000000000000000", "Docs", "https://yt3.ggpht.com/d", null)

        assertThat(controller.addSubscription("https://www.youtube.com/@docs")).isEqualTo(RemoteResult.ok(text(R.string.remote_subs_added, "Docs")))
        assertThat(controller.addSubscription("https://www.youtube.com/@docs").message).isEqualTo(text(R.string.remote_subs_exists, "Docs"))
        assertThat(controller.addSubscription(watchLink(INSIDE_VIDEO)).message).isEqualTo(text(R.string.remote_subs_not_channel))

        val csv = "Channel Id,Channel Url,Channel Title\n" +
            "UCdocs000000000000000000,http://www.youtube.com/channel/UCdocs000000000000000000,Docs\n" +
            "UCnewchannel000000000000,http://www.youtube.com/channel/UCnewchannel000000000000,New one\n"
        val imported = controller.importTakeout("subscriptions.csv", csv.toByteArray())
        assertThat(imported).isEqualTo(RemoteResult.ok(text(R.string.remote_takeout_added_some_present, "قناة واحدة", "قناة واحدة")))
        assertThat(env.syncs).isEqualTo(2) // each new channel's videos are fetched right away
        assertThat(controller.importTakeout("subscriptions.csv", "nothing here".toByteArray()).message).isEqualTo(text(R.string.import_error_no_channels))

        assertThat(controller.subscriptions().map { it.channelId }).containsExactly("UCdocs000000000000000000", "UCnewchannel000000000000")
        assertThat(controller.removeSubscription("UCnewchannel000000000000")).isEqualTo(RemoteResult.ok(text(R.string.remote_subs_removed, "New one")))
        assertThat(controller.removeSubscription("UCnewchannel000000000000").message).isEqualTo(text(R.string.remote_subs_not_subscribed))
    }

    // ---- Backup -----------------------------------------------------------------------------------------------

    @Test
    fun backup_roundTripsThroughThePhone_andRefusesAnythingElse() = runBlocking<Unit> {
        val bytes = controller.exportBackup()
        env.profiles.update("kid") { it.copy(name = "تغيّر") }
        val restored = controller.importBackup(bytes)
        assertThat(restored.ok).isTrue()
        assertThat(restored.message).isEqualTo(text(R.string.backup_restored, "ملفان", env.texts.channels(0)))
        assertThat(env.profiles.get("kid")!!.name).isEqualTo("سارة")
        assertThat(controller.importBackup("{\"hello\":1}".toByteArray()).message).isEqualTo(text(R.string.backup_error_not_backup))
    }

    @Test
    fun status_showsTheProfileAndItsTime() = runBlocking<Unit> {
        env.enterChild()
        env.used("kid", 50 * 60 + 30)
        val status = controller.status()
        assertThat(status.profileName).isEqualTo("سارة")
        assertThat(status.profileKind).isEqualTo(RemoteProfileKind.CHILD)
        assertThat(status.sessionState).isEqualTo(RemoteSessionState.OK)
        assertThat(status.timeLeftMinutes).isEqualTo(10) // 9.5 minutes left: rounded up
        assertThat(status.videoLocked).isFalse()
    }
}
