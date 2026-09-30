package com.ahmadnayfeh.shasha.policy

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.time.Duration

/**
 * Adversarial tests: attempts to get content, time or an exit past the rules of SPEC sections 3 and 4.
 * Tests that pass are kept as regression coverage.
 */
class AdversarialContentTest {

    private val engine = engineAt("2026-09-28T12:00")

    private fun cp(vararg codePoints: Int): String = buildString { codePoints.forEach { appendCodePoint(it) } }

    /** Puts [invisible] between every letter of [word]. */
    private fun sneak(word: String, invisible: String): String = word.toList().joinToString(invisible)

    private fun hex(s: String) = s.codePoints().toArray().joinToString(" ") { "U+%04X".format(it) }

    // =========================================================================================
    // Child allowlist bypass attempts
    // =========================================================================================

    @Test
    fun child_unknownOrEmptyChannelId_neverAllowed() {
        val kid = child()
        assertThat(engine.canShow(kid, video(channelId = null))).isEqualTo(deny(DenyReason.NOT_IN_ALLOWLIST))
        assertThat(engine.canShow(kid, video(channelId = ""))).isEqualTo(deny(DenyReason.NOT_IN_ALLOWLIST))
        assertThat(engine.canShowChannel(kid, "")).isEqualTo(deny(DenyReason.NOT_IN_ALLOWLIST))
        assertThat(engine.canPlay(kid, video(channelId = null), SessionState.Ok(3600))).isEqualTo(deny(DenyReason.NOT_IN_ALLOWLIST))
        assertThat(engine.canPlay(kid, video(channelId = null), LockState(false, SessionState.Ok(3600))))
            .isEqualTo(deny(DenyReason.NOT_IN_ALLOWLIST))
    }

    @Test
    fun child_nearMissChannelIds_areNotTheAllowlistedChannel() {
        val kid = child()
        for (id in listOf("uc_allowed", "UC_ALLOWED", " UC_allowed", "UC_allowed ", "UC_allowedX", "UC_allowe", "UC_allowed\u200B")) {
            assertWithMessage("channelId '%s'", id).that(engine.canShow(kid, video(channelId = id)))
                .isEqualTo(deny(DenyReason.NOT_IN_ALLOWLIST))
            assertWithMessage("channel page '%s'", id).that(engine.canShowChannel(kid, id))
                .isEqualTo(deny(DenyReason.NOT_IN_ALLOWLIST))
        }
    }

    @Test
    fun child_channelAndPlaylistIds_doNotSatisfyEachOther() {
        val kid = child()
        // An allowlisted CHANNEL id passed as the playlist the item came through.
        assertThat(engine.canShow(kid, video(channelId = OTHER_CHANNEL, viaPlaylistId = ALLOWED_CHANNEL)))
            .isEqualTo(deny(DenyReason.NOT_IN_ALLOWLIST))
        // An allowlisted PLAYLIST id passed as the channel.
        assertThat(engine.canShow(kid, video(channelId = ALLOWED_PLAYLIST))).isEqualTo(deny(DenyReason.NOT_IN_ALLOWLIST))
        assertThat(engine.canShowChannel(kid, ALLOWED_PLAYLIST)).isEqualTo(deny(DenyReason.NOT_IN_ALLOWLIST))
        assertThat(engine.canShowPlaylist(kid, ALLOWED_CHANNEL, null)).isEqualTo(deny(DenyReason.NOT_IN_ALLOWLIST))
        assertThat(engine.canShowPlaylist(kid, OTHER_PLAYLIST, ALLOWED_PLAYLIST)).isEqualTo(deny(DenyReason.NOT_IN_ALLOWLIST))
    }

    @Test
    fun child_playlistOfAllowlistedOwner_doesNotOpenOtherChannelsInsideIt() {
        val kid = child()
        assertThat(engine.canShowPlaylist(kid, OTHER_PLAYLIST, ALLOWED_CHANNEL)).isEqualTo(Decision.Allow)
        assertThat(engine.canShow(kid, video(channelId = OTHER_CHANNEL, viaPlaylistId = OTHER_PLAYLIST)))
            .isEqualTo(deny(DenyReason.NOT_IN_ALLOWLIST))
        assertThat(engine.canShow(kid, video(channelId = null, viaPlaylistId = OTHER_PLAYLIST)))
            .isEqualTo(deny(DenyReason.NOT_IN_ALLOWLIST))
        assertThat(engine.canShow(kid, video(channelId = ALLOWED_CHANNEL, viaPlaylistId = OTHER_PLAYLIST))).isEqualTo(Decision.Allow)
    }

    /**
     * Documents a trust boundary: the engine cannot know whether a video really belongs to
     * [Candidate.viaPlaylistId]. A phone link like watch?v=<any>&list=<allowlisted playlist> must NOT be
     * mapped to viaPlaylistId, or any video plays in the child profile (SPEC acceptance: "قناة خارج السماح
     * لا تظهر حتى عبر رابط مرسل من الجوال").
     */
    @Test
    fun child_viaPlaylistId_isTrusted_soCallersMustOnlySetItForRealMembership() {
        val fromLinkListParam = video(channelId = OTHER_CHANNEL, viaPlaylistId = ALLOWED_PLAYLIST, url = "https://youtu.be/evil?list=$ALLOWED_PLAYLIST")
        assertThat(engine.canPlay(child(), fromLinkListParam, SessionState.Ok(3600))).isEqualTo(Decision.Allow)
        // Without the playlist context the same video is rejected.
        assertThat(engine.canPlay(child(), fromLinkListParam.copy(viaPlaylistId = null), SessionState.Ok(3600)))
            .isEqualTo(deny(DenyReason.NOT_IN_ALLOWLIST))
    }

    @Test
    fun child_blockedButAllowlisted_blockWinsEverywhere() {
        val kid = child().withBlockedChannel(ALLOWED_CHANNEL)
        assertThat(engine.canShow(kid, video())).isEqualTo(deny(DenyReason.BLOCKED_CHANNEL))
        assertThat(engine.canShow(kid, video(viaPlaylistId = ALLOWED_PLAYLIST))).isEqualTo(deny(DenyReason.BLOCKED_CHANNEL))
        assertThat(engine.canShowChannel(kid, ALLOWED_CHANNEL)).isEqualTo(deny(DenyReason.BLOCKED_CHANNEL))
        assertThat(engine.canShowPlaylist(kid, OTHER_PLAYLIST, ALLOWED_CHANNEL)).isEqualTo(deny(DenyReason.BLOCKED_CHANNEL))
        assertThat(engine.canShowPlaylist(kid, ALLOWED_PLAYLIST, ALLOWED_CHANNEL)).isEqualTo(deny(DenyReason.BLOCKED_CHANNEL))
        assertThat(engine.canPlay(kid, video(), SessionState.Ok(null))).isEqualTo(deny(DenyReason.BLOCKED_CHANNEL))
        assertThat(engine.canPlay(kid, video(), LockState(false, SessionState.Ok(null)))).isEqualTo(deny(DenyReason.BLOCKED_CHANNEL))
        // Live streams of a blocked channel are blocked too.
        assertThat(engine.canShow(kid, video(isLive = true, durationSeconds = null))).isEqualTo(deny(DenyReason.BLOCKED_CHANNEL))
    }

    @Test
    fun child_keywordBlocked_evenInsideAllowlistedPlaylist() {
        val kid = child().withBlockedKeywords("رعب")
        assertThat(engine.canShow(kid, video(title = "قصة رُعْب", channelId = OTHER_CHANNEL, viaPlaylistId = ALLOWED_PLAYLIST)))
            .isEqualTo(deny(DenyReason.BLOCKED_KEYWORD))
    }

    @Test
    fun child_removedFromAllowlist_isDeniedAgain() {
        val kid = child()
        val removed = kid.copy(allowlist = kid.allowlist.without(ALLOWED_CHANNEL).without(ALLOWED_PLAYLIST))
        assertThat(engine.canShow(removed, video())).isEqualTo(deny(DenyReason.NOT_IN_ALLOWLIST))
        assertThat(engine.canShow(removed, video(viaPlaylistId = ALLOWED_PLAYLIST))).isEqualTo(deny(DenyReason.NOT_IN_ALLOWLIST))
        assertThat(engine.canShowPlaylist(removed, ALLOWED_PLAYLIST, null)).isEqualTo(deny(DenyReason.NOT_IN_ALLOWLIST))
    }

    @Test
    fun child_liveFromOtherChannel_denied_liveFromAllowlisted_allowed() {
        val kid = child()
        assertThat(engine.canShow(kid, video(channelId = OTHER_CHANNEL, isLive = true, durationSeconds = null)))
            .isEqualTo(deny(DenyReason.NOT_IN_ALLOWLIST))
        assertThat(engine.canShow(kid, video(isLive = true, durationSeconds = 0))).isEqualTo(Decision.Allow)
    }

    @Test
    fun lockedPlayback_blocksAnyNewStart_forEveryProfile() {
        val locked = LockState(playbackLocked = true, session = SessionState.Ok(null))
        assertThat(engine.canPlay(child(), video(), locked)).isEqualTo(deny(DenyReason.LOCKED))
        assertThat(engine.canPlay(child(), video(viaPlaylistId = ALLOWED_PLAYLIST), locked)).isEqualTo(deny(DenyReason.LOCKED))
        assertThat(engine.canPlay(adult(), video(channelId = OTHER_CHANNEL), locked)).isEqualTo(deny(DenyReason.LOCKED))
        assertThat(engine.canPlay(child(), video(), LockState(true, SessionState.Warning(10)))).isEqualTo(deny(DenyReason.LOCKED))
    }

    @Test
    fun anotherChildsOverride_doesNotLiftTimeUp() {
        val kid = child("k1")
        val sibling = child("k2")
        val grants = Overrides.NONE +
            engine.grantUnlimitedToday(sibling) +
            engine.grantExtraTime(sibling, 3600, Overrides.NONE, 30) +
            engine.grantScheduleOverrideUntilEndOfDay(sibling) +
            engine.grantUnlimitedToday(adult())
        assertThat(engine.session(kid, 3600, grants)).isEqualTo(SessionState.TimeUp)
        val night = engineAt("2026-09-28T23:00")
        assertThat(night.session(kid, 0, Overrides.NONE + night.grantScheduleOverrideUntilEndOfDay(sibling)))
            .isInstanceOf(SessionState.OutsideSchedule::class.java)
    }

    // =========================================================================================
    // Shorts
    // =========================================================================================

    @Test
    fun shorts_durationBoundaries_forAChild() {
        val kid = child()
        val expected = mapOf<Long?, Decision>(
            179L to deny(DenyReason.SHORTS_HIDDEN),
            180L to deny(DenyReason.SHORTS_HIDDEN),
            181L to Decision.Allow,
            1L to deny(DenyReason.SHORTS_HIDDEN),
            0L to Decision.Allow, // unknown
            -1L to Decision.Allow, // NewPipe's "unknown"
            null to Decision.Allow, // RSS
        )
        for ((duration, decision) in expected) {
            assertWithMessage("duration %s", duration).that(engine.canShow(kid, video(durationSeconds = duration))).isEqualTo(decision)
            assertWithMessage("canPlay duration %s", duration)
                .that(engine.canPlay(kid, video(durationSeconds = duration), SessionState.Ok(3600))).isEqualTo(decision)
        }
    }

    @Test
    fun shorts_explicitSignalsWinOverDurationAndLive() {
        val kid = child()
        for (duration in listOf<Long?>(null, 0, 181, 3600)) {
            assertThat(engine.canShow(kid, video(durationSeconds = duration, isShortFormFlag = true))).isEqualTo(deny(DenyReason.SHORTS_HIDDEN))
            assertThat(engine.canShow(kid, video(durationSeconds = duration, url = "https://www.youtube.com/shorts/abc")))
                .isEqualTo(deny(DenyReason.SHORTS_HIDDEN))
            assertThat(engine.canShow(kid, video(durationSeconds = duration, isLive = true, isShortFormFlag = true)))
                .isEqualTo(deny(DenyReason.SHORTS_HIDDEN))
        }
    }

    @Test
    fun shorts_urlVariants() {
        for (url in listOf(
            "https://www.youtube.com/shorts/abc",
            "https://youtube.com/shorts/abc?si=xyz",
            "https://youtube.com/shorts/abc?feature=share",
            "https://m.youtube.com/shorts/abc",
            "HTTPS://WWW.YOUTUBE.COM/SHORTS/ABC",
            "https://www.youtube.com/Shorts/abc",
        )) {
            assertWithMessage(url).that(engine.isShort(video(url = url, durationSeconds = 900))).isTrue()
        }
        for (url in listOf(
            "https://www.youtube.com/watch?v=abc",
            "https://youtu.be/abc",
            "https://www.youtube.com/watch?v=shorts",
            "https://www.youtube.com/@shortsfan/videos",
        )) {
            assertWithMessage(url).that(engine.isShort(video(url = url, durationSeconds = 900))).isFalse()
        }
    }

    @Test
    fun shorts_liveNeverShortByDuration() {
        for (duration in listOf<Long?>(null, -1, 0, 1, 60, 180, 181)) {
            assertWithMessage("live %s", duration).that(engine.isShort(video(isLive = true, durationSeconds = duration))).isFalse()
        }
    }

    @Test
    fun shorts_contentOrder_allowlistThenBlockThenKeywordThenShorts() {
        val kid = child().withBlockedKeywords("رعب")
        assertThat(engine.canShow(kid, video(channelId = OTHER_CHANNEL, durationSeconds = 30))).isEqualTo(deny(DenyReason.NOT_IN_ALLOWLIST))
        assertThat(engine.canShow(kid, video(title = "رعب", durationSeconds = 30))).isEqualTo(deny(DenyReason.BLOCKED_KEYWORD))
        assertThat(engine.canShow(kid, video(durationSeconds = 30, viaPlaylistId = ALLOWED_PLAYLIST, channelId = OTHER_CHANNEL)))
            .isEqualTo(deny(DenyReason.SHORTS_HIDDEN))
    }

    // =========================================================================================
    // Arabic keyword normalization
    // =========================================================================================

    @Test
    fun keywords_tashkeelOnEitherSide() {
        val p = adult().withBlockedKeywords("رُعْب")
        assertThat(engine.canShow(p, video(title = "فيلم رعب"))).isEqualTo(deny(DenyReason.BLOCKED_KEYWORD))
        assertThat(engine.canShow(p, video(title = "فيلم رَعَّبَ"))).isEqualTo(deny(DenyReason.BLOCKED_KEYWORD))
        assertThat(engine.canShow(p, video(title = "فيلم رًٌٍَُِّْعب"))).isEqualTo(deny(DenyReason.BLOCKED_KEYWORD))
        val q = adult().withBlockedKeywords("رعب")
        assertThat(engine.canShow(q, video(title = "فيلم رُعْبٌ"))).isEqualTo(deny(DenyReason.BLOCKED_KEYWORD))
    }

    @Test
    fun keywords_tatweelOnEitherSide() {
        val p = adult().withBlockedKeywords("قـتـال")
        assertThat(engine.canShow(p, video(title = "قتال الشوارع"))).isEqualTo(deny(DenyReason.BLOCKED_KEYWORD))
        assertThat(engine.canShow(p, video(title = "قــــــتــــال"))).isEqualTo(deny(DenyReason.BLOCKED_KEYWORD))
        assertThat(engine.canShow(p, video(title = "ـقـ◌ـتـال"))).isEqualTo(Decision.Allow) // a visible dotted circle breaks the word
    }

    @Test
    fun keywords_alefVariantsInBothDirections() {
        val pairs = listOf(
            "أسد" to "اسد", "اسد" to "أسد", "إسلام" to "اسلام", "اسلام" to "إسلام",
            "آمن" to "امن", "امن" to "آمن", "ٱلحرب" to "الحرب", "الحرب" to "ٱلحرب", "أ" to "إ",
        )
        for ((keyword, title) in pairs) {
            assertWithMessage("%s in %s", keyword, title).that(TextNormalizer.containsKeyword("عنوان $title هنا", keyword)).isTrue()
        }
    }

    @Test
    fun keywords_otherLetterVariants() {
        assertThat(TextNormalizer.containsKeyword("مسابقة كبرى", "مسابقه كبري")).isTrue()
        assertThat(TextNormalizer.containsKeyword("مسابقه كبري", "مسابقة كبرى")).isTrue()
        assertThat(TextNormalizer.containsKeyword("قراءة", "قراءه")).isTrue()
        assertThat(TextNormalizer.containsKeyword("مسائل", "مسايل")).isTrue()
        assertThat(TextNormalizer.containsKeyword("ﺭﻋﺐ", "رعب")).isTrue() // presentation forms
        assertThat(TextNormalizer.containsKeyword("ﻷ", "لا")).isTrue()
    }

    @Test
    fun keywords_mixedLatinCaseAndCompatibilityForms() {
        val p = adult().withBlockedKeywords("HoRRoR")
        for (title in listOf("horror", "HORROR night", "Night of HoRrOr", "ＨＯＲＲＯＲ", "\uD835\uDC07\uD835\uDC0E\uD835\uDC11\uD835\uDC11\uD835\uDC0E\uD835\uDC11", "ⒽⓄⓇⓇⓄⓇ")) {
            assertWithMessage(title).that(engine.canShow(p, video(title = title))).isEqualTo(deny(DenyReason.BLOCKED_KEYWORD))
        }
        assertThat(engine.canShow(p, video(title = "Harry Potter"))).isEqualTo(Decision.Allow)
    }

    @Test
    fun keywords_arabicAndLatinMixedInOneKeyword() {
        val p = adult().withBlockedKeywords("تحدي TikTok")
        assertThat(engine.canShow(p, video(title = "أقوى تَحَدّي   tiktok 2026"))).isEqualTo(deny(DenyReason.BLOCKED_KEYWORD))
        assertThat(engine.canShow(p, video(title = "تحدي يوتيوب"))).isEqualTo(Decision.Allow)
    }

    @Test
    fun keywords_emptyOrInvisibleKeywords_blockNothing() {
        val blanks = arrayOf("", " ", "\t\n", "ـــ", "ًٌٍ", "\u200D", "\u200F\u200E", "\uFEFF", "ـَ ـ")
        val p = adult().withBlockedKeywords(*blanks)
        for (title in listOf("", " ", "أي عنوان", "Any title", "ـ")) {
            assertWithMessage("title '%s'", title).that(engine.canShow(p, video(title = title))).isEqualTo(Decision.Allow)
        }
        var list = Blocklist()
        for (b in blanks) list = list.withKeyword(b)
        assertThat(list.titleKeywords).isEmpty()
        assertThat(TextNormalizer.firstMatch("أي عنوان", blanks.toList())).isNull()
    }

    /** The candidates (as "U+XXXX") that, put between the letters of a blocked keyword, get the title allowed. */
    private fun leaksBetweenLetters(candidates: List<String>): List<String> {
        val p = adult().withBlockedKeywords("رعب")
        return candidates
            .filter { invisible -> engine.canShow(p, video(title = "فيلم " + sneak("رعب", invisible))) == Decision.Allow }
            .map { hex(it) }
    }

    @Test
    fun keywords_formatCharactersInsideTitle_areRemoved() {
        val formats = listOf("\u200B", "\u200C", "\u200D", "\u200E", "\u200F", "\u2060", "\uFEFF", "\u00AD", "\u061C", "\u202E", "\u2066")
        assertThat(leaksBetweenLetters(formats)).isEmpty()
    }

    /**
     * BUG: invisible default-ignorable characters that are not in Unicode category Cf (variation
     * selectors, combining grapheme joiner) are kept, so they split a blocked keyword while the title
     * still looks identical on screen.
     */
    @Test
    fun keywords_invisibleVariationSelectorsAndCgj_cannotSplitAKeyword() {
        assertThat(leaksBetweenLetters(listOf("\uFE0F", "\uFE00", "\u034F"))).isEmpty()
    }

    /**
     * BUG: [TextNormalizer] checks Character.getType per UTF-16 char, so invisible format characters outside
     * the BMP (TAG characters U+E0020..E007F, VARIATION SELECTOR-17.., U+1D173..) are two surrogate chars that
     * are never recognized as FORMAT and stay in the text.
     */
    @Test
    fun keywords_invisibleSupplementaryCharacters_cannotSplitAKeyword() {
        assertThat(leaksBetweenLetters(listOf(cp(0xE0061), cp(0xE0020), cp(0xE0001), cp(0xE0100), cp(0x1D173)))).isEmpty()
    }

    /**
     * BUG: harakat from the Arabic Extended-A block (curly fatha/damma/kasra U+08E4..U+08E6, open
     * tanween U+08F0..U+08F2) are not removed like the ordinary harakat U+064B..U+065F.
     */
    @Test
    fun keywords_extendedHarakat_areTashkeelToo() {
        val p = adult().withBlockedKeywords("رعب")
        val leaks = listOf(0x08E4, 0x08E5, 0x08E6, 0x08F0, 0x08F1, 0x08F2)
            .map { cp(it) }
            .filter { mark -> engine.canShow(p, video(title = "فيلم ر${mark}عب")) == Decision.Allow }
            .map { hex(it) }
        assertThat(leaks).isEmpty()
    }

    @Test
    fun keywords_whitespaceVariants() {
        val p = adult().withBlockedKeywords("  لعبة   رعب  ")
        for (title in listOf("لعبة رعب", "لعبة\u00A0رعب", "لعبة\u2003رعب", "لعبة\n\tرعب", "لعبة \u200B رعب", "لعبة\u3000رعب")) {
            assertWithMessage(hex(title)).that(engine.canShow(p, video(title = title))).isEqualTo(deny(DenyReason.BLOCKED_KEYWORD))
        }
    }

    // =========================================================================================
    // Features per profile
    // =========================================================================================

    @Test
    fun features_childInvariants_whateverTheFlags() {
        for (hideComments in listOf(true, false)) for (hideShorts in listOf(true, false)) for (quota in listOf(null, 0, 60)) {
            val kid = child().copy(hideComments = hideComments, hideShorts = hideShorts, dailyQuotaMinutes = quota, schedule = null)
            val f = engine.features(kid)
            assertThat(f.search).isFalse()
            assertThat(f.textInput).isFalse()
            assertThat(f.comments).isFalse()
            assertThat(f.undoSponsorSkip).isFalse()
            assertThat(f.enterNeedsPin).isFalse()
            assertThat(f.exitNeedsPin).isTrue()
            assertThat(f.adultExitChallenge).isFalse()
        }
    }

    @Test
    fun features_adultInvariants_whateverTheFlags() {
        for (hideComments in listOf(true, false)) for (hideShorts in listOf(true, false)) {
            val f = engine.features(adult().copy(hideComments = hideComments, hideShorts = hideShorts, dailyQuotaMinutes = 30))
            assertThat(f.search).isTrue()
            assertThat(f.textInput).isTrue()
            assertThat(f.comments).isEqualTo(!hideComments)
            assertThat(f.undoSponsorSkip).isTrue()
            assertThat(f.enterNeedsPin).isTrue()
            assertThat(f.exitNeedsPin).isFalse()
            assertThat(f.adultExitChallenge).isTrue()
        }
    }

    @Test
    fun features_newChildFromScratch_isRestrictedByDefault() {
        val kid = Profile.newChild("ليلى")
        assertThat(engine.features(kid).search).isFalse()
        assertThat(engine.canShow(kid, video())).isEqualTo(deny(DenyReason.NOT_IN_ALLOWLIST))
        assertThat(engine.canShowChannel(kid, ALLOWED_CHANNEL)).isEqualTo(deny(DenyReason.NOT_IN_ALLOWLIST))
        assertThat(engine.session(kid, 0)).isNotEqualTo(SessionState.Ok(null))
    }

    // =========================================================================================
    // PIN hashing and attempt limiter
    // =========================================================================================

    @Test
    fun pinFormat_rejectsLookalikeDigitsAndDecorations() {
        val rejected = listOf(
            "１２３４", // full-width
            "۱۲۳۴", // Persian digits
            "¹²³⁴", // superscripts
            "\uD835\uDFCF\uD835\uDFD0\uD835\uDFD1\uD835\uDFD2", // mathematical bold digits (length 8 in UTF-16)
            "+123", "-123", "1234\n", "1234 ", "12.3", "1e10", "123\u0000", "12\u200B34",
        )
        for (pin in rejected) {
            assertWithMessage(hex(pin)).that(PinHasher.validatePinFormat(pin)).isFalse()
        }
        for (pin in listOf("0000", "0123", "99999999", "1234567")) {
            assertWithMessage(pin).that(PinHasher.validatePinFormat(pin)).isTrue()
        }
    }

    @Test
    fun pinVerify_leadingZerosAndPrefixesMatter() {
        val hasher = PinHasher(iterations = 1000)
        val stored = hasher.hash("0123")
        assertThat(hasher.verify("0123", stored)).isTrue()
        assertThat(hasher.verify("123", stored)).isFalse()
        assertThat(hasher.verify("00123", stored)).isFalse()
        assertThat(hasher.verify("01234", stored)).isFalse()
        assertThat(hasher.verify("０１２３", stored)).isFalse()
        val long = hasher.hash("12345678")
        assertThat(hasher.verify("1234567", long)).isFalse()
        assertThat(hasher.verify("12345678", long)).isTrue()
    }

    @Test
    fun pinVerify_neverThrows_onOddEncodings() {
        val hasher = PinHasher(iterations = 1000)
        val good = hasher.hash("1234")
        val odd = listOf(good + "\n", " $good", good.replace("pbkdf2", "PBKDF2"), good.replace("$", "$$"), "$$$", "pbkdf2$1000$", good.reversed())
        for (encoded in odd) {
            assertWithMessage(encoded).that(hasher.verify("1234", encoded)).isFalse()
        }
    }

    @Test
    fun pinVerify_differentPinsDifferentHashes() {
        val hasher = PinHasher(iterations = 1000)
        val a = hasher.hash("1111")
        assertThat(hasher.verify("1112", a)).isFalse()
        assertThat(hasher.verify("1111", a)).isTrue()
    }

    @Test
    fun limiter_correctPinDuringLockout_isRefused_andDoesNotShortenIt() {
        val clock = clockAt("2026-09-28T12:00")
        val limiter = PinAttemptLimiter(clock)
        repeat(5) { limiter.attempt { false } }
        val until = limiter.state.lockedUntilEpochMs
        clock.advance(Duration.ofMinutes(4))
        assertThat(limiter.attempt { true }).isEqualTo(PinAttemptResult.LockedOut(60_000))
        assertThat(limiter.state.lockedUntilEpochMs).isEqualTo(until)
        clock.advance(Duration.ofSeconds(59).plusMillis(999))
        assertThat(limiter.attempt { true }).isEqualTo(PinAttemptResult.LockedOut(1))
        clock.advance(Duration.ofMillis(1))
        assertThat(limiter.attempt { true }).isEqualTo(PinAttemptResult.Success)
        assertThat(limiter.state).isEqualTo(PinAttemptState())
    }

    @Test
    fun limiter_lockoutIsExactlyFiveMinutes_andTheNextOneAfterFiveMoreFailuresIsLonger() {
        val clock = clockAt("2026-09-28T12:00")
        val limiter = PinAttemptLimiter(clock)
        repeat(4) { assertThat(limiter.attempt { false }).isInstanceOf(PinAttemptResult.Wrong::class.java) }
        assertThat(limiter.attempt { false }).isEqualTo(PinAttemptResult.LockedOut(300_000))
        clock.advance(Duration.ofMinutes(5))
        assertThat(limiter.isLocked()).isFalse()
        repeat(4) { i -> assertThat(limiter.attempt { false }).isEqualTo(PinAttemptResult.Wrong(4 - i)) }
        assertThat(limiter.attempt { false }).isEqualTo(PinAttemptResult.LockedOut(15 * 60_000L))
    }

    @Test
    fun limiter_restartBetweenFailures_keepsCounting() {
        val clock = clockAt("2026-09-28T12:00")
        var state = PinAttemptState()
        repeat(4) {
            val l = PinAttemptLimiter(clock, state)
            l.attempt { false }
            state = l.state
        }
        val last = PinAttemptLimiter(clock, state)
        assertThat(last.attempt { false }).isEqualTo(PinAttemptResult.LockedOut(300_000))
        // A fresh instance restored from the saved state is still locked and does not check the PIN.
        var checked = false
        assertThat(PinAttemptLimiter(clock, last.state).attempt { checked = true; true }).isEqualTo(PinAttemptResult.LockedOut(300_000))
        assertThat(checked).isFalse()
    }

    @Test
    fun limiter_successResetsEvenAfterFourFailures() {
        val clock = clockAt("2026-09-28T12:00")
        val limiter = PinAttemptLimiter(clock)
        repeat(4) { limiter.attempt { false } }
        assertThat(limiter.attempt { true }).isEqualTo(PinAttemptResult.Success)
        repeat(4) { limiter.attempt { false } }
        assertThat(limiter.isLocked()).isFalse()
        assertThat(limiter.attemptsLeft()).isEqualTo(1)
    }

    // =========================================================================================
    // Adult exit challenge
    // =========================================================================================

    @Test
    fun exitChallenge_eachPhraseMatchesOnlyItself() {
        val phrases = AdultExitChallenge.phrases
        for (expected in phrases) for (typed in phrases) {
            assertWithMessage("'%s' typed for '%s'", typed, expected)
                .that(AdultExitChallenge.matches(expected, typed)).isEqualTo(expected == typed)
        }
    }

    @Test
    fun exitChallenge_rejectsNearMisses() {
        val phrase = "الصبر مفتاح الفرج"
        val wrong = listOf(
            "الصبر مفتاح", "مفتاح الصبر الفرج", "الصبرمفتاح الفرج", "الصبر مفتاح الفرج الفرج",
            "الصبر مفتاح الفرح", "x الصبر مفتاح الفرج", "الصبر مفتاح الفرجx", "alsabr miftah alfaraj", "*",
        )
        for (typed in wrong) {
            assertWithMessage(typed).that(AdultExitChallenge.matches(phrase, typed)).isFalse()
        }
        assertThat(AdultExitChallenge.matches(phrase, "الصبر\u200B مفتاح الفرج")).isTrue()
    }

    @Test
    fun exitChallenge_holdAndRequirement() {
        assertThat(AdultExitChallenge.isHoldComplete(Long.MIN_VALUE)).isFalse()
        assertThat(AdultExitChallenge.isHoldComplete(Long.MAX_VALUE)).isTrue()
        assertThat(AdultExitChallenge.holdProgress(Long.MAX_VALUE)).isEqualTo(1f)
        assertThat(AdultExitChallenge.holdProgress(Long.MIN_VALUE)).isEqualTo(0f)
        assertThat(AdultExitChallenge.isRequired(adult().copy(hideComments = true), videoFinished = false)).isTrue()
        assertThat(AdultExitChallenge.isRequired(Profile.newChild("x"), videoFinished = false)).isFalse()
    }

    // =========================================================================================
    // Stats windows
    // =========================================================================================

    private val today = epochDay("2026-09-28")
    private val calc = UsageStatsCalculator(RIYADH)

    @Test
    fun stats_surrenderWindow_exactLocalMidnights() {
        fun count(at: String) = calc.summary("adult", today, emptyList(), listOf(SurrenderEvent("adult", epochMsAt(at), "v")))
            .surrendersLast7Days
        assertThat(count("2026-09-22T00:00")).isEqualTo(1)
        assertThat(count("2026-09-21T23:59:59.999")).isEqualTo(0)
        assertThat(count("2026-09-28T23:59:59.999")).isEqualTo(1)
        assertThat(count("2026-09-29T00:00")).isEqualTo(0)
    }

    @Test
    fun stats_windowsDoNotOverlap_andOrderDoesNotMatter() {
        val records = (0..20).map { UsageRecord("kid", today - it, UsageKind.FOREGROUND, 60L * (it + 1)) }
        val s = calc.summary("kid", today, records, emptyList())
        val s2 = calc.summary("kid", today, records.shuffled(java.util.Random(7)), emptyList())
        assertThat(s2).isEqualTo(s)
        assertThat(s.days).hasSize(7)
        assertThat(s.thisWeekSeconds).isEqualTo((1L..7L).sum() * 60)
        assertThat(s.previousWeekSeconds).isEqualTo((8L..14L).sum() * 60)
        assertThat(s.dailyMinutes).containsExactly(7L, 6L, 5L, 4L, 3L, 2L, 1L).inOrder()
    }

    @Test
    fun stats_topChannelsIgnoreForegroundAndOtherProfiles() {
        val records = listOf(
            UsageRecord("kid", today, UsageKind.FOREGROUND, 9_999, "UC_fg", "مقدمة"),
            UsageRecord("kid", today, UsageKind.PLAYBACK, 60, "UC_a", "أ"),
            UsageRecord("kid2", today, UsageKind.PLAYBACK, 9_999, "UC_b", "ب"),
            UsageRecord("kid", today, UsageKind.PLAYBACK, -500, "UC_neg", "سالب"),
        )
        val s = calc.summary("kid", today, records, emptyList())
        assertThat(s.topChannels).containsExactly(ChannelUsage("UC_a", "أ", 60))
        assertThat(s.thisWeekSeconds).isEqualTo(9_999)
    }

    @Test
    fun stats_quotaInputMatchesTodayOfTheSummary() {
        val records = listOf(
            UsageRecord("kid", today, UsageKind.FOREGROUND, 1_000),
            UsageRecord("kid", today, UsageKind.FOREGROUND, 234),
            UsageRecord("kid", today, UsageKind.PLAYBACK, 5_000, "UC_a"),
        )
        assertThat(calc.foregroundSeconds("kid", today, records)).isEqualTo(calc.summary("kid", today, records, emptyList()).days.last().seconds)
    }
}
