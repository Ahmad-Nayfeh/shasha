package com.ahmadnayfeh.shasha.policy

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ShortsTest {

    private val engine = engineAt("2026-09-28T12:00")

    @Test
    fun duration_upTo180SecondsInclusive_isShort() {
        assertThat(engine.isShort(video(durationSeconds = 1))).isTrue()
        assertThat(engine.isShort(video(durationSeconds = 179))).isTrue()
        assertThat(engine.isShort(video(durationSeconds = 180))).isTrue()
        assertThat(engine.isShort(video(durationSeconds = 181))).isFalse()
        assertThat(PolicyEngine.SHORTS_MAX_SECONDS).isEqualTo(180)
    }

    @Test
    fun unknownDuration_isNotShort() {
        assertThat(engine.isShort(video(durationSeconds = null))).isFalse()
        assertThat(engine.isShort(video(durationSeconds = 0))).isFalse()
        assertThat(engine.isShort(video(durationSeconds = -1))).isFalse()
    }

    @Test
    fun shortsUrl_isShort_whateverTheDuration() {
        assertThat(engine.isShort(video(url = "https://www.youtube.com/shorts/abc", durationSeconds = 3600))).isTrue()
        assertThat(engine.isShort(video(url = "https://m.youtube.com/SHORTS/abc", durationSeconds = null))).isTrue()
    }

    @Test
    fun urlMentioningShortsWithoutPath_isNotShort() {
        assertThat(engine.isShort(video(url = "https://www.youtube.com/watch?v=shorts1", durationSeconds = 600))).isFalse()
        assertThat(engine.isShort(video(url = "https://www.youtube.com/@shorts", durationSeconds = 600))).isFalse()
    }

    @Test
    fun shortFormFlag_isShort() {
        assertThat(engine.isShort(video(isShortFormFlag = true, durationSeconds = 900))).isTrue()
    }

    @Test
    fun live_isNeverShortByDuration() {
        assertThat(engine.isShort(video(isLive = true, durationSeconds = 60))).isFalse()
        assertThat(engine.isShort(video(isLive = true, durationSeconds = null))).isFalse()
        // …but the explicit signals still count.
        assertThat(engine.isShort(video(isLive = true, url = "https://www.youtube.com/shorts/x"))).isTrue()
        assertThat(engine.isShort(video(isLive = true, isShortFormFlag = true))).isTrue()
    }

    @Test
    fun hideShorts_decidesVisibility() {
        val live = video(isLive = true, durationSeconds = 60)
        assertThat(engine.canShow(child(), live)).isEqualTo(Decision.Allow)
        assertThat(engine.canShow(child(), video(isShortFormFlag = true))).isEqualTo(deny(DenyReason.SHORTS_HIDDEN))
        assertThat(engine.canShow(adult(), video(isShortFormFlag = true))).isEqualTo(Decision.Allow)
    }

    /**
     * One rule for the badge and for hiding: what a profile that shows shorts badges «Shorts» (the static rule with the
     * parent's limit) is exactly what a profile that hides them never sees, at any limit.
     */
    @Test
    fun badgedWhereShown_isExactlyWhatIsHiddenWhereHidden() {
        val mix = listOf(
            video(videoId = "flag", isShortFormFlag = true, durationSeconds = 900),
            video(videoId = "link", url = "https://www.youtube.com/shorts/link", durationSeconds = null),
            video(videoId = "s45", durationSeconds = 45),
            video(videoId = "s90", durationSeconds = 90),
            video(videoId = "s180", durationSeconds = 180),
            video(videoId = "s181", durationSeconds = 181),
            video(videoId = "long", durationSeconds = 3600),
            video(videoId = "rss", durationSeconds = null),
            video(videoId = "live", isLive = true, durationSeconds = 60),
            video(videoId = "liveShort", isLive = true, isShortFormFlag = true),
        )
        for (limit in listOf(60L, 180L, 600L)) {
            val rules = PolicyEngine(clockAt("2026-09-28T12:00")) { PolicyConfig(shortsMaxSeconds = limit) }
            val badged = mix.filter { PolicyEngine.isShort(it, limit) }.map { it.videoId }
            val hidden = mix.filter { rules.canShow(adult().copy(hideShorts = true), it) == deny(DenyReason.SHORTS_HIDDEN) }.map { it.videoId }
            assertThat(hidden).isEqualTo(badged)
            assertThat(mix.filter(rules::isShort).map { it.videoId }).isEqualTo(badged)
            // Where shorts show, every one of them is listed.
            assertThat(mix.filter { rules.canShow(adult(), it).isAllowed }).isEqualTo(mix)
        }
        assertThat(mix.filter { PolicyEngine.isShort(it, 180) }.map { it.videoId })
            .containsExactly("flag", "link", "s45", "s90", "s180", "liveShort").inOrder()
    }

    @Test
    fun needsDuration_onlyWhenTheShortsRuleCannotDecideWithoutIt() {
        val rss = video(durationSeconds = null) // a regular upload as RSS gives it: no duration
        assertThat(engine.needsDuration(child(), rss)).isTrue()
        assertThat(engine.needsDuration(child(), video(durationSeconds = 0))).isTrue()
        // It lists (the engine cannot know), but the same video with its real 150 s is a short here.
        assertThat(engine.canShow(child(), rss)).isEqualTo(Decision.Allow)
        assertThat(engine.canShow(child(), rss.copy(durationSeconds = 150))).isEqualTo(deny(DenyReason.SHORTS_HIDDEN))

        // Known either way: a duration, a "/shorts/" link, the flag, or a live stream.
        assertThat(engine.needsDuration(child(), video(durationSeconds = 150))).isFalse()
        assertThat(engine.needsDuration(child(), video(durationSeconds = 600))).isFalse()
        assertThat(engine.needsDuration(child(), video(durationSeconds = null, url = "https://www.youtube.com/shorts/abc"))).isFalse()
        assertThat(engine.needsDuration(child(), video(durationSeconds = null, isShortFormFlag = true))).isFalse()
        assertThat(engine.needsDuration(child(), video(durationSeconds = null, isLive = true))).isFalse()
        // A profile that shows shorts never needs it.
        assertThat(engine.needsDuration(adult(), rss)).isFalse()
        assertThat(engine.needsDuration(child().copy(hideShorts = false), rss)).isFalse()
        assertThat(engine.needsDuration(adult().copy(hideShorts = true), rss)).isTrue()
    }
}
