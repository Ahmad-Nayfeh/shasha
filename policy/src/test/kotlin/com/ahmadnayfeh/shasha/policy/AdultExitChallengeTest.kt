package com.ahmadnayfeh.shasha.policy

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.random.Random

class AdultExitChallengeTest {

    private val challenge = AdultExitChallenge

    @Test
    fun holdIsTenSeconds() {
        assertThat(AdultExitChallenge.HOLD_MILLIS).isEqualTo(10_000)
        assertThat(challenge.isHoldComplete(9_999)).isFalse()
        assertThat(challenge.isHoldComplete(10_000)).isTrue()
        assertThat(challenge.isHoldComplete(15_000)).isTrue()
    }

    @Test
    fun holdProgress_isClamped() {
        assertThat(challenge.holdProgress(-1)).isEqualTo(0f)
        assertThat(challenge.holdProgress(0)).isEqualTo(0f)
        assertThat(challenge.holdProgress(5_000)).isEqualTo(0.5f)
        assertThat(challenge.holdProgress(20_000)).isEqualTo(1f)
    }

    @Test
    fun keyboardLetters_are28DistinctArabicLetters() {
        val letters = AdultExitChallenge.KEYBOARD_LETTERS
        assertThat(letters).hasLength(28)
        assertThat(letters.toSet()).hasSize(28)
        assertThat(letters.all { it in 'ا'..'ي' }).isTrue()
        for (forbidden in "أإآٱؤئءةى") assertThat(letters).doesNotContain(forbidden.toString())
    }

    @Test
    fun atLeastEightDistinctShortPhrases() {
        val phrases = AdultExitChallenge.phrases
        assertThat(phrases.size).isAtLeast(8)
        assertThat(phrases.toSet()).hasSize(phrases.size)
        for (p in phrases) {
            assertThat(p.length).isAtMost(24)
            assertThat(p.split(" ").size).isAtMost(5)
        }
    }

    @Test
    fun phrases_onlyUseKeyboardLettersAndSingleSpaces() {
        val allowed = AdultExitChallenge.KEYBOARD_LETTERS.toSet() + ' '
        for (p in AdultExitChallenge.phrases) {
            assertThat(p).isNotEmpty()
            assertThat(p).isEqualTo(p.trim())
            assertThat(p).doesNotContain("  ")
            val bad = p.filterNot { it in allowed }
            assertThat(bad).isEmpty()
            // Already in normal form: what the keyboard types is exactly what is shown.
            assertThat(TextNormalizer.normalize(p)).isEqualTo(p)
        }
    }

    @Test
    fun randomPhrase_isDeterministicForASeed_andReachesEveryPhrase() {
        assertThat(challenge.randomPhrase(Random(42))).isEqualTo(challenge.randomPhrase(Random(42)))
        val seen = (0 until 500).map { challenge.randomPhrase(Random(it)) }.toSet()
        assertThat(seen).containsExactlyElementsIn(AdultExitChallenge.phrases)
    }

    @Test
    fun matches_exactAndSpacingVariants() {
        val phrase = "الوقت ثمين"
        assertThat(challenge.matches(phrase, "الوقت ثمين")).isTrue()
        assertThat(challenge.matches(phrase, "  الوقت    ثمين ")).isTrue()
        assertThat(challenge.matches(phrase, "الوَقتُ ثَمينٌ")).isTrue()
        assertThat(challenge.matches(phrase, "الـوقـت ثمين")).isTrue()
    }

    @Test
    fun matches_usesTheSameLetterFolding() {
        assertThat(challenge.matches("النجاح يحتاج صبرا", "النجاح يحتاج صبراً")).isTrue()
        assertThat(challenge.matches("كفى مدرسة", "كفي مدرسه")).isTrue()
        assertThat(challenge.matches("Stop Now", "stop now")).isTrue()
    }

    @Test
    fun matches_rejectsWrongPartialOrEmpty() {
        val phrase = "من جد وجد"
        assertThat(challenge.matches(phrase, "من جد")).isFalse()
        assertThat(challenge.matches(phrase, "من جد وجدت")).isFalse()
        assertThat(challenge.matches(phrase, "منجد وجد")).isFalse()
        assertThat(challenge.matches(phrase, "")).isFalse()
        assertThat(challenge.matches(phrase, "   ")).isFalse()
        assertThat(challenge.matches("", "")).isFalse()
    }

    @Test
    fun isRequired_onlyForAdultsLeavingEarly() {
        assertThat(challenge.isRequired(adult(), videoFinished = false)).isTrue()
        assertThat(challenge.isRequired(adult(), videoFinished = true)).isFalse()
        assertThat(challenge.isRequired(child(), videoFinished = false)).isFalse()
        assertThat(challenge.isRequired(child(), videoFinished = true)).isFalse()
    }
}
