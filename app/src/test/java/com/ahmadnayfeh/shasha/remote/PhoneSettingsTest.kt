package com.ahmadnayfeh.shasha.remote

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.R
import com.ahmadnayfeh.shasha.data.Behavior
import com.ahmadnayfeh.shasha.data.BehaviorKeys
import com.ahmadnayfeh.shasha.data.PhraseMode
import com.ahmadnayfeh.shasha.data.PlaybackSettings
import com.ahmadnayfeh.shasha.policy.ALLOWED_CHANNEL
import com.ahmadnayfeh.shasha.policy.Schedule
import com.ahmadnayfeh.shasha.policy.SwitchResult
import com.ahmadnayfeh.shasha.sponsorblock.SponsorCategory
import com.ahmadnayfeh.shasha.youtube.ChannelItem
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * v1.1: the TV's settings, profile rules, block list and stats from the phone, through the TV's own stores and checks
 * (one source of truth), and the optional adult PIN on both the TV's rules and the phone's switch.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class PhoneSettingsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var env: RemoteTestEnv
    private val controller get() = env.controller

    @Before
    fun setUp() {
        env = remoteEnv(tmp.root)
        runBlocking { env.behavior.awaitLoaded() }
    }

    @After
    fun tearDown() = env.close()

    private fun RemoteSettingsPage.value(key: String): JsonElement = groups.flatMap { it.items }.first { it.key == key }.value

    private fun RemoteSettingsPage.item(key: String): RemoteSetting = groups.flatMap { it.items }.first { it.key == key }

    private fun text(id: Int, vararg args: Any) = env.texts.get(id, *args)

    @Test
    fun thePage_hasEveryGroup_andTheTvsDefaults() = runBlocking<Unit> {
        val page = controller.settings()
        assertThat(page.groups.map { it.id }).containsExactly("serious", "playback", "pin", "sponsorblock", "quality", "remote").inOrder()
        val keys = page.groups.flatMap { g -> g.items.map { it.key } }
        // Every behaviour key of the store is on the page (nothing the TV offers is missing on the phone).
        assertThat(keys).containsAtLeastElementsIn(BehaviorKeys.ALL.keys)
        assertThat(keys).containsAtLeast(PhoneSettings.KEY_SPONSOR, PhoneSettings.KEY_MAX_QUALITY, PhoneSettings.KEY_PORT)
        page.groups.flatMap { it.items }.forEach { assertThat(it.value).isEqualTo(it.defaultValue) }
        // The TV's own choices and labels.
        val hold = page.item(BehaviorKeys.ESCAPE_HOLD_SECONDS.name)
        assertThat(hold.choices.map { it.value.jsonPrimitive.int }).isEqualTo(BehaviorKeys.ESCAPE_HOLD_CHOICES)
        assertThat(hold.label).isEqualTo(text(R.string.serious_hold))
        assertThat(hold.visibleWhen.keys).containsExactly("serious_mode", "adult_escape")
        assertThat(page.item(BehaviorKeys.ADULT_ENTRY_PIN.name).warning).isEqualTo(text(R.string.pin_settings_adult_entry_warning))
    }

    @Test
    fun aChange_isStoredWithTheTvsSetter_andTheAnswerCarriesIt() = runBlocking<Unit> {
        val r = controller.changeSetting(BehaviorKeys.SERIOUS_MODE.name, JsonPrimitive(false))
        assertThat(r.ok).isTrue()
        assertThat(r.settings!!.value(BehaviorKeys.SERIOUS_MODE.name)).isEqualTo(JsonPrimitive(false))
        assertThat(env.behavior.current().seriousMode).isFalse()
        // The rules follow (the engine reads the store), and the status tells the page.
        withTimeout(5_000) { env.behavior.values.first { !it.seriousMode } }
        assertThat(env.engine.config().seriousMode).isFalse()
        assertThat(controller.status().seriousMode).isFalse()

        assertThat(controller.changeSetting(BehaviorKeys.WARNING_MINUTES.name, JsonPrimitive(10)).ok).isTrue()
        assertThat(controller.changeSetting(BehaviorKeys.DEFAULT_SPEED.name, JsonPrimitive(1.5f)).ok).isTrue()
        assertThat(controller.changeSetting(BehaviorKeys.EXTRA_MINUTES.name, JsonArray(listOf(10, 90).map(::JsonPrimitive))).ok).isTrue()
        assertThat(controller.changeSetting(BehaviorKeys.VOICE_LANGUAGE.name, JsonPrimitive("en-US")).ok).isTrue()
        assertThat(controller.changeSetting(PhoneSettings.KEY_MAX_QUALITY, JsonPrimitive(720)).ok).isTrue()
        assertThat(controller.changeSetting(PhoneSettings.KEY_SPONSOR, JsonArray(listOf(JsonPrimitive("sponsor")))).ok).isTrue()
        val b = env.behavior.current()
        assertThat(b.warningMinutes).isEqualTo(10)
        assertThat(b.defaultSpeed).isEqualTo(1.5f)
        assertThat(b.extraMinutes).containsExactly(10, 90).inOrder()
        assertThat(b.voiceLanguage).isEqualTo("en-US")
        assertThat(env.playbackSettings.current()).isEqualTo(720)
        assertThat(env.sponsorSettings.categories.first()).containsExactly(SponsorCategory.SPONSOR)
    }

    @Test
    fun valuesTheTvDoesNotOffer_areRefused_andNothingChanges() = runBlocking<Unit> {
        fun refused(key: String, value: JsonElement) = runBlocking {
            val r = controller.changeSetting(key, value)
            assertThat(r.ok).isFalse()
            assertThat(r.settings).isNotNull()
        }
        refused(BehaviorKeys.WARNING_MINUTES.name, JsonPrimitive(7)) // not a choice
        refused(BehaviorKeys.WARNING_MINUTES.name, JsonPrimitive("5")) // a text, not a number
        refused(BehaviorKeys.SERIOUS_MODE.name, JsonPrimitive("false"))
        refused(BehaviorKeys.DEFAULT_SPEED.name, JsonPrimitive(1.1f))
        refused(BehaviorKeys.EXTRA_MINUTES.name, JsonArray(listOf(5, 10, 15, 20, 30).map(::JsonPrimitive))) // five
        refused(BehaviorKeys.EXTRA_MINUTES.name, JsonArray(listOf(15, 7).map(::JsonPrimitive))) // 7 not offered
        refused(BehaviorKeys.EXTRA_MINUTES.name, JsonArray(emptyList()))
        refused(BehaviorKeys.VOICE_LANGUAGE.name, JsonPrimitive("fr-FR"))
        refused(PhoneSettings.KEY_MAX_QUALITY, JsonPrimitive(1000))
        refused(PhoneSettings.KEY_SPONSOR, JsonArray(listOf(JsonPrimitive("chapter"))))
        refused(PhoneSettings.KEY_PORT, JsonPrimitive(80))
        refused(BehaviorKeys.CUSTOM_PHRASE.name, JsonPrimitive("hello")) // the exit keyboard has Arabic letters only
        refused(BehaviorKeys.PHRASES.name, JsonArray(emptyList()))
        refused(BehaviorKeys.PHRASES.name, JsonArray(listOf(JsonPrimitive("abc"))))
        // Choosing «عبارتي الخاصة» before writing it: the TV opens its editor first, the phone asks for it first.
        val custom = controller.changeSetting(BehaviorKeys.PHRASE_MODE.name, JsonPrimitive(PhraseMode.CUSTOM.name))
        assertThat(custom.ok).isFalse()
        assertThat(custom.message).isEqualTo(text(R.string.phone_setting_custom_first))
        val unknown = controller.changeSetting("no_such_setting", JsonPrimitive(true))
        assertThat(unknown.message).isEqualTo(text(R.string.phone_setting_unknown))
        assertThat(env.behavior.current()).isEqualTo(Behavior.DEFAULT)
        assertThat(env.playbackSettings.current()).isEqualTo(PlaybackSettings.DEFAULT_MAX_QUALITY)
        assertThat(env.restarts).isEqualTo(0)
    }

    @Test
    fun phrases_theWholeListAtOnce_andTheCustomPhraseIsChosenWhenWritten() = runBlocking<Unit> {
        val list = listOf("انا اخرج الان", "ساعود لاحقا")
        assertThat(controller.changeSetting(BehaviorKeys.PHRASES.name, JsonArray(list.map(::JsonPrimitive))).ok).isTrue()
        assertThat(env.behavior.current().phrases).isEqualTo(list)
        val r = controller.changeSetting(BehaviorKeys.CUSTOM_PHRASE.name, JsonPrimitive("اريد الخروج"))
        assertThat(r.ok).isTrue()
        assertThat(env.behavior.current().phraseMode).isEqualTo(PhraseMode.CUSTOM)
        assertThat(env.behavior.current().exitPhrases).containsExactly("اريد الخروج")
        assertThat(r.settings!!.value(BehaviorKeys.PHRASE_MODE.name)).isEqualTo(JsonPrimitive("CUSTOM"))
    }

    @Test
    fun resets_oneGroupOrEverything_likeTheTv() = runBlocking<Unit> {
        controller.changeSetting(BehaviorKeys.SERIOUS_MODE.name, JsonPrimitive(false))
        controller.changeSetting(BehaviorKeys.WARNING_MINUTES.name, JsonPrimitive(10))
        controller.changeSetting(BehaviorKeys.ADULT_ENTRY_PIN.name, JsonPrimitive(false))
        controller.changeSetting(PhoneSettings.KEY_MAX_QUALITY, JsonPrimitive(480))

        val serious = controller.resetSettings(PhoneSettings.GROUP_SERIOUS)
        assertThat(serious.ok).isTrue()
        assertThat(env.behavior.current().seriousMode).isTrue()
        assertThat(env.behavior.current().warningMinutes).isEqualTo(10) // another group: kept
        assertThat(controller.resetSettings("nope").ok).isFalse()

        val all = controller.resetSettings(null)
        assertThat(all.message).isEqualTo(text(R.string.reset_done))
        assertThat(env.behavior.current()).isEqualTo(Behavior.DEFAULT)
        assertThat(env.playbackSettings.current()).isEqualTo(PlaybackSettings.DEFAULT_MAX_QUALITY)
        assertThat(all.reconnectPort).isNull() // the port did not move
        assertThat(env.restarts).isEqualTo(0)
    }

    @Test
    fun aNewPort_answersFirst_thenTheServerMoves() = runBlocking<Unit> {
        val r = controller.changeSetting(PhoneSettings.KEY_PORT, JsonPrimitive(9123))
        assertThat(r.ok).isTrue()
        assertThat(r.reconnectPort).isEqualTo(9123)
        assertThat(env.restarts).isEqualTo(0) // not inside the request that asked for it
        withTimeout(5_000) { while (env.restarts == 0) delay(10) }
        assertThat(env.remoteSettings.port.first()).isEqualTo(9123)
        // The same port again changes nothing.
        assertThat(controller.changeSetting(PhoneSettings.KEY_PORT, JsonPrimitive(9123)).reconnectPort).isNull()
        // A reset moves it back to the default, and says so.
        val reset = controller.resetSettings(null)
        assertThat(reset.reconnectPort).isEqualTo(RemoteSettings.DEFAULT_PORT)
        withTimeout(5_000) { while (env.restarts < 2) delay(10) }
    }

    // ---- The optional adult PIN, on the TV's rules and the phone's switch alike ---------------------------------

    @Test
    fun adultEntryPinOff_thePhoneEntersTheAdultProfileWithoutAPin_butLeavingAChildStillAsks() = runBlocking<Unit> {
        env.enterChild()
        controller.changeSetting(BehaviorKeys.ADULT_ENTRY_PIN.name, JsonPrimitive(false))
        withTimeout(5_000) { env.behavior.values.first { !it.adultEntryPin } }
        // From a child: leaving it needs the PIN, whatever the setting.
        assertThat(controller.profiles().first { it.id == "adult" }.needsPin).isTrue()
        assertThat(controller.switchProfile("adult", null).ok).isFalse()
        assertThat(controller.switchProfile("adult", PIN).ok).isTrue()
        // From the adult to another adult or back: the TV's gate says no PIN is needed to enter an adult profile.
        env.profiles.upsert(com.ahmadnayfeh.shasha.policy.Profile.newAdult("ضيف", id = "guest"))
        assertThat(controller.profiles().first { it.id == "guest" }.needsPin).isFalse()
        assertThat(env.gate.switchProfile("guest", pin = null)).isEqualTo(SwitchResult.Switched)
        assertThat(controller.switchProfile("adult", null).ok).isTrue()
        assertThat(env.current().id).isEqualTo("adult")
    }

    @Test
    fun adultEntryPinOn_theAdultProfileNeedsThePin_fromThePhone() = runBlocking<Unit> {
        env.profiles.upsert(com.ahmadnayfeh.shasha.policy.Profile.newAdult("ضيف", id = "guest"))
        assertThat(controller.profiles().first { it.id == "guest" }.needsPin).isTrue()
        assertThat(controller.switchProfile("guest", null).ok).isFalse()
        assertThat(env.current().id).isEqualTo("adult")
    }

    // ---- Profiles, the block list, the stats ----------------------------------------------------------------

    @Test
    fun profiles_flagsAndSchedule_likeTheTvsEditor() = runBlocking<Unit> {
        env.used("kid", 20 * 60)
        val kid = controller.profileDetails().first { it.id == "kid" }
        assertThat(kid.kind).isEqualTo(RemoteProfileKind.CHILD)
        assertThat(kid.dailyQuotaMinutes).isEqualTo(60)
        assertThat(kid.scheduleFrom).isEqualTo(7 * 60)
        assertThat(kid.scheduleUntil).isEqualTo(22 * 60)
        assertThat(kid.hideComments).isTrue() // a child never gets comments
        assertThat(kid.usedTodayMinutes).isEqualTo(20)

        assertThat(controller.setProfileFlags("kid", hideShorts = false, hideComments = null).ok).isTrue()
        assertThat(env.profiles.get("kid")!!.hideShorts).isFalse()
        val comments = controller.setProfileFlags("kid", hideShorts = null, hideComments = false)
        assertThat(comments.ok).isFalse()
        assertThat(comments.message).isEqualTo(text(R.string.phone_comments_child))
        assertThat(controller.setProfileFlags("adult", hideShorts = null, hideComments = true).ok).isTrue()
        assertThat(env.profiles.get("adult")!!.hideComments).isTrue()
        assertThat(controller.setProfileFlags("gone", true, null).ok).isFalse()

        // The TV picker's rule: until 0:00 is the end of the day, the same time twice is all day.
        assertThat(controller.setSchedule("kid", 8 * 60, 0).ok).isTrue()
        assertThat(env.profiles.get("kid")!!.schedule).isEqualTo(Schedule(8 * 60, 24 * 60))
        assertThat(controller.profileDetails().first { it.id == "kid" }.scheduleUntil).isEqualTo(0)
        assertThat(controller.setSchedule("kid", 9 * 60, 9 * 60).ok).isTrue()
        assertThat(env.profiles.get("kid")!!.schedule).isNull()
        assertThat(controller.setSchedule("kid", 20 * 60, 6 * 60).ok).isTrue()
        assertThat(env.profiles.get("kid")!!.schedule!!.wrapsMidnight).isTrue()
        assertThat(controller.setSchedule("kid", null, null).message).isEqualTo(text(R.string.phone_schedule_all_day, "سارة"))
    }

    @Test
    fun blocklist_isTheHouseholds_andAppliesToEveryProfile() = runBlocking<Unit> {
        assertThat(controller.blockKeyword("  رعب ").ok).isTrue()
        assertThat(controller.blockKeyword("رعب").message).isEqualTo(text(R.string.phone_keyword_exists, "رعب"))
        env.source.channels["UCprank0000000000000000A"] = ChannelItem("UCprank0000000000000000A", "Prank TV", null, null)
        env.source.handles["@prank"] = "UCprank0000000000000000A"
        assertThat(controller.blockChannel("https://www.youtube.com/@prank").ok).isTrue()
        assertThat(controller.blockChannel("https://youtu.be/abcdefghijk").ok).isFalse()
        env.profiles.all().forEach {
            assertThat(it.blocklist.titleKeywords).contains("رعب")
            assertThat(it.blocklist.channels.map { c -> c.channelId }).contains("UCprank0000000000000000A")
        }
        val list = controller.blocklist()
        assertThat(list.keywords).containsExactly("رعب")
        assertThat(list.channels.single().title).isEqualTo("Prank TV")
        assertThat(controller.unblockKeyword("رعب").ok).isTrue()
        assertThat(controller.unblockKeyword("رعب").ok).isFalse()
        assertThat(controller.unblockChannel("UCprank0000000000000000A").ok).isTrue()
        assertThat(controller.unblockChannel(ALLOWED_CHANNEL).ok).isFalse()
        assertThat(controller.blocklist().channels).isEmpty()
    }

    @Test
    fun stats_theLastSevenDaysOfEveryProfile() = runBlocking<Unit> {
        env.usage.addPlayback("kid", env.engine.today(), 30 * 60, ALLOWED_CHANNEL, "قناة مسموحة")
        env.used("kid", 40 * 60)
        val stats = controller.stats()
        assertThat(stats.map { it.profileId }).containsExactly("adult", "kid").inOrder()
        val kid = stats.first { it.profileId == "kid" }
        assertThat(kid.days).hasSize(7)
        assertThat(kid.days.last()).isEqualTo(40)
        assertThat(kid.topChannels.single().name).isEqualTo("قناة مسموحة")
    }

    @Test
    fun withoutTheV11Parts_theControllerRefusesInArabic() = runBlocking<Unit> {
        val bare = TvRemoteController(
            env.gate, env.profiles, env.session, env.lock, com.ahmadnayfeh.shasha.ui.parental.ParentalLists(env.profiles),
            env.subscriptions, env.source, env.phonePin, env.backup, env.hub, env.display, com.ahmadnayfeh.shasha.player.PlayQueues(), env.texts,
        )
        val e = runCatching { bare.settings() }.exceptionOrNull()
        assertThat(e).isInstanceOf(RemoteRejection::class.java)
        assertThat(bare.status().seriousMode).isTrue()
    }
}
