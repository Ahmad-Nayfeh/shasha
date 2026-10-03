package com.ahmadnayfeh.shasha.youtube.safety

import java.text.Normalizer

/**
 * Turns a title, a channel name or a search query into the words [ExplicitFilter] compares, undoing every cheap way to
 * disguise a spelling while it still reads the same (or nearly) on screen. What the folding does, in order:
 *
 * - Unicode compatibility decomposition (NFKD): full-width and styled Latin ("ｓｅｘ", "𝐬𝐞𝐱", "ⓢⓔⓧ"), Arabic presentation forms and
 *   ligatures become plain letters, and every accent, hamza and tashkeel is split off as a mark and dropped. So "é" is "e",
 *   "أ إ آ" are "ا", "ؤ" is "و" and "ئ" is "ي". The marks without width of every kind (tatweel, zero-width characters, direction
 *   marks, variation selectors, soft hyphens, tag characters) are dropped as well, exactly as the policy module's
 *   TextNormalizer does for the parent's blocked words (that one is not reused: see the note on [ExplicitFilter]).
 * - Lowercase, locale independent; digits of every script become ASCII digits; letters NFKD does not split (ø, ł, đ, ß, æ, ı…)
 *   become the Latin letters they imitate; ة → ه, ى → ي and the Persian and Urdu forms of ك ي ه ب ج ز ت د ر ن fold onto the
 *   Arabic ones (TextNormalizer's own folds, a little further), so one list serves all three languages.
 * - Regional-indicator and negative-squared letters (🇸 🇪 🇽, 🅢 🅔 🅧) are letters.
 * - Words end at spaces, punctuation and symbols (so an emoji, a dot or an apostrophe between two words separates them:
 *   "who're" is "who" and "re", never "whore").
 *
 * What [readings] gives besides the plain reading, when the text calls for it:
 * - **CamelCase split**: "SexOnTheBeat" is also read as "Sex On The Beat" (every lower-case to upper-case step starts a new
 *   word). It is a second reading, never a replacement: "SeXy" must still be "sexy".
 * - **Symbols dropped**: "se🔥xy", "s*e*x" and "p~o~r~n" are also read with every decorative symbol removed, so letters
 *   scattered with emoji or symbols join again.
 *
 * And in every reading, **single letters that stand alone are joined**: "s e x", "s.e.x", "p-o-r-n", "س ك س" are one word
 * each (two or more single letters in a row, at least two of them letters; a lone "A" or "I" stays as it is).
 */
internal object TextFolding {

    /** Only this much of a text is judged: titles are short, and a pasted page must not cost a second. */
    const val MAX_CHARS = 600

    /**
     * The words of [raw], once per way of reading it ([the plain reading first][plainReading]); a reading is a list of words.
     * Empty when nothing is left of the text.
     */
    fun readings(raw: String): List<List<String>> {
        val text = decompose(raw)
        val plain = words(text, camel = false, dropSymbols = false, join = true)
        if (plain.isEmpty()) return emptyList()
        val out = ArrayList<List<String>>(3)
        out += plain
        if (hasCamelStep(text)) words(text, camel = true, dropSymbols = false, join = true).takeIf { it != plain }?.let { out += it }
        if (hasDecoration(text)) words(text, camel = false, dropSymbols = true, join = true).takeIf { it != plain }?.let { out += it }
        return out
    }

    /** True when [raw] marks itself for adults: "18+", "+18", "21+", "18 plus" (a version such as "1.18+" or "iOS 18+" is not). */
    fun hasAgeMark(raw: String): Boolean {
        val text = decompose(raw)
        if ('+' !in text && !text.contains("plus", ignoreCase = true)) return false
        return AGE_MARK.containsMatchIn(text.lowercase(java.util.Locale.ROOT))
    }

    /** The words of a term of the lists, folded like a title's words but not joined, not split by case (a term is written the way it is meant). */
    fun termWords(term: String): List<String> = words(decompose(term), camel = false, dropSymbols = false, join = false)

    // ---- Decomposition -------------------------------------------------------------------------------------------------

    private fun decompose(raw: String): String {
        val cut = if (raw.length > MAX_CHARS) raw.substring(0, MAX_CHARS) else raw
        // ASCII is its own NFKD (and has only ASCII digits): most titles and every English term skip the work.
        if (cut.all { it.code < 128 }) return cut
        return asciiDigits(Normalizer.normalize(cut, Normalizer.Form.NFKD))
    }

    /** Arabic-Indic, Persian, Devanagari… digits are ASCII digits (NFKD leaves them). */
    private fun asciiDigits(text: String): String {
        if (text.none { it > '9' && Character.isDigit(it) }) return text
        val out = StringBuilder(text.length)
        for (c in text) out.append(if (c > '9' && Character.isDigit(c)) ('0'.code + Character.digit(c, 10)).toChar() else c)
        return out.toString()
    }

    private val AGE_MARK = Regex(
        "(?<![0-9.,])(?<!ios )(?<!android )(?<!version )(?<!v)(?:18|21)\\s?\\+|\\+\\s?(?:18|21)(?![0-9])|(?<![0-9.,])(?:18|21)\\s?plus\\b",
    )

    private fun hasCamelStep(text: String): Boolean {
        var previousLower = false
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            if (previousLower && Character.isUpperCase(cp)) return true
            if (isIgnorable(cp)) continue
            previousLower = Character.isLowerCase(cp)
        }
        return false
    }

    private fun hasDecoration(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            if (isDecoration(cp)) return true
        }
        return false
    }

    // ---- Words ------------------------------------------------------------------------------------------------------------

    private fun words(text: String, camel: Boolean, dropSymbols: Boolean, join: Boolean): List<String> {
        val words = ArrayList<String>()
        val cur = StringBuilder()
        var previous = -1 // the last code point added to [cur], in its own case; -1 when [cur] is empty
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            when {
                isSpace(cp) -> {
                    flush(words, cur)
                    previous = -1
                }
                isIgnorable(cp) -> Unit
                Character.isLetter(cp) -> {
                    if (camel && previous != -1 && Character.isLowerCase(previous) && Character.isUpperCase(cp)) flush(words, cur)
                    cur.append(foldLetter(Character.toLowerCase(cp)))
                    previous = cp
                }
                Character.isDigit(cp) -> {
                    cur.append(('0'.code + Character.digit(cp, 10)).toChar())
                    previous = cp
                }
                letterOfSymbol(cp) != 0 -> {
                    cur.append(letterOfSymbol(cp).toChar())
                    previous = cp
                }
                cp in LEET_SYMBOLS -> {
                    cur.append(cp.toChar())
                    previous = cp
                }
                dropSymbols && isDecoration(cp) -> Unit
                else -> {
                    flush(words, cur)
                    previous = -1
                }
            }
        }
        flush(words, cur)
        words.removeAll { w -> w.none { it.isLetterOrDigit() } } // "$" and "@" on their own
        return if (join) joinSingles(words) else words
    }

    private fun flush(words: MutableList<String>, cur: StringBuilder) {
        if (cur.isNotEmpty()) {
            words += cur.toString()
            cur.setLength(0)
        }
    }

    /** Two or more single letters in a row ("s e x") are one word, if at least two of them are letters ("1 2 3" is not a word). */
    private fun joinSingles(words: List<String>): List<String> {
        var out: ArrayList<String>? = null
        var i = 0
        while (i < words.size) {
            var j = i
            var letters = 0
            while (j < words.size && words[j].length == 1 && words[j][0].isLetterOrDigit()) {
                if (words[j][0].isLetter()) letters++
                j++
            }
            if (j - i >= 2 && letters >= 2) {
                val joined = StringBuilder()
                for (k in i until j) joined.append(words[k])
                if (out == null) out = ArrayList<String>(words.size).also { it.addAll(words.subList(0, i)) }
                out += joined.toString()
                i = j
            } else {
                out?.add(words[i])
                i++
            }
        }
        return out ?: words
    }

    // ---- Characters ------------------------------------------------------------------------------------------------------

    private fun isSpace(cp: Int): Boolean = Character.isWhitespace(cp) || Character.isSpaceChar(cp)

    /** Nothing on screen (marks, format and control characters, tatweel, fillers): dropped without ending a word. Same set as TextNormalizer's. */
    private fun isIgnorable(cp: Int): Boolean = when (Character.getType(cp).toByte()) {
        Character.NON_SPACING_MARK, Character.ENCLOSING_MARK, Character.FORMAT, Character.CONTROL -> true
        else -> cp == TATWEEL || cp in HANGUL_FILLERS || cp in 0x2060..0x206F || cp in 0xFFF0..0xFFF8 || cp in 0xE0000..0xE0FFF
    }

    /** Emoji and other pictures, math and currency signs, and the marks people scatter inside a word (* and the bullets). */
    private fun isDecoration(cp: Int): Boolean = when (Character.getType(cp).toByte()) {
        Character.OTHER_SYMBOL, Character.MODIFIER_SYMBOL, Character.MATH_SYMBOL, Character.CURRENCY_SYMBOL -> cp !in LEET_SYMBOLS
        else -> cp == '*'.code || cp in BULLETS
    }

    /** The letter a symbol is drawn as (regional indicators and negative circled or squared letters), or 0. */
    private fun letterOfSymbol(cp: Int): Int = when (cp) {
        in 0x1F1E6..0x1F1FF -> 'a'.code + (cp - 0x1F1E6)
        in 0x1F150..0x1F169 -> 'a'.code + (cp - 0x1F150)
        in 0x1F170..0x1F189 -> 'a'.code + (cp - 0x1F170)
        else -> 0
    }

    /** Lower-case letter → the letter it is read as: Latin letters NFKD leaves whole, then Arabic, Persian and Urdu forms. */
    private fun foldLetter(cp: Int): String = when (cp) {
        'ø'.code -> "o"
        'đ'.code, 'ð'.code -> "d"
        'ł'.code -> "l"
        'ħ'.code -> "h"
        'ı'.code -> "i"
        'ŧ'.code -> "t"
        'ß'.code -> "ss"
        'æ'.code -> "ae"
        'œ'.code -> "oe"
        'þ'.code -> "th"
        'ٱ'.code -> "ا" // alef wasla
        'ى'.code, 'ی'.code, 'ے'.code, 'ې'.code -> "ي" // alef maksura, Persian and Urdu yeh
        'ة'.code, 'ہ'.code, 'ھ'.code, 'ە'.code -> "ه" // teh marbuta, the Urdu hehs
        'ک'.code, 'گ'.code, 'ڪ'.code -> "ك" // Persian kaf, gaf
        'پ'.code -> "ب"
        'چ'.code -> "ج"
        'ژ'.code -> "ز"
        'ٹ'.code -> "ت"
        'ڈ'.code -> "د"
        'ڑ'.code -> "ر"
        'ں'.code -> "ن"
        else -> String(Character.toChars(cp))
    }

    private const val TATWEEL = 0x0640

    /** •  ·  ‧  ・ */
    private val BULLETS = setOf(0x2022, 0x00B7, 0x2027, 0x30FB)

    /** Letters by category but drawn as nothing: HANGUL FILLER after NFKD, and the jamo fillers. */
    private val HANGUL_FILLERS = setOf(0x115F, 0x1160, 0x3164, 0xFFA0)

    /** Symbols read as the letter they imitate in a word ([LeetFolding]), so they belong to words. */
    internal val LEET_SYMBOLS = setOf('@'.code, '$'.code, '§'.code, '€'.code)

}
