package com.ahmadnayfeh.shasha.policy

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AllowBlockTest {

    private val engine = engineAt("2026-09-28T12:00")
    private val short = video(durationSeconds = 45)

    // ---- Adult -------------------------------------------------------------------------------

    @Test
    fun adult_plainVideo_allowed() {
        assertThat(engine.canShow(adult(), video())).isEqualTo(Decision.Allow)
    }

    @Test
    fun adult_ignoresAllowlist_andUnknownChannel() {
        assertThat(engine.canShow(adult(), video(channelId = OTHER_CHANNEL))).isEqualTo(Decision.Allow)
        assertThat(engine.canShow(adult(), video(channelId = null))).isEqualTo(Decision.Allow)
    }

    @Test
    fun adult_blockedChannel() {
        val p = adult().withBlockedChannel()
        assertThat(engine.canShow(p, video(channelId = BLOCKED_CHANNEL))).isEqualTo(deny(DenyReason.BLOCKED_CHANNEL))
        assertThat(engine.canShow(p, video(channelId = OTHER_CHANNEL))).isEqualTo(Decision.Allow)
        assertThat(engine.canShow(p, video(channelId = null))).isEqualTo(Decision.Allow)
    }

    @Test
    fun adult_blockedKeyword() {
        val p = adult().withBlockedKeywords("رعب")
        assertThat(engine.canShow(p, video(title = "فيلم رعب قصير"))).isEqualTo(deny(DenyReason.BLOCKED_KEYWORD))
        assertThat(engine.canShow(p, video(title = "فيلم كرتون"))).isEqualTo(Decision.Allow)
    }

    @Test
    fun adult_blockedChannelWinsOverKeyword() {
        val p = adult().withBlockedChannel().withBlockedKeywords("رعب")
        assertThat(engine.canShow(p, video(title = "رعب", channelId = BLOCKED_CHANNEL)))
            .isEqualTo(deny(DenyReason.BLOCKED_CHANNEL))
    }

    @Test
    fun adult_keywordWinsOverShorts() {
        val p = adult().copy(hideShorts = true).withBlockedKeywords("رعب")
        assertThat(engine.canShow(p, video(title = "رعب", durationSeconds = 30))).isEqualTo(deny(DenyReason.BLOCKED_KEYWORD))
    }

    @Test
    fun adult_shortsOnlyHiddenWhenAsked() {
        assertThat(engine.canShow(adult(), short)).isEqualTo(Decision.Allow)
        assertThat(engine.canShow(adult().copy(hideShorts = true), short)).isEqualTo(deny(DenyReason.SHORTS_HIDDEN))
    }

    // ---- Child -------------------------------------------------------------------------------

    @Test
    fun child_allowlistedChannel_allowed() {
        assertThat(engine.canShow(child(), video())).isEqualTo(Decision.Allow)
    }

    @Test
    fun child_channelOutsideAllowlist_denied() {
        assertThat(engine.canShow(child(), video(channelId = OTHER_CHANNEL))).isEqualTo(deny(DenyReason.NOT_IN_ALLOWLIST))
    }

    @Test
    fun child_unknownChannel_denied() {
        assertThat(engine.canShow(child(), video(channelId = null))).isEqualTo(deny(DenyReason.NOT_IN_ALLOWLIST))
    }

    @Test
    fun child_emptyAllowlist_deniesEverything() {
        val p = Profile.newChild("عمر", id = "k2")
        assertThat(engine.canShow(p, video())).isEqualTo(deny(DenyReason.NOT_IN_ALLOWLIST))
        assertThat(engine.canShow(p, video(viaPlaylistId = ALLOWED_PLAYLIST))).isEqualTo(deny(DenyReason.NOT_IN_ALLOWLIST))
    }

    @Test
    fun child_viaAllowlistedPlaylist_allowedEvenFromOtherOrUnknownChannel() {
        assertThat(engine.canShow(child(), video(channelId = OTHER_CHANNEL, viaPlaylistId = ALLOWED_PLAYLIST)))
            .isEqualTo(Decision.Allow)
        assertThat(engine.canShow(child(), video(channelId = null, viaPlaylistId = ALLOWED_PLAYLIST)))
            .isEqualTo(Decision.Allow)
    }

    @Test
    fun child_viaOtherPlaylist_denied() {
        assertThat(engine.canShow(child(), video(channelId = OTHER_CHANNEL, viaPlaylistId = OTHER_PLAYLIST)))
            .isEqualTo(deny(DenyReason.NOT_IN_ALLOWLIST))
    }

    @Test
    fun child_allowlistNotShared_betweenChildren() {
        val other = Profile.newChild("عمر", id = "k2").copy(
            allowlist = Allowlist(channels = listOf(ListedChannel(OTHER_CHANNEL, "أخرى"))),
        )
        assertThat(engine.canShow(other, video(channelId = ALLOWED_CHANNEL))).isEqualTo(deny(DenyReason.NOT_IN_ALLOWLIST))
        assertThat(engine.canShow(other, video(channelId = OTHER_CHANNEL))).isEqualTo(Decision.Allow)
        assertThat(engine.canShow(child(), video(channelId = OTHER_CHANNEL))).isEqualTo(deny(DenyReason.NOT_IN_ALLOWLIST))
    }

    @Test
    fun child_allowlistedButBlockedChannel_blocked() {
        val p = child().withBlockedChannel(ALLOWED_CHANNEL)
        assertThat(engine.canShow(p, video())).isEqualTo(deny(DenyReason.BLOCKED_CHANNEL))
    }

    @Test
    fun child_blockedChannelInsideAllowlistedPlaylist_blocked() {
        val p = child().withBlockedChannel()
        assertThat(engine.canShow(p, video(channelId = BLOCKED_CHANNEL, viaPlaylistId = ALLOWED_PLAYLIST)))
            .isEqualTo(deny(DenyReason.BLOCKED_CHANNEL))
    }

    @Test
    fun child_notAllowlistedAndBlocked_reportsAllowlistFirst() {
        val p = child().withBlockedChannel()
        assertThat(engine.canShow(p, video(channelId = BLOCKED_CHANNEL))).isEqualTo(deny(DenyReason.NOT_IN_ALLOWLIST))
    }

    @Test
    fun child_blockedKeyword_evenInAllowlistedChannel() {
        val p = child().withBlockedKeywords("تحدي")
        assertThat(engine.canShow(p, video(title = "تحدّي الـــمـــوت"))).isEqualTo(deny(DenyReason.BLOCKED_KEYWORD))
    }

    @Test
    fun child_shortsHiddenByDefault() {
        assertThat(engine.canShow(child(), short)).isEqualTo(deny(DenyReason.SHORTS_HIDDEN))
        assertThat(engine.canShow(child(), video(url = "https://www.youtube.com/shorts/abc", durationSeconds = null)))
            .isEqualTo(deny(DenyReason.SHORTS_HIDDEN))
    }

    @Test
    fun child_shortsAllowedWhenParentTurnsHidingOff() {
        assertThat(engine.canShow(child().copy(hideShorts = false), short)).isEqualTo(Decision.Allow)
    }

    // ---- Channels and playlists --------------------------------------------------------------

    @Test
    fun canShowChannel_adult() {
        val p = adult().withBlockedChannel()
        assertThat(engine.canShowChannel(p, OTHER_CHANNEL)).isEqualTo(Decision.Allow)
        assertThat(engine.canShowChannel(p, BLOCKED_CHANNEL)).isEqualTo(deny(DenyReason.BLOCKED_CHANNEL))
    }

    @Test
    fun canShowChannel_child() {
        val p = child().withBlockedChannel(ALLOWED_CHANNEL)
        assertThat(engine.canShowChannel(child(), ALLOWED_CHANNEL)).isEqualTo(Decision.Allow)
        assertThat(engine.canShowChannel(child(), OTHER_CHANNEL)).isEqualTo(deny(DenyReason.NOT_IN_ALLOWLIST))
        assertThat(engine.canShowChannel(p, ALLOWED_CHANNEL)).isEqualTo(deny(DenyReason.BLOCKED_CHANNEL))
    }

    @Test
    fun canShowPlaylist_adult() {
        val p = adult().withBlockedChannel()
        assertThat(engine.canShowPlaylist(p, OTHER_PLAYLIST, OTHER_CHANNEL)).isEqualTo(Decision.Allow)
        assertThat(engine.canShowPlaylist(p, OTHER_PLAYLIST, null)).isEqualTo(Decision.Allow)
        assertThat(engine.canShowPlaylist(p, OTHER_PLAYLIST, BLOCKED_CHANNEL)).isEqualTo(deny(DenyReason.BLOCKED_CHANNEL))
    }

    @Test
    fun canShowPlaylist_child_allowlistedPlaylistOrOwner() {
        assertThat(engine.canShowPlaylist(child(), ALLOWED_PLAYLIST, OTHER_CHANNEL)).isEqualTo(Decision.Allow)
        assertThat(engine.canShowPlaylist(child(), ALLOWED_PLAYLIST, null)).isEqualTo(Decision.Allow)
        assertThat(engine.canShowPlaylist(child(), OTHER_PLAYLIST, ALLOWED_CHANNEL)).isEqualTo(Decision.Allow)
    }

    @Test
    fun canShowPlaylist_child_otherwiseDenied() {
        assertThat(engine.canShowPlaylist(child(), OTHER_PLAYLIST, OTHER_CHANNEL)).isEqualTo(deny(DenyReason.NOT_IN_ALLOWLIST))
        assertThat(engine.canShowPlaylist(child(), OTHER_PLAYLIST, null)).isEqualTo(deny(DenyReason.NOT_IN_ALLOWLIST))
    }

    @Test
    fun canShowPlaylist_child_allowlistedPlaylistOfBlockedOwner_blocked() {
        val p = child().withBlockedChannel()
        assertThat(engine.canShowPlaylist(p, ALLOWED_PLAYLIST, BLOCKED_CHANNEL)).isEqualTo(deny(DenyReason.BLOCKED_CHANNEL))
    }

    // ---- canPlay -----------------------------------------------------------------------------

    @Test
    fun canPlay_followsSession() {
        val p = child()
        assertThat(engine.canPlay(p, video(), SessionState.Ok(600))).isEqualTo(Decision.Allow)
        assertThat(engine.canPlay(p, video(), SessionState.Ok(null))).isEqualTo(Decision.Allow)
        assertThat(engine.canPlay(p, video(), SessionState.Warning(60))).isEqualTo(Decision.Allow)
        assertThat(engine.canPlay(p, video(), SessionState.TimeUp)).isEqualTo(deny(DenyReason.TIME_UP))
        assertThat(engine.canPlay(p, video(), SessionState.OutsideSchedule(0))).isEqualTo(deny(DenyReason.OUTSIDE_SCHEDULE))
    }

    @Test
    fun canPlay_contentRulesComeBeforeTime() {
        val p = child()
        assertThat(engine.canPlay(p, video(channelId = OTHER_CHANNEL), SessionState.TimeUp))
            .isEqualTo(deny(DenyReason.NOT_IN_ALLOWLIST))
        assertThat(engine.canPlay(p, short, SessionState.OutsideSchedule(0))).isEqualTo(deny(DenyReason.SHORTS_HIDDEN))
    }

    @Test
    fun canPlay_phoneLinkOutsideAllowlist_rejected() {
        val link = video(channelId = OTHER_CHANNEL, url = "https://youtu.be/xyz", videoId = "xyz")
        assertThat(engine.canPlay(child(), link, SessionState.Ok(3600))).isEqualTo(deny(DenyReason.NOT_IN_ALLOWLIST))
        assertThat(engine.canPlay(adult(), link, SessionState.Ok(null))).isEqualTo(Decision.Allow)
    }

    @Test
    fun canPlay_withLockState() {
        val p = child()
        assertThat(engine.canPlay(p, video(), LockState(playbackLocked = false, session = SessionState.Ok(600))))
            .isEqualTo(Decision.Allow)
        assertThat(engine.canPlay(p, video(), LockState(playbackLocked = true, session = SessionState.Ok(600))))
            .isEqualTo(deny(DenyReason.LOCKED))
        assertThat(engine.canPlay(p, video(), LockState(playbackLocked = true, session = SessionState.TimeUp)))
            .isEqualTo(deny(DenyReason.TIME_UP))
        assertThat(engine.canPlay(p, video(channelId = OTHER_CHANNEL), LockState(true, SessionState.Ok(600))))
            .isEqualTo(deny(DenyReason.NOT_IN_ALLOWLIST))
    }

    @Test
    fun decision_isAllowed() {
        assertThat(Decision.Allow.isAllowed).isTrue()
        assertThat(deny(DenyReason.LOCKED).isAllowed).isFalse()
    }

    // ---- Links from the phone ----------------------------------------------------------------

    @Test
    fun canPlayLink_ignoresTheLinksListParameter() {
        val unlocked = LockState(false, SessionState.Ok(3600))
        // watch?v=<video of another channel>&list=<allowlisted playlist>
        val link = video(channelId = OTHER_CHANNEL, viaPlaylistId = ALLOWED_PLAYLIST, url = "https://youtu.be/evil?list=$ALLOWED_PLAYLIST")
        assertThat(engine.canPlayLink(child(), link, unlocked)).isEqualTo(deny(DenyReason.NOT_IN_ALLOWLIST))
        assertThat(engine.canPlayLink(child(), link.copy(channelId = ALLOWED_CHANNEL), unlocked)).isEqualTo(Decision.Allow)
        assertThat(engine.canPlayLink(adult(), link, unlocked)).isEqualTo(Decision.Allow)
        assertThat(engine.canPlayLink(child(), link.copy(channelId = ALLOWED_CHANNEL), LockState(true, SessionState.Ok(3600))))
            .isEqualTo(deny(DenyReason.LOCKED))
    }

    // ---- Playlist titles ---------------------------------------------------------------------

    @Test
    fun canShowPlaylist_blockedKeywordInItsTitle() {
        val p = adult().withBlockedKeywords("رعب")
        assertThat(engine.canShowPlaylist(p, OTHER_PLAYLIST, OTHER_CHANNEL, title = "أفلام رُعب")).isEqualTo(deny(DenyReason.BLOCKED_KEYWORD))
        assertThat(engine.canShowPlaylist(p, OTHER_PLAYLIST, OTHER_CHANNEL, title = "أفلام كرتون")).isEqualTo(Decision.Allow)
        assertThat(engine.canShowPlaylist(p, OTHER_PLAYLIST, OTHER_CHANNEL)).isEqualTo(Decision.Allow)
        val kid = child().withBlockedKeywords("رعب")
        assertThat(engine.canShowPlaylist(kid, ALLOWED_PLAYLIST, null, title = "رعب")).isEqualTo(deny(DenyReason.BLOCKED_KEYWORD))
        assertThat(engine.canShowPlaylist(kid, OTHER_PLAYLIST, OTHER_CHANNEL, title = "رعب")).isEqualTo(deny(DenyReason.NOT_IN_ALLOWLIST))
    }

    @Test
    fun keywordMatcher_isRebuiltWhenTheBlocklistChanges() {
        val a = adult().withBlockedKeywords("رعب")
        val b = adult().withBlockedKeywords("قتال")
        repeat(2) {
            assertThat(engine.canShow(a, video(title = "رعب"))).isEqualTo(deny(DenyReason.BLOCKED_KEYWORD))
            assertThat(engine.canShow(b, video(title = "رعب"))).isEqualTo(Decision.Allow)
            assertThat(engine.canShow(b, video(title = "قتال"))).isEqualTo(deny(DenyReason.BLOCKED_KEYWORD))
            assertThat(engine.canShow(adult(), video(title = "قتال"))).isEqualTo(Decision.Allow)
        }
    }
}
