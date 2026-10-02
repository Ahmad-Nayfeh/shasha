package com.ahmadnayfeh.shasha.policy

import com.ahmadnayfeh.shasha.youtube.VideoItem
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The list rules behind «الفيديوهات المقترحة» and Shorts, as the screens use them ([ContentFilter]): who sees which
 * suggestions (adult and child, Shorts shown or hidden), and that the «Shorts» badge marks exactly what hiding Shorts
 * removes.
 */
class SuggestionsAndShortsFilterTest {
    private val engine = PolicyEngine(MutableClock(instantAt("2026-09-28T12:00")))

    private fun rules(profile: Profile) = ContentFilter.of(engine, profile)

    private fun List<VideoItem>.ids() = map { it.videoId }

    /** What YouTube suggests beside a video, as VideoSource.related gives it. */
    private val related = listOf(
        clip("allowed0001", channelId = ALLOWED_CHANNEL),
        clip("other000001", channelId = OTHER_CHANNEL),
        clip("blocked0001", channelId = BLOCKED_CHANNEL),
        clip("short000001", channelId = OTHER_CHANNEL, short = true, durationSeconds = null),
        clip("brief000001", channelId = ALLOWED_CHANNEL, durationSeconds = 50),
        clip("unknown0001", channelId = OTHER_CHANNEL, durationSeconds = null),
        clip("keyword0001", channelId = OTHER_CHANNEL, title = "مقطع رعب"),
        clip("allowed0001", channelId = ALLOWED_CHANNEL),
        clip("nochannel01", channelId = null),
    )

    private val household = Blocklist().withChannel(ListedChannel(BLOCKED_CHANNEL, "محظورة")).withKeyword("رعب")

    @Test
    fun adult_seesSuggestions_withoutTheHouseholdsBlocks_once_each() {
        val adult = rules(owner().copy(blocklist = household))
        assertThat(adult.showsSuggestions).isTrue()
        assertThat(adult.suggestions(related).ids())
            .containsExactly("allowed0001", "other000001", "short000001", "brief000001", "unknown0001", "nochannel01").inOrder()
    }

    @Test
    fun adultWithoutShorts_noShortOfAnyKind_norAnythingOfUnknownLength() {
        val adult = rules(owner().copy(blocklist = household, hideShorts = true))
        assertThat(adult.suggestions(related).ids()).containsExactly("allowed0001", "other000001", "nochannel01").inOrder()
    }

    @Test
    fun suggestionsOff_noneAnywhere_theListsStayAsTheyWere() {
        val off = rules(owner().copy(showSuggestions = false))
        assertThat(off.showsSuggestions).isFalse()
        assertThat(off.features?.suggestions).isFalse()
        assertThat(off.suggestions(related)).isEmpty()
        assertThat(off.videos(related)).isNotEmpty()
    }

    @Test
    fun child_byDefault_hasNoSuggestions() {
        val child = rules(kid())
        assertThat(child.showsSuggestions).isFalse()
        assertThat(child.suggestions(related)).isEmpty()
    }

    @Test
    fun childWithSuggestions_onlyFromItsAllowedChannels() {
        val child = rules(kid().copy(showSuggestions = true))
        // The default child hides shorts: the 50-second video is one.
        assertThat(child.suggestions(related).ids()).containsExactly("allowed0001")
        val withShorts = rules(kid().copy(showSuggestions = true, hideShorts = false))
        assertThat(withShorts.suggestions(related).ids()).containsExactly("allowed0001", "brief000001").inOrder()
    }

    @Test
    fun theVideoBesideThemIsNotSuggested() {
        val adult = rules(owner())
        assertThat(adult.suggestions(related, exclude = setOf("allowed0001", "other000001")).ids()).doesNotContain("allowed0001")
        assertThat(adult.suggestions(related, exclude = setOf("allowed0001", "other000001")).ids()).doesNotContain("other000001")
    }

    @Test
    fun beforeAProfileIsLoaded_nothing() {
        val nothing = ContentFilter.NOTHING
        assertThat(nothing.showsSuggestions).isFalse()
        assertThat(nothing.showsShorts).isFalse()
        assertThat(nothing.suggestions(related)).isEmpty()
        assertThat(nothing.isShort(clip("short000001", short = true))).isFalse()
    }

    @Test
    fun shortsShownOrHidden_followsTheProfile() {
        assertThat(rules(owner()).showsShorts).isTrue()
        assertThat(rules(owner().copy(hideShorts = true)).showsShorts).isFalse()
        assertThat(rules(kid()).showsShorts).isFalse()
        assertThat(rules(kid().copy(hideShorts = false)).showsShorts).isTrue()
    }

    /** Every tile badged «Shorts» where they show is gone where they are hidden, and nothing else is. */
    @Test
    fun theShortsBadge_marksExactlyWhatHidingShortsRemoves() {
        val feed = listOf(
            clip("flagged0001", short = true, durationSeconds = 600),
            clip("link0000001", url = "https://www.youtube.com/shorts/link0000001", durationSeconds = null),
            clip("brief000001", durationSeconds = 45),
            clip("limit000001", durationSeconds = 180),
            clip("long0000001", durationSeconds = 181),
            clip("rss00000001", durationSeconds = null),
            clip("live0000001", live = true, durationSeconds = 30),
        )
        for (limit in listOf(60L, 180L, 300L)) {
            val parent = PolicyEngine(MutableClock(instantAt("2026-09-28T12:00"))) { PolicyConfig(shortsMaxSeconds = limit) }
            val shown = ContentFilter.of(parent, owner())
            val hidden = ContentFilter.of(parent, owner().copy(hideShorts = true))
            val badged = feed.filter { it.isShort(limit) }
            assertThat(shown.videos(feed)).isEqualTo(feed)
            assertThat(feed - hidden.videos(feed).toSet()).isEqualTo(badged)
            assertThat(feed.filter(shown::isShort)).isEqualTo(badged)
        }
        assertThat(feed.filter { it.isShort(PolicyEngine.SHORTS_MAX_SECONDS) }.ids())
            .containsExactly("flagged0001", "link0000001", "brief000001", "limit000001").inOrder()
    }
}
