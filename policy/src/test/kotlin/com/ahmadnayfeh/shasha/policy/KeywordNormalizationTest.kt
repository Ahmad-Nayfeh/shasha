package com.ahmadnayfeh.shasha.policy

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.util.Locale

class KeywordNormalizationTest {

    private fun n(s: String) = TextNormalizer.normalize(s)

    @Test
    fun stripsTashkeel() {
        assertThat(n("مُحَمَّدٌ")).isEqualTo("محمد")
        assertThat(n("كِتَابًا")).isEqualTo("كتابا")
        assertThat(n("هٰذا")).isEqualTo("هذا") // superscript alef
    }

    @Test
    fun stripsQuranicAnnotationMarks() {
        val marked = "ا\u0610ب\u061Aت\u06D6ث\u06DCج\u06DFح\u06E4خ\u06E7د\u06E8ذ\u06EAر\u06ED"
        assertThat(n(marked)).isEqualTo("ابتثجحخدذر")
    }

    @Test
    fun stripsTatweel() {
        assertThat(n("جـــمـيـل")).isEqualTo("جميل")
    }

    @Test
    fun unifiesLetterVariants() {
        assertThat(n("أإآٱ")).isEqualTo("اااا")
        assertThat(n("مصطفى")).isEqualTo("مصطفي")
        assertThat(n("مدرسة")).isEqualTo("مدرسه")
        assertThat(n("مؤمن")).isEqualTo("مومن")
        assertThat(n("مسائل")).isEqualTo("مسايل")
        assertThat(n("کتابی")).isEqualTo("كتابي") // Persian kaf and yeh
    }

    @Test
    fun combiningHamzaAndMaddaAreFolded() {
        assertThat(n("آ")).isEqualTo("ا") // alef + combining madda → آ → ا
        assertThat(n("أ")).isEqualTo("ا") // alef + combining hamza above
    }

    @Test
    fun lowercasesLatin_independentOfDefaultLocale() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr"))
            assertThat(n("MINECRAFT Title")).isEqualTo("minecraft title")
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun collapsesAndTrimsWhitespace() {
        assertThat(n("  لعبة \t\n  رعب  ")).isEqualTo("لعبه رعب")
        assertThat(n("a  b")).isEqualTo("a b") // no-break spaces
        assertThat(n("   ")).isEmpty()
        assertThat(n("")).isEmpty()
    }

    @Test
    fun removesInvisibleFormatCharacters() {
        assertThat(n("ر‌ع‍ب‏")).isEqualTo("رعب")
        assertThat(n("‏  رعب")).isEqualTo("رعب")
    }

    @Test
    fun foldsPresentationFormsAndFullWidth() {
        assertThat(n("ﻻ")).isEqualTo("لا") // lam-alef ligature
        assertThat(n("ＦＯＲＴＮＩＴＥ")).isEqualTo("fortnite")
    }

    @Test
    fun containsKeyword_matchesAcrossSpellingVariants() {
        assertThat(TextNormalizer.containsKeyword("أفضل لُعبَة في العالم", "لعبه")).isTrue()
        assertThat(TextNormalizer.containsKeyword("Best FORTNITE clips", "fortnite")).isTrue()
        assertThat(TextNormalizer.containsKeyword("قصة رعـــب", "  رعب ")).isTrue()
        assertThat(TextNormalizer.containsKeyword("لعبة    رعب", "لعبة رعب")).isTrue()
        assertThat(TextNormalizer.containsKeyword("كرتون", "رعب")).isFalse()
    }

    @Test
    fun containsKeyword_isSubstring() {
        // Substring on purpose: "رعب" also catches "مرعب".
        assertThat(TextNormalizer.containsKeyword("مشهد مرعب", "رعب")).isTrue()
    }

    @Test
    fun blankKeywords_neverMatch() {
        assertThat(TextNormalizer.containsKeyword("أي عنوان", "")).isFalse()
        assertThat(TextNormalizer.containsKeyword("أي عنوان", "   ")).isFalse()
        assertThat(TextNormalizer.containsKeyword("أي عنوان", "َـ")).isFalse() // only a haraka and tatweel
    }

    @Test
    fun firstMatch() {
        assertThat(TextNormalizer.firstMatch("فيلم رعب", listOf("", "كرتون", "رعب", "فيلم"))).isEqualTo("رعب")
        assertThat(TextNormalizer.firstMatch("فيلم رعب", listOf("  ", "كرتون"))).isNull()
        assertThat(TextNormalizer.firstMatch("فيلم رعب", emptyList())).isNull()
    }

    @Test
    fun engineUsesNormalizedKeywords() {
        val engine = engineAt("2026-09-28T12:00")
        val p = adult().withBlockedKeywords("", "   ", "مُرعِب", "HORROR")
        assertThat(engine.canShow(p, video(title = "أكثر مشهد مرعـــب"))).isEqualTo(deny(DenyReason.BLOCKED_KEYWORD))
        assertThat(engine.canShow(p, video(title = "Horror night"))).isEqualTo(deny(DenyReason.BLOCKED_KEYWORD))
        assertThat(engine.canShow(p, video(title = "رسوم متحركة"))).isEqualTo(Decision.Allow)
    }

    @Test
    fun invisibleCharactersOfEveryKind_areRemoved() {
        val invisible = listOf(
            0x034F, 0xFE00, 0xFE0F, 0x180B, 0x17B4, // marks without width
            0x115F, 0x1160, 0x3164, 0xFFA0, // Hangul fillers
            0x2065, 0xFFF0, 0xE0000, 0xE0061, 0xE007F, 0xE0100, 0xE01EF, 0x1D173, 0x1BCA0, // reserved, tags, VS17+, Cf
            0x0007, 0x007F, 0x0085, // controls (0x85 is whitespace: becomes a space, then collapses)
        )
        for (cp in invisible) {
            val s = buildString { append('ر'); appendCodePoint(cp); append('ع'); appendCodePoint(cp); append('ب') }
            assertWithMessage("U+%s", Integer.toHexString(cp)).that(n(s).replace(" ", "")).isEqualTo("رعب")
        }
    }

    @Test
    fun allArabicMarkBlocks_areTashkeel() {
        for (cp in listOf(0x08D3, 0x08E4, 0x08F0, 0x08F2, 0x08FF, 0x0898, 0x065F, 0x06E3, 0x0610)) {
            val s = buildString { append('ر'); appendCodePoint(cp); append("عب") }
            assertWithMessage("U+%s", Integer.toHexString(cp)).that(n(s)).isEqualTo("رعب")
        }
    }

    @Test
    fun visibleCharactersStay() {
        assertThat(n("ر◌عب")).isEqualTo("ر◌عب") // dotted circle
        assertThat(n("ر😀عب")).isEqualTo("ر😀عب") // emoji outside the BMP
        assertThat(n("ر۞عب")).isEqualTo("ر۞عب") // rub el hizb
        assertThat(n("café naïve")).isEqualTo("café naïve") // precomposed letters keep their accents
    }

    @Test
    fun lowercasingNeverLeavesAStrayMark() {
        // "İ" lowercases to i + combining dot above; the dot is removed like any other mark.
        assertThat(n("İSTANBUL")).isEqualTo("istanbul")
        assertThat(TextNormalizer.containsKeyword("Visit istanbul", "İstanbul")).isTrue()
    }

    @Test
    fun keywordMatcher_matchesLikeFirstMatch() {
        val keywords = listOf("", "كرتون", "رُعب", "HORROR")
        val matcher = KeywordMatcher(keywords)
        for (title in listOf("فيلم رعب", "Horror night", "رسوم", "", "كرتون رعب")) {
            assertWithMessage(title).that(matcher.firstMatch(title)).isEqualTo(TextNormalizer.firstMatch(title, keywords))
        }
        assertThat(matcher.firstMatch("فيلم رعب")).isEqualTo("رُعب") // the original spelling
        assertThat(KeywordMatcher(emptyList()).firstMatch("أي شيء")).isNull()
    }
}
