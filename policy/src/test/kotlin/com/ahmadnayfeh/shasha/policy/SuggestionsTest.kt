package com.ahmadnayfeh.shasha.policy

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** «الفيديوهات المقترحة»: who sees suggestions, and which ones (adult and child, shorts shown or hidden). */
class SuggestionsTest {

    private val engine = engineAt("2026-09-28T12:00")

    private val regular = video(channelId = OTHER_CHANNEL, durationSeconds = 600)
    private val fromAllowed = video(channelId = ALLOWED_CHANNEL, durationSeconds = 600)
    private val short = video(channelId = OTHER_CHANNEL, url = "https://www.youtube.com/shorts/s1", durationSeconds = null)
    private val noDuration = video(channelId = OTHER_CHANNEL, durationSeconds = null)

    @Test
    fun defaults_onForTheAdult_offForAChild() {
        assertThat(adult().showSuggestions).isTrue()
        assertThat(child().showSuggestions).isFalse()
        assertThat(engine.canSuggest(adult(), regular)).isTrue()
        // Off for a child: not even its allowed channels.
        assertThat(engine.canSuggest(child(), fromAllowed)).isFalse()
    }

    @Test
    fun turnedOff_nothingIsSuggested() {
        val off = adult().copy(showSuggestions = false)
        assertThat(engine.canSuggest(off, regular)).isFalse()
        assertThat(engine.canSuggest(off, fromAllowed)).isFalse()
        // The setting only concerns suggestions: the same videos still list elsewhere.
        assertThat(engine.canShow(off, regular)).isEqualTo(Decision.Allow)
    }

    @Test
    fun childWithSuggestions_onlyItsAllowedChannels() {
        val kid = child().copy(showSuggestions = true)
        assertThat(engine.canSuggest(kid, fromAllowed)).isTrue()
        assertThat(engine.canSuggest(kid, regular)).isFalse()
        assertThat(engine.canSuggest(kid, video(channelId = null))).isFalse()
        // An allowlisted playlist admits any channel on its own page, never in suggestions.
        assertThat(engine.canSuggest(kid, regular.copy(viaPlaylistId = ALLOWED_PLAYLIST))).isFalse()
        assertThat(engine.canShow(kid, regular.copy(viaPlaylistId = ALLOWED_PLAYLIST))).isEqualTo(Decision.Allow)
    }

    @Test
    fun blockLists_applyToSuggestions() {
        val household = adult().withBlockedChannel().withBlockedKeywords("رعب")
        assertThat(engine.canSuggest(household, video(channelId = BLOCKED_CHANNEL))).isFalse()
        assertThat(engine.canSuggest(household, video(title = "فيلم رعب قصير"))).isFalse()
        assertThat(engine.canSuggest(household, regular)).isTrue()
        val kid = child().copy(showSuggestions = true).withBlockedKeywords("رعب")
        assertThat(engine.canSuggest(kid, video(channelId = ALLOWED_CHANNEL, title = "رعب"))).isFalse()
    }

    @Test
    fun shortsSetting_appliesToSuggestions() {
        // Shown: shorts are suggested like anything else, a video of unknown length too.
        assertThat(engine.canSuggest(adult(), short)).isTrue()
        assertThat(engine.canSuggest(adult(), video(durationSeconds = 45))).isTrue()
        assertThat(engine.canSuggest(adult(), noDuration)).isTrue()
        // Hidden: no short by flag, link or length, and nothing whose length is unknown (it may be one).
        val noShorts = adult().copy(hideShorts = true)
        assertThat(engine.canSuggest(noShorts, short)).isFalse()
        assertThat(engine.canSuggest(noShorts, video(durationSeconds = 45))).isFalse()
        assertThat(engine.canSuggest(noShorts, video(isShortFormFlag = true))).isFalse()
        assertThat(engine.canSuggest(noShorts, noDuration)).isFalse()
        assertThat(engine.canSuggest(noShorts, regular)).isTrue()
        // A live stream is never a short by its length.
        assertThat(engine.canSuggest(noShorts, video(isLive = true, durationSeconds = null))).isTrue()
        // The child's default hides shorts.
        val kid = child().copy(showSuggestions = true)
        assertThat(engine.canSuggest(kid, video(channelId = ALLOWED_CHANNEL, durationSeconds = 50))).isFalse()
        assertThat(engine.canSuggest(kid.copy(hideShorts = false), video(channelId = ALLOWED_CHANNEL, durationSeconds = 50))).isTrue()
    }

    @Test
    fun shortsLimit_isTheParents() {
        val strict = PolicyEngine(clockAt("2026-09-28T12:00")) { PolicyConfig(shortsMaxSeconds = 60) }
        val noShorts = adult().copy(hideShorts = true)
        assertThat(strict.canSuggest(noShorts, video(durationSeconds = 90))).isTrue()
        assertThat(engine.canSuggest(noShorts, video(durationSeconds = 90))).isFalse()
    }
}
