package com.ahmadnayfeh.shasha.policy

import java.text.Normalizer
import java.util.Locale

/**
 * Folds text so that spelling variants compare equal: used for blocked title keywords and for the
 * adult exit challenge phrase.
 *
 * - NFKC first, so Arabic presentation forms (ﻻ, ﷲ…), full-width and styled Latin become plain
 *   letters, and a letter followed by a combining mark becomes one precomposed letter (é, أ, آ).
 * - Lowercases (locale independent).
 * - Removes, per code point (so characters outside the BMP count too), everything that has no
 *   width of its own and could split a keyword while the title looks the same on screen: every
 *   non-spacing or enclosing mark (all tashkeel/harakat of every Arabic block, Quranic marks,
 *   variation selectors, the combining grapheme joiner), format characters (ZWJ, RLM, tag
 *   characters…), control characters, the other default-ignorable code points (Hangul fillers,
 *   reserved ones) and tatweel. Scripts whose words differ only by such marks fold together, which
 *   can only make a blocked keyword match more often.
 * - أ إ آ ٱ → ا, ى → ي, ة → ه, ؤ → و, ئ → ي; Persian ی → ي and ک → ك.
 * - Collapses whitespace runs to one space, trimmed.
 */
object TextNormalizer {

    fun normalize(text: String): String {
        val composed = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        val out = StringBuilder(composed.length)
        var pendingSpace = false
        var i = 0
        while (i < composed.length) {
            val cp = composed.codePointAt(i)
            i += Character.charCount(cp)
            when {
                isSpace(cp) -> pendingSpace = out.isNotEmpty()
                isIgnorable(cp) -> Unit
                else -> {
                    if (pendingSpace) out.append(' ')
                    pendingSpace = false
                    out.appendCodePoint(fold(cp))
                }
            }
        }
        return out.toString()
    }

    /** True when [keyword] (non-blank after normalization) appears anywhere in [text]. */
    fun containsKeyword(text: String, keyword: String): Boolean {
        val needle = normalize(keyword)
        return needle.isNotEmpty() && normalize(text).contains(needle)
    }

    /** Returns the first keyword found in [text], or null. Blank keywords are ignored. */
    fun firstMatch(text: String, keywords: List<String>): String? = KeywordMatcher(keywords).firstMatch(text)

    private fun isSpace(cp: Int): Boolean = Character.isWhitespace(cp) || Character.isSpaceChar(cp)

    private fun isIgnorable(cp: Int): Boolean = when (Character.getType(cp).toByte()) {
        Character.NON_SPACING_MARK, Character.ENCLOSING_MARK, Character.FORMAT, Character.CONTROL -> true
        else -> cp == TATWEEL || cp in HANGUL_FILLERS || isReservedDefaultIgnorable(cp)
    }

    // Default_Ignorable_Code_Point ranges that hold unassigned code points (rendered as nothing).
    private fun isReservedDefaultIgnorable(cp: Int): Boolean =
        cp in 0x2060..0x206F || cp in 0xFFF0..0xFFF8 || cp in 0xE0000..0xE0FFF

    private fun fold(cp: Int): Int = when (cp) {
        'أ'.code, 'إ'.code, 'آ'.code, 'ٱ'.code -> 'ا'.code // أ إ آ ٱ → ا
        'ى'.code -> 'ي'.code // ى → ي
        'ة'.code -> 'ه'.code // ة → ه
        'ؤ'.code -> 'و'.code // ؤ → و
        'ئ'.code -> 'ي'.code // ئ → ي
        'ی'.code -> 'ي'.code // Persian yeh ی → ي
        'ک'.code -> 'ك'.code // Persian kaf ک → ك
        else -> cp
    }

    private const val TATWEEL = 0x0640

    /** Letters by category, but drawn as nothing (HANGUL FILLER after NFKC, and the jamo fillers). */
    private val HANGUL_FILLERS = setOf(0x115F, 0x1160, 0x3164, 0xFFA0)
}

/**
 * Blocked keywords normalized once, for filtering a whole list of titles. [keywords] keeps the
 * original spelling, which [firstMatch] returns.
 */
class KeywordMatcher(val keywords: List<String>) {

    private val needles: List<Pair<String, String>> = keywords.mapNotNull { keyword ->
        TextNormalizer.normalize(keyword).takeIf { it.isNotEmpty() }?.let { keyword to it }
    }

    /** The first keyword found in [text], or null. Blank keywords never match. */
    fun firstMatch(text: String): String? {
        if (needles.isEmpty()) return null
        val haystack = TextNormalizer.normalize(text)
        return needles.firstOrNull { (_, needle) -> haystack.contains(needle) }?.first
    }
}
