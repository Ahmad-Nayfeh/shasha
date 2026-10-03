package com.ahmadnayfeh.shasha.youtube.safety

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * The lists of [ExplicitTerms] kept honest: every entry is well formed, none is listed twice, every term blocks itself, and every
 * allowed entry still earns its place. These are the mistakes that creep in when someone adds a line.
 */
class ExplicitTermsTest {

    private val rules = ExplicitTerms.rules

    private fun folded(term: String): List<String> = TextFolding.termWords(term)

    @Test
    fun singleWordListsHoldOneWordEach_ofLettersAndDigitsOnly() {
        val lists = mapOf("anywhere" to rules.anywhere, "words" to rules.words, "exact" to rules.exact, "alone" to rules.alone)
        for ((name, terms) in lists) {
            for (term in terms) {
                assertWithMessage("$name: \"$term\"").that(folded(term)).hasSize(1)
                assertWithMessage("$name: \"$term\" is only letters and digits").that(folded(term)[0].all { it.isLetterOrDigit() }).isTrue()
            }
        }
    }

    @Test
    fun phrasesHoldTwoWordsOrMore() {
        for (phrase in rules.phrases) assertWithMessage(phrase).that(folded(phrase).size).isAtLeast(2)
    }

    @Test
    fun nothingIsListedTwiceInAList() {
        val lists = mapOf(
            "anywhere" to rules.anywhere, "words" to rules.words, "exact" to rules.exact, "alone" to rules.alone,
            "phrases" to rules.phrases, "allowed" to rules.allowed,
        )
        for ((name, terms) in lists) {
            val keys = terms.map { folded(it).joinToString(" ") }
            val repeated = keys.groupingBy { it }.eachCount().filter { it.value > 1 }.keys
            assertWithMessage("$name lists these twice (after folding)").that(repeated).isEmpty()
        }
    }

    @Test
    fun aWordIsInTheListItNeeds() {
        // "anywhere" is for words no ordinary word contains: nothing short but sex, xxx and سكس, which have their ALLOWED entries.
        val short = rules.anywhere.filter { folded(it)[0].length < 4 }
        assertThat(short).containsExactly("sex", "xxx", "سكس")
        // A word of the "exact" and "alone" lists is not also in "words" (which would make the stricter list pointless).
        val fold = { terms: List<String> -> terms.map { folded(it)[0] } }
        assertThat(fold(rules.exact).intersect(fold(rules.words).toSet())).isEmpty()
        assertThat(fold(rules.alone).intersect(fold(rules.words).toSet())).isEmpty()
        assertThat(fold(rules.alone).intersect(fold(rules.exact).toSet())).isEmpty()
    }

    @Test
    fun everyTermBlocksItself() {
        val filter = ExplicitFilter.default
        val singles = rules.anywhere + rules.words + rules.exact + rules.alone + rules.phrases
        val notBlocked = singles.filterNot { filter.isExplicit(it) }
        assertWithMessage("terms that do not block themselves (an allowed entry hides them?)").that(notBlocked).isEmpty()
    }

    @Test
    fun everyGatePairBlocks() {
        val filter = ExplicitFilter.default
        val missed = ArrayList<String>()
        for (gate in rules.gates) {
            assertThat(gate.triggers).isNotEmpty()
            assertThat(gate.companions).isNotEmpty()
            assertThat(gate.before + gate.after).isAtLeast(1)
            for (trigger in gate.triggers) {
                assertThat(folded(trigger)).hasSize(1)
                for (companion in gate.companions) {
                    assertThat(folded(companion)).hasSize(1)
                    val pairs = buildList {
                        if (gate.after >= 1) add("$trigger $companion")
                        if (gate.before >= 1) add("$companion $trigger")
                    }
                    for (pair in pairs) if (!filter.isExplicit(pair)) missed += pair
                }
            }
        }
        assertWithMessage("gate pairs that do not block").that(missed).isEmpty()
    }

    @Test
    fun aGatesTriggerAloneIsOrdinary_orItIsNotAGate() {
        val filter = ExplicitFilter.default
        // These are the words the gates exist for. If one of them alone is blocked by another list, the gate is pointless.
        for (word in listOf("hot", "kiss", "strip", "scene", "adult", "mature", "naughty", "dirty", "spicy", "steamy", "provocative", "revealing", "curvy", "thick", "transparent", "underwear", "swimsuit", "swimwear", "sheer", "uncensored", "skimpy", "asmr")) {
            assertWithMessage(word).that(filter.isExplicit(word)).isFalse()
        }
    }

    @Test
    fun everyAllowedEntryIsStillNeeded() {
        // Without its entry, the allowed text must be blocked: otherwise the entry is dead weight, and dead weight hides things later.
        val needed = rules.allowed
        val dead = ArrayList<String>()
        for (entry in needed) {
            val without = TermIndex(
                Rules(rules.anywhere, rules.words, rules.exact, rules.phrases, rules.gates, rules.alone, rules.allowed - entry),
            )
            val text = entry
            if (!without.matches(TextFolding.readings(text))) dead += entry
        }
        assertWithMessage("allowed entries that nothing would block").that(dead).isEmpty()
    }

    @Test
    fun everyAllowedEntryIsAnOrdinaryText() {
        // ... and with it, it passes.
        val filter = ExplicitFilter.default
        val blocked = rules.allowed.filter { filter.isExplicit(it) }
        assertWithMessage("allowed entries that are still blocked").that(blocked).isEmpty()
    }

    @Test
    fun theListsAreBigEnoughToMeanIt() {
        // A guard against a list emptied by a bad edit (an unbalanced raw string swallows the rest of a category).
        assertThat(rules.anywhere.size).isAtLeast(80)
        assertThat(rules.words.size).isAtLeast(250)
        assertThat(rules.phrases.size).isAtLeast(100)
        assertThat(rules.gates.size).isAtLeast(25)
        assertThat(rules.allowed.size).isAtLeast(100)
        assertThat(ExplicitTerms.CATEGORIES.map { it.name }).containsNoDuplicates()
    }

    @Test
    fun everyLanguageTheOwnerNamedIsThere() {
        val filter = ExplicitFilter.default
        // One sample per language and script: English, Arabic (MSA and dialect), Franco-Arabic, Spanish, French, Turkish, Persian,
        // Urdu, Hindi (Latin), Russian.
        for (sample in listOf("porn", "سكس", "نيك", "sks", "porno", "sexe", "seks", "سکس", "ننگا", "chudai", "секс")) {
            assertWithMessage(sample).that(filter.isExplicit(sample)).isTrue()
        }
    }
}
