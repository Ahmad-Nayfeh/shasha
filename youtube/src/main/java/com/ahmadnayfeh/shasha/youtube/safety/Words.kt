package com.ahmadnayfeh.shasha.youtube.safety

/**
 * A word with every run of one letter cut to a single letter: [key] is what is left ("kisss" is "kis") and [runs] how
 * long each run was (1, 1, 3). Two spellings of one word that differ only in how far a letter is stretched share a key;
 * the runs tell them apart where it matters ("ass" is "as" with runs 1, 2, and the plain word "as" has 1, 1).
 */
internal class Shape(word: String) {
    val key: String
    val runs: IntArray

    init {
        val letters = StringBuilder(word.length)
        val lengths = IntArray(word.length)
        var count = 0
        var i = 0
        while (i < word.length) {
            val c = word[i]
            var j = i + 1
            while (j < word.length && word[j] == c) j++
            letters.append(c)
            lengths[count++] = j - i
            i = j
        }
        key = letters.toString()
        runs = lengths.copyOf(count)
    }
}

/**
 * A listed word, folded like the words of a title. It matches a word of the text when the keys are equal and the runs of
 * letters fit ([stretchedTo]): "sexxxy" and "seeexy" are "sexy", "asssss" is "ass", "as" is not.
 * [exact]: no prefix or suffix may be taken off the text's word first (the listed word is too short or too common for that).
 */
internal class Needle(val word: String, val exact: Boolean = false) {
    private val shape = Shape(word)
    val key: String get() = shape.key
    val runs: IntArray get() = shape.runs

    /**
     * Whether letters stretched as in [textRuns] (from index [from]; the letters past [span] count one each) are this word, spelled
     * with stretched letters. The first and the last letter of the word may run on as long as they like ("sexxxxy": the match
     * simply ends inside the run). Inside the word a run must be as long as the listed word's, or three or more: "seeexy" is
     * stretching, "seexy" is not, and neither is any ordinary word that doubles a letter where the listed one has one
     * ("hoot" is not "hot", "bookkeeper" is not "bokep", "Poorna" is not "porn"). The Arabic letters of the long vowels
     * (ا و ي) are stretched by two as often as by three, and no ordinary Arabic word doubles them: "بناات" is "بنات".
     */
    fun stretchedTo(textRuns: IntArray, from: Int, span: Int): Boolean {
        val last = runs.size - 1
        for (i in 0..last) {
            val have = if (i < span) textRuns[from + i] else 1
            val need = runs[i]
            if (have < need) return false
            if (i != 0 && i != last && have != need && have < 3 && !(have == 2 && shape.key[i] in LONG_VOWELS)) return false
        }
        return true
    }

    private companion object {
        /** The Arabic letters that are stretched to lengthen a vowel (alef, waw, yeh: the others are folded onto them). */
        const val LONG_VOWELS = "اوي"
    }
}

/** The alphabet a word is written in: it decides which prefixes and suffixes may be taken off it. */
internal enum class Script { LATIN, ARABIC, OTHER }

/**
 * One way to read a word: [key] is the word itself, or what is left of it once a regular suffix (or an Arabic prefix and suffix)
 * is taken off ([stripped]). [runs] are the word's, from [from] on; the first [span] letters of [key] are the word's, anything
 * after them (an "e" put back: "danc" + "e") counts as one letter.
 */
internal class Reading(
    val key: String,
    private val runs: IntArray,
    private val from: Int,
    private val span: Int,
    val stripped: Boolean,
) {
    /** Whether this reading is [needle], however its letters are stretched (the keys are equal already): see [Needle.stretchedTo]. */
    fun fits(needle: Needle): Boolean = needle.stretchedTo(runs, from, span)
}

/**
 * A word of a text, ready to be compared with the lists: the readings of every spelling it may be a disguise of.
 *
 * - **Leetspeak**: a Latin word with digits or symbols is also read with them as the letters they imitate (0 o, 1 i or l,
 *   3 e, 4 a, 5 s, 7 t, @ a, $ s, § s, € e). Plain digits stay as they are in the plain reading, so "7ba" can be listed too.
 * - **Lookalikes**: a Cyrillic or Greek word made only of letters that look like Latin ones ("ѕех") is also read as Latin.
 * - **Affixes**: a Latin word may lose a regular English suffix (s, es, ing, ed, er, ers, with the "e" put back where it
 *   was dropped: "dancing" is "dance"); an Arabic word may lose the prefixes و ف ب ل ك ال and combinations, and the common
 *   endings (ات ين ون ان ها هم ه ك ي…), but never down to fewer than three letters. A suffix counts only when its own letters
 *   are not stretched ("brass" is not "bra" + "s", "bras" is).
 *
 * Computed on first use, per word, and never kept past the text it belongs to.
 */
internal class Word(val text: String) {

    private class Variant(val shape: Shape, val script: Script)

    private val script: Script = scriptOf(text)

    private val variants: List<Variant> by lazy(LazyThreadSafetyMode.NONE) {
        val out = ArrayList<Variant>(4)
        out += Variant(Shape(text), script)
        if (script != Script.ARABIC) {
            val latin = if (isLookalike(text)) latinized(text) else text
            if (latin != text) out += Variant(Shape(latin), Script.LATIN)
            if (script == Script.LATIN || latin != text) {
                // Digits and symbols as the letters they imitate; a 1 is an i or an l.
                if (latin.any { it in LEET }) {
                    out += Variant(Shape(leet(latin, 'i')), Script.LATIN)
                    if ('1' in latin) out += Variant(Shape(leet(latin, 'l')), Script.LATIN)
                }
            }
        }
        out
    }

    val readings: List<Reading> by lazy(LazyThreadSafetyMode.NONE) {
        val out = ArrayList<Reading>(8)
        for (v in variants) when (v.script) {
            Script.LATIN -> latinReadings(v.shape, out)
            Script.ARABIC -> arabicReadings(v.shape, out)
            Script.OTHER -> out += Reading(v.shape.key, v.shape.runs, 0, v.shape.key.length, stripped = false)
        }
        out
    }

    /** Whether this word is [needle] (or its plural, its Arabic form with the article…). */
    fun matches(needle: Needle): Boolean {
        for (r in readings) if (r.key == needle.key && !(needle.exact && r.stripped) && r.fits(needle)) return true
        return false
    }

    /** Whether [needle] is inside this word, anywhere (a listed word that no ordinary word contains). */
    fun contains(needle: Needle): Boolean {
        val key = needle.key
        val need = needle.runs
        for (v in variants) {
            val own = v.shape.key
            val runs = v.shape.runs
            var at = own.indexOf(key)
            while (at >= 0) {
                if (needle.stretchedTo(runs, at, need.size)) return true
                at = own.indexOf(key, at + 1)
            }
        }
        return false
    }

    private companion object {
        private val LEET = charArrayOf('0', '1', '3', '4', '5', '7', '@', '$', '§', '€')

        fun leet(text: String, one: Char): String {
            val out = StringBuilder(text.length)
            for (c in text) {
                out.append(
                    when (c) {
                        '0' -> 'o'
                        '1' -> one
                        '3', '€' -> 'e'
                        '4', '@' -> 'a'
                        '5', '$', '§' -> 's'
                        '7' -> 't'
                        else -> c
                    },
                )
            }
            return out.toString()
        }

        fun scriptOf(text: String): Script {
            for (c in text) {
                if (!c.isLetter()) continue
                return when {
                    c in 'a'..'z' -> Script.LATIN
                    c.code in 0x0600..0x06FF || c.code in 0x0750..0x077F || c.code in 0x08A0..0x08FF || c.code in 0xFB50..0xFEFF -> Script.ARABIC
                    else -> Script.OTHER
                }
            }
            return Script.OTHER
        }

        /** Cyrillic and Greek letters that look like Latin ones, as typed to slip past a filter. */
        private val LOOKALIKES = mapOf(
            'а' to 'a', 'в' to 'b', 'е' to 'e', 'к' to 'k', 'м' to 'm', 'н' to 'h', 'о' to 'o', 'р' to 'p', 'с' to 'c', 'т' to 't',
            'у' to 'y', 'х' to 'x', 'і' to 'i', 'ѕ' to 's', 'ј' to 'j', 'ԁ' to 'd', 'ӏ' to 'l', 'ѡ' to 'w',
            'α' to 'a', 'β' to 'b', 'ε' to 'e', 'ι' to 'i', 'κ' to 'k', 'ν' to 'v', 'ο' to 'o', 'ρ' to 'p', 'τ' to 't', 'υ' to 'u', 'χ' to 'x',
        )

        /** Letters that look Latin but no Russian word uses (Greek, Serbian, Ukrainian…): a word made of lookalikes and these is a spoof. */
        private const val NOT_RUSSIAN = "ѕјԁӏѡіαβεικνορτυχ"

        /**
         * A word that mixes Latin letters with lookalikes ("sеx" with a Cyrillic е), or is made only of lookalikes that no Russian
         * word has ("ѕех"). A Russian word made of lookalikes only ("хохота", "канва") is just a Russian word.
         */
        fun isLookalike(text: String): Boolean {
            var foreign = false
            var latin = false
            var spoof = false
            for (c in text) {
                when {
                    c in 'a'..'z' -> latin = true
                    c.isDigit() -> Unit
                    c in LOOKALIKES -> {
                        foreign = true
                        if (c in NOT_RUSSIAN) spoof = true
                    }
                    else -> return false
                }
            }
            return foreign && (latin || spoof)
        }

        fun latinized(text: String): String = String(CharArray(text.length) { LOOKALIKES[text[it]] ?: text[it] })

        // ---- English: s es ing ed er ers --------------------------------------------------------------------------------

        private val EN_SUFFIXES = listOf("s", "es", "ing", "ed", "er", "ers")

        /** After these a dropped "e" comes back: dancing, danced, dancer, dancers → dance. */
        private val EN_E_SUFFIXES = setOf("ing", "ed", "er", "ers")

        fun latinReadings(shape: Shape, out: MutableList<Reading>) {
            val key = shape.key
            val n = key.length
            out += Reading(key, shape.runs, 0, n, stripped = false)
            if (n < 4) return
            for (suffix in EN_SUFFIXES) {
                if (!key.endsWith(suffix)) continue
                val stem = n - suffix.length
                if (stem < 2 || (stem until n).any { shape.runs[it] != 1 }) continue
                out += Reading(key.substring(0, stem), shape.runs, 0, stem, stripped = true)
                // The dropped "e" is put back only where the stem's last letter is not doubled: "dancing" is "dance", "rapped" is not "rape".
                if (suffix in EN_E_SUFFIXES && shape.runs[stem - 1] == 1) {
                    out += Reading(key.substring(0, stem) + "e", shape.runs, 0, stem, stripped = true)
                }
            }
        }

        // ---- Arabic: ال و ف ب ل ك … and ات ين ون ان ها هم ه ك ي … -----------------------------------------------------

        /** Longest first. Written after TextFolding: ة is ه, أ is ا. */
        private val AR_PREFIXES = listOf("وال", "فال", "بال", "كال", "ولل", "فلل", "لل", "ال", "وب", "ول", "وك", "فب", "فل", "فك", "و", "ف", "ب", "ل", "ك")
        private val AR_SUFFIXES = listOf("ات", "ين", "ون", "ان", "ها", "هم", "هن", "كم", "نا", "تين", "يات", "يه", "يا", "ه", "ك", "ي")
        private const val AR_MIN_STEM = 3

        fun arabicReadings(shape: Shape, out: MutableList<Reading>) {
            val key = shape.key
            val n = key.length
            out += Reading(key, shape.runs, 0, n, stripped = false)
            val starts = ArrayList<Int>(4).also { it += 0 }
            for (p in AR_PREFIXES) if (key.startsWith(p) && n - p.length >= AR_MIN_STEM) starts += p.length
            for (start in starts) {
                val ends = ArrayList<Int>(4).also { it += n }
                for (s in AR_SUFFIXES) if (key.endsWith(s) && n - s.length - start >= AR_MIN_STEM) ends += n - s.length
                for (end in ends) {
                    if (start == 0 && end == n) continue
                    out += Reading(key.substring(start, end), shape.runs, start, end - start, stripped = true)
                }
            }
        }
    }
}
