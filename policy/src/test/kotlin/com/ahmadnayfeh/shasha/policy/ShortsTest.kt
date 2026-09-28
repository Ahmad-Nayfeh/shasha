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
}
