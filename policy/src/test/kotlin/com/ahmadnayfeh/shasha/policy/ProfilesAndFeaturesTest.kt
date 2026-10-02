package com.ahmadnayfeh.shasha.policy

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class ProfilesAndFeaturesTest {

    private val engine = engineAt("2026-09-28T12:00")

    @Test
    fun newAdult_hasNoLimitsAndShowsEverything() {
        val p = Profile.newAdult("أحمد", id = "a1")
        assertThat(p.id).isEqualTo("a1")
        assertThat(p.name).isEqualTo("أحمد")
        assertThat(p.kind).isEqualTo(ProfileKind.ADULT)
        assertThat(p.avatar).isEqualTo(Profile.DEFAULT_ADULT_AVATAR)
        assertThat(p.dailyQuotaMinutes).isNull()
        assertThat(p.schedule).isNull()
        assertThat(p.hideShorts).isFalse()
        assertThat(p.hideComments).isFalse()
        assertThat(p.showSuggestions).isTrue()
        assertThat(p.allowlist).isEqualTo(Allowlist())
        assertThat(p.blocklist).isEqualTo(Blocklist())
        assertThat(p.isAdult).isTrue()
        assertThat(p.isChild).isFalse()
    }

    @Test
    fun newChild_hasHourQuotaDaySchedule_andHidesShortsAndComments() {
        val p = Profile.newChild("سارة", id = "c1", avatar = "rocket")
        assertThat(p.kind).isEqualTo(ProfileKind.CHILD)
        assertThat(p.avatar).isEqualTo("rocket")
        assertThat(p.dailyQuotaMinutes).isEqualTo(60)
        assertThat(p.schedule).isEqualTo(Schedule(7 * 60, 22 * 60))
        assertThat(p.hideShorts).isTrue()
        assertThat(p.hideComments).isTrue()
        assertThat(p.showSuggestions).isFalse()
        assertThat(p.allowlist.channels).isEmpty()
        assertThat(p.allowlist.playlists).isEmpty()
        assertThat(p.isChild).isTrue()
        assertThat(p.isAdult).isFalse()
    }

    @Test
    fun newChild_defaultAvatarAndRandomIds_allowSeveralChildren() {
        val a = Profile.newChild("سارة")
        val b = Profile.newChild("عمر")
        assertThat(a.avatar).isEqualTo(Profile.DEFAULT_CHILD_AVATAR)
        assertThat(a.id).isNotEmpty()
        assertThat(a.id).isNotEqualTo(b.id)
        assertThat(Profile.newAdult("x").id).isNotEqualTo(Profile.newAdult("x").id)
    }

    @Test
    fun quota_mustBeNullOrWithinOneDay() {
        val p = adult()
        assertThat(p.copy(dailyQuotaMinutes = 0).dailyQuotaMinutes).isEqualTo(0)
        assertThat(p.copy(dailyQuotaMinutes = 1440).dailyQuotaMinutes).isEqualTo(1440)
        assertThat(p.copy(dailyQuotaMinutes = null).dailyQuotaMinutes).isNull()
        assertThrows(IllegalArgumentException::class.java) { p.copy(dailyQuotaMinutes = -1) }
        assertThrows(IllegalArgumentException::class.java) { p.copy(dailyQuotaMinutes = 1441) }
    }

    @Test
    fun features_adult() {
        assertThat(engine.features(adult())).isEqualTo(
            Features(
                search = true,
                textInput = true,
                comments = true,
                undoSponsorSkip = true,
                enterNeedsPin = true,
                exitNeedsPin = false,
                adultExitChallenge = true,
                channelBrowsing = true,
                suggestions = true,
            ),
        )
    }

    @Test
    fun features_suggestions_followTheProfileSetting() {
        assertThat(engine.features(adult().copy(showSuggestions = false)).suggestions).isFalse()
        assertThat(engine.features(child()).suggestions).isFalse()
        assertThat(engine.features(child().copy(showSuggestions = true)).suggestions).isTrue()
    }

    @Test
    fun features_adultEntryPinOff_onlyTheEntryChanges() {
        val relaxed = PolicyEngine(clockAt("2026-09-28T12:00")) { PolicyConfig(adultEntryNeedsPin = false) }
        val adultFeatures = relaxed.features(adult())
        assertThat(adultFeatures.enterNeedsPin).isFalse()
        assertThat(adultFeatures).isEqualTo(engine.features(adult()).copy(enterNeedsPin = false))
        // Leaving a child profile still needs the PIN.
        assertThat(relaxed.features(child())).isEqualTo(engine.features(child()))
        assertThat(relaxed.features(child()).exitNeedsPin).isTrue()
    }

    @Test
    fun features_adultWithHiddenComments() {
        val f = engine.features(adult().copy(hideComments = true))
        assertThat(f.comments).isFalse()
        assertThat(f.search).isTrue()
    }

    @Test
    fun features_child() {
        assertThat(engine.features(child())).isEqualTo(
            Features(
                search = false,
                textInput = false,
                comments = false,
                undoSponsorSkip = false,
                enterNeedsPin = false,
                exitNeedsPin = true,
                adultExitChallenge = false,
                channelBrowsing = true,
                suggestions = false,
            ),
        )
    }

    @Test
    fun features_childNeverGetsComments_evenWhenFlagIsOff() {
        assertThat(engine.features(child().copy(hideComments = false)).comments).isFalse()
    }

    @Test
    fun allowlist_withChannel_addsOnceAndRenames() {
        val list = Allowlist()
            .withChannel(ListedChannel("UC1", "قديم"))
            .withChannel(ListedChannel("UC2", "ثانية"))
            .withChannel(ListedChannel("UC1", "جديد"))
        assertThat(list.channels).containsExactly(ListedChannel("UC2", "ثانية"), ListedChannel("UC1", "جديد")).inOrder()
        assertThat(list.hasChannel("UC1")).isTrue()
        assertThat(list.hasChannel("UC3")).isFalse()
        assertThat(list.hasChannel(null)).isFalse()
    }

    @Test
    fun allowlist_withPlaylist_andWithout() {
        val list = Allowlist()
            .withChannel(ListedChannel("UC1", "قناة"))
            .withPlaylist(ListedPlaylist("PL1", "قائمة"))
            .withPlaylist(ListedPlaylist("PL1", "قائمة معدلة"))
        assertThat(list.playlists).containsExactly(ListedPlaylist("PL1", "قائمة معدلة"))
        assertThat(list.hasPlaylist("PL1")).isTrue()
        assertThat(list.hasPlaylist(null)).isFalse()
        assertThat(list.without("PL1").playlists).isEmpty()
        assertThat(list.without("PL1").channels).hasSize(1)
        assertThat(list.without("UC1").channels).isEmpty()
        assertThat(list.without("missing")).isEqualTo(list)
    }

    @Test
    fun blocklist_channels() {
        val list = Blocklist().withChannel(ListedChannel("UC1", "أ")).withChannel(ListedChannel("UC1", "ب"))
        assertThat(list.channels).containsExactly(ListedChannel("UC1", "ب"))
        assertThat(list.hasChannel("UC1")).isTrue()
        assertThat(list.hasChannel(null)).isFalse()
        assertThat(list.withoutChannel("UC1").channels).isEmpty()
    }

    @Test
    fun blocklist_keywords_ignoreBlankAndNormalizedDuplicates() {
        val list = Blocklist()
            .withKeyword("  لعبة  ")
            .withKeyword("   ")
            .withKeyword("")
            .withKeyword("لُعبه")
            .withKeyword("Horror")
            .withKeyword("HORROR")
        assertThat(list.titleKeywords).containsExactly("لعبة", "Horror").inOrder()
        assertThat(list.withoutKeyword("لعبه").titleKeywords).containsExactly("Horror")
        assertThat(list.withoutKeyword("horror").titleKeywords).containsExactly("لعبة")
    }
}
