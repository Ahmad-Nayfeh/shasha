package com.ahmadnayfeh.shasha.youtube.safety

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Opt-in: lists which words of a word list the filter blocks, to find ordinary words that a new term catches ("Scunthorpe").
 * Skipped unless SAFETY_WORDLIST names a UTF-8 file with one word per line (a dictionary, or a language's most frequent words):
 *
 *   SAFETY_WORDLIST=/path/words.txt ./gradlew :youtube:testDebugUnitTest --tests '*ExplicitTermsScanTest'
 *
 * Each blocked word is printed as one "BLOCKED-WORD | word" line; read them, most frequent first if the list is ordered that way.
 * Most are meant (every sex word of the language); the ones that are not are added to ExplicitTerms.ALLOWED, or their term moves
 * from `anywhere` to `words`.
 */
class ExplicitTermsScanTest {

    @Test
    fun printTheBlockedWordsOfAWordList() {
        val path = System.getenv("SAFETY_WORDLIST")
        assumeTrue("set SAFETY_WORDLIST to a word list to scan", !path.isNullOrBlank())
        val filter = ExplicitFilter.default
        val words = File(path!!).readLines(Charsets.UTF_8).map { it.trim() }.filter { it.isNotEmpty() }
        val blocked = words.filter { filter.isExplicit(it) }
        blocked.forEach { println("BLOCKED-WORD | $it") }
        println("BLOCKED-WORD | scanned ${words.size} words, ${blocked.size} blocked")
    }
}
