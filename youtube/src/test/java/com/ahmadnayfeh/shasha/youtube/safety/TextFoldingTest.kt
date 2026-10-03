package com.ahmadnayfeh.shasha.youtube.safety

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** How a text becomes the words the filter compares: [TextFolding.readings] and [TextFolding.termWords]. */
class TextFoldingTest {

    private fun plain(text: String): List<String> = TextFolding.readings(text).firstOrNull().orEmpty()

    private fun all(text: String): List<List<String>> = TextFolding.readings(text)

    @Test
    fun lowercaseWordsSplitAtSpacesPunctuationAndSymbols() {
        assertThat(plain("Hello, World! (2024) | Official-Video")).containsExactly("hello", "world", "2024", "official", "video").inOrder()
        assertThat(plain("  spaced   out \t text\n")).containsExactly("spaced", "out", "text").inOrder()
        assertThat(plain("ab🔥cd")).containsExactly("ab", "cd").inOrder()
        assertThat(all("   ")).isEmpty()
        assertThat(all("")).isEmpty()
        assertThat(all("!!! ... ???")).isEmpty()
    }

    @Test
    fun accentsAndMarksAreDropped() {
        assertThat(plain("Café Crème Brûlée")).containsExactly("cafe", "creme", "brulee").inOrder()
        assertThat(plain("Ṡėẋ")).containsExactly("sex")
        assertThat(plain("İSTANBUL ıı")).containsExactly("istanbul", "ii").inOrder()
        assertThat(plain("Søren Łukasz Đorđe Straße")).containsExactly("soren", "lukasz", "dorde", "strasse").inOrder()
    }

    @Test
    fun compatibilityFormsBecomePlainLetters() {
        assertThat(plain("ＳＥＸ ｐｏｒｎ")).containsExactly("sex", "porn").inOrder()
        assertThat(plain("𝐬𝐞𝐱 𝓷𝓾𝓭𝓮 ⓣⓦⓔⓡⓚ")).containsExactly("sex", "nude", "twerk").inOrder()
        assertThat(plain("ﻻ")).containsExactly("لا") // lam-alef ligature
        assertThat(plain("²³")).containsExactly("23")
    }

    @Test
    fun regionalIndicatorsAndNegativeLettersAreLetters() {
        assertThat(plain("🇸🇪🇽")).containsExactly("sex")
        assertThat(plain("🅢🅔🅧")).containsExactly("sex")
        assertThat(plain("🅿🅾🆁🅽")).containsExactly("porn")
    }

    @Test
    fun invisibleCharactersJoinTheLettersAroundThem() {
        assertThat(plain("s​e‌x‍")).containsExactly("sex")
        assertThat(plain("p­o⁠r﻿n")).containsExactly("porn")
        assertThat(plain("s‏e‎x")).containsExactly("sex")
        assertThat(plain("ex️ample")).containsExactly("example")
        assertThat(plain("ㅤᅟx")).containsExactly("x")
    }

    @Test
    fun arabicLettersAreFoldedLikeThePolicyModulesNormalizer() {
        assertThat(plain("أإآٱ")).containsExactly("اااا")
        assertThat(plain("مصطفى مدرسة مؤمن مسائل")).containsExactly("مصطفي", "مدرسه", "مومن", "مسايل").inOrder()
        assertThat(plain("مُحَمَّدٌ كِتَابًا هٰذا")).containsExactly("محمد", "كتابا", "هذا").inOrder()
        assertThat(plain("جـــمـيـل")).containsExactly("جميل")
        assertThat(plain("کتابی")).containsExactly("كتابي") // Persian kaf and yeh
        assertThat(plain("پورن چند ژ")).containsExactly("بورن", "جند", "ز").inOrder() // Persian letters fold onto the Arabic ones
    }

    @Test
    fun digitsOfEveryScriptAreAsciiDigits() {
        assertThat(plain("١٨ ۱۸ १८")).containsExactly("18", "18", "18")
        assertThat(TextFolding.hasAgeMark("١٨+")).isTrue()
    }

    @Test
    fun anApostropheEndsAWord_soContractionsNeverFormOtherWords() {
        assertThat(plain("who're you")).containsExactly("who", "re", "you").inOrder()
        assertThat(plain("Pirate's Booty")).containsExactly("pirate", "s", "booty").inOrder()
        assertThat(plain("it’s")).containsExactly("it", "s").inOrder()
    }

    @Test
    fun singleLettersInARowAreOneWord() {
        assertThat(plain("s e x")).containsExactly("sex")
        assertThat(plain("S.E.X")).containsExactly("sex")
        assertThat(plain("p-o-r-n videos")).containsExactly("porn", "videos").inOrder()
        assertThat(plain("p_o_r_n")).containsExactly("porn")
        assertThat(plain("س ك س")).containsExactly("سكس")
        assertThat(plain("s 3 x")).containsExactly("s3x")
        // A lone letter, or numbers, stay as they are.
        assertThat(plain("I am a boy")).containsExactly("i", "am", "a", "boy").inOrder()
        assertThat(plain("Vitamin B 12")).containsExactly("vitamin", "b", "12").inOrder()
        assertThat(plain("1 2 3 4")).containsExactly("1", "2", "3", "4").inOrder()
        assertThat(plain("Plan A or B")).containsExactly("plan", "a", "or", "b").inOrder()
    }

    @Test
    fun symbolsAndSignsOnTheirOwnAreNotWords() {
        assertThat(plain("$ @ § € $$$")).isEmpty()
        assertThat(plain("$100 for @home")).containsExactly("$100", "for", "@home").inOrder()
    }

    @Test
    fun aSecondReadingSplitsCamelCase_theFirstKeepsTheWordWhole() {
        val readings = all("SexOnTheBeat")
        assertThat(readings).hasSize(2)
        assertThat(readings[0]).containsExactly("sexonthebeat")
        assertThat(readings[1]).containsExactly("sex", "on", "the", "beat").inOrder()
        assertThat(all("SeXy")[0]).containsExactly("sexy")
        assertThat(all("lowercase only")).hasSize(1)
        assertThat(all("ALL CAPS TITLE")).hasSize(1)
    }

    @Test
    fun aThirdReadingDropsDecorativeSymbols() {
        val readings = all("se🔥xy")
        assertThat(readings).hasSize(2)
        assertThat(readings[0]).containsExactly("se", "xy").inOrder()
        assertThat(readings[1]).containsExactly("sexy")
        assertThat(all("sx*ex*xx").last()).containsExactly("sxexxx")
        assertThat(all("t•w•e•r•k").last()).containsExactly("twerk")
        // Punctuation is not decoration: it only separates.
        assertThat(all("se.xy")).hasSize(1)
        assertThat(all("hello-world")).hasSize(1)
    }

    @Test
    fun onlyTheStartOfAVeryLongTextIsRead() {
        val text = "word ".repeat(1_000)
        assertThat(plain(text).size).isAtMost(TextFolding.MAX_CHARS / 5 + 1)
    }

    @Test
    fun termsAreFoldedLikeTitlesButNotJoined() {
        assertThat(TextFolding.termWords("Lap Dance")).containsExactly("lap", "dance").inOrder()
        assertThat(TextFolding.termWords("إغراء")).containsExactly("اغراء")
        assertThat(TextFolding.termWords("s e x")).containsExactly("s", "e", "x").inOrder()
        assertThat(TextFolding.termWords("pr0n")).containsExactly("pr0n")
        assertThat(TextFolding.termWords("SexOnTheBeat")).containsExactly("sexonthebeat")
    }

    @Test
    fun ageMarks() {
        for (text in listOf("18+", "videos 18+", "(18+)", "+18", "+ 21", "21+", "18 plus", "18plus", "Adults 18+ only")) {
            assertThat(TextFolding.hasAgeMark(text)).isTrue()
        }
        for (text in listOf("1.18+", "Minecraft 1.18+ mods", "iOS 18+", "Android 21+", "v18+", "118+", "2018", "18", "plus 18th", "180+ videos", "top 10+", "A+ student")) {
            assertThat(TextFolding.hasAgeMark(text)).isFalse()
        }
    }
}
