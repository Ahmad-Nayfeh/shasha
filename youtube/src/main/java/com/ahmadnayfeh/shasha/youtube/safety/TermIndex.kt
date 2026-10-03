package com.ahmadnayfeh.shasha.youtube.safety

/**
 * The lists of [ExplicitTerms] in the shape the filter reads them. Every entry is a string as people write it; the
 * index folds it like a title ([TextFolding.termWords]) so the two always meet.
 *
 * @property anywhere explicit wherever they appear inside a word: no ordinary word contains them.
 * @property words explicit as a whole word, with its regular plural, -ing, -ed and -er forms (and, in Arabic, the prefixes and
 *   suffixes of [Word]).
 * @property exact like [words], but exactly as written: no prefix, no suffix (an Arabic word of two or three letters that
 *   would otherwise meet many others).
 * @property phrases explicit as a run of two or more whole words; written together ("lapdance") they are explicit too.
 * @property gates explicit only beside a companion: see [Gate].
 * @property alone explicit only as the whole text, a query typed alone ("dick"): in a longer title they are names or ordinary words.
 * @property allowed ordinary words and phrases that the lists above would otherwise catch ("Essex", "naked eye"): the words
 *   of an allowed phrase are not judged at all.
 */
internal class Rules(
    val anywhere: List<String>,
    val words: List<String>,
    val exact: List<String>,
    val phrases: List<String>,
    val gates: List<Gate>,
    val alone: List<String>,
    val allowed: List<String>,
)

/**
 * A word that is ordinary alone and explicit beside certain others: "hot" (not "hot weather", but "hot girls"), "kiss", "strip",
 * Arabic "ساخنة". One of [triggers] with one of [companions] at most [before] words before it or [after] words after it (counting
 * the words between). The companions are ordinary words too: say it with the nearest ones only where a farther one would
 * catch ordinary titles ("Hot Wheels videos" has "video" two words after "hot").
 */
internal class Gate(val triggers: List<String>, val companions: List<String>, val before: Int = 0, val after: Int = 1)

/**
 * [Rules] folded and indexed by the first word of each entry, so that judging a text looks up its words instead of walking
 * the lists. Immutable once built: safe to share between threads.
 */
internal class TermIndex(rules: Rules) {

    private class Entry(val needles: List<Needle>, val alone: Boolean = false)

    private class GateEntry(val trigger: Needle, val companions: List<Needle>, val before: Int, val after: Int)

    private val anywhere: List<Needle> = rules.anywhere.map { Needle(single(it)) }
    private val blocks = HashMap<String, MutableList<Entry>>()
    private val allows = HashMap<String, MutableList<Entry>>()
    private val gates = HashMap<String, MutableList<GateEntry>>()

    init {
        for (term in rules.words) addBlock(listOf(Needle(single(term))))
        for (term in rules.exact) addBlock(listOf(Needle(single(term), exact = true)))
        for (term in rules.alone) addBlock(listOf(Needle(single(term), exact = true)), alone = true)
        for (phrase in rules.phrases) {
            val words = TextFolding.termWords(phrase)
            require(words.size >= 2) { "A phrase has two words or more: \"$phrase\"" }
            addBlock(words.map { Needle(it) })
            addBlock(listOf(Needle(words.joinToString(""))))
        }
        for (term in rules.allowed) {
            val words = TextFolding.termWords(term)
            require(words.isNotEmpty()) { "Nothing left of the allowed term \"$term\"" }
            add(allows, words.map { Needle(it) })
            if (words.size > 1) add(allows, listOf(Needle(words.joinToString(""))))
        }
        for (gate in rules.gates) {
            val companions = gate.companions.map { Needle(single(it)) }
            for (trigger in gate.triggers) {
                val needle = Needle(single(trigger))
                gates.getOrPut(needle.key) { ArrayList() } += GateEntry(needle, companions, gate.before, gate.after)
                // Written together, a pair is one word: "hotgirls", "frenchkiss".
                if (needle.word.all { it in 'a'..'z' }) {
                    for (c in companions) {
                        if (gate.after >= 1) addBlock(listOf(Needle(needle.word + c.word)))
                        if (gate.before >= 1) addBlock(listOf(Needle(c.word + needle.word)))
                    }
                }
            }
        }
    }

    /** Whether any reading of the text (see [TextFolding.readings]) holds a listed word, phrase or pair. */
    fun matches(readings: List<List<String>>): Boolean {
        for (reading in readings) if (matchesReading(reading.map(::Word))) return true
        return false
    }

    private fun matchesReading(words: List<Word>): Boolean {
        val masked = BooleanArray(words.size)
        for (i in words.indices) {
            for (r in words[i].readings) {
                val list = allows[r.key] ?: continue
                for (entry in list) {
                    if (r.fits(entry.needles[0]) && restMatches(entry, words, i, null)) {
                        for (k in entry.needles.indices) masked[i + k] = true
                    }
                }
            }
        }
        for (i in words.indices) {
            if (masked[i]) continue
            val word = words[i]
            for (needle in anywhere) if (word.contains(needle)) return true
            for (r in word.readings) {
                blocks[r.key]?.let { list ->
                    for (entry in list) {
                        val first = entry.needles[0]
                        if (first.exact && r.stripped || !r.fits(first)) continue
                        if (entry.alone && words.size != 1) continue
                        if (restMatches(entry, words, i, masked)) return true
                    }
                }
                gates[r.key]?.let { list ->
                    for (g in list) {
                        if (g.trigger.exact && r.stripped || !r.fits(g.trigger)) continue
                        if (companionNear(g, words, i, masked)) return true
                    }
                }
            }
        }
        return false
    }

    /** The words after the first one match the rest of [entry]'s words, none of them hidden by an allowed phrase. */
    private fun restMatches(entry: Entry, words: List<Word>, i: Int, masked: BooleanArray?): Boolean {
        if (i + entry.needles.size > words.size) return false
        for (k in 1 until entry.needles.size) {
            if (masked != null && masked[i + k]) return false
            if (!words[i + k].matches(entry.needles[k])) return false
        }
        return true
    }

    private fun companionNear(gate: GateEntry, words: List<Word>, i: Int, masked: BooleanArray): Boolean {
        val from = maxOf(0, i - gate.before)
        val to = minOf(words.size - 1, i + gate.after)
        for (j in from..to) {
            if (j == i || masked[j]) continue
            for (c in gate.companions) if (words[j].matches(c)) return true
        }
        return false
    }

    private fun addBlock(needles: List<Needle>, alone: Boolean = false) = add(blocks, needles, alone)

    private fun add(index: HashMap<String, MutableList<Entry>>, needles: List<Needle>, alone: Boolean = false) {
        index.getOrPut(needles[0].key) { ArrayList() } += Entry(needles, alone)
    }

    private fun single(term: String): String {
        val words = TextFolding.termWords(term)
        require(words.size == 1) { "Not a single word: \"$term\"" }
        return words[0]
    }
}
