package com.ahmadnayfeh.shasha.youtube.safety

/**
 * Decides whether a text (a title, a channel name, a description snippet, a search query, a suggestion) is sexual, indecent
 * or suggestive. The one filter of both apps: [SafeVideoSource] applies it at the source, and the apps use [default] for what
 * they keep on the device (the feed, the history, the recent searches).
 *
 * It is a list of words, kept in [ExplicitTerms] (read its header before adding to it), compared after the text is folded
 * ([TextFolding]): spelling tricks (leetspeak, repeated or spaced-out letters, emoji or symbols between letters, accents,
 * Arabic diacritics and letter variants, CamelCase) change nothing, and the lists are matched in three ways: inside a word, as a
 * whole word, and as a pair or a phrase of words. Ordinary words that contain or sit beside a listed one are allowed ("Essex",
 * "comic strip"). Prefer blocking when unsure, but never an ordinary search: "Essex football" and "hot weather" pass.
 *
 * Lives in :youtube, which both apps already depend on, so the phone app gets it without carrying the TV's policy engine, and
 * with its own small folding instead of :policy's TextNormalizer: that one matches a parent's blocked words in a pure-JVM module
 * that knows nothing of YouTube, and this filter needs more folds than it has (accents, leetspeak, repeated and spaced-out
 * letters, symbols between letters). The Arabic folds are the same as TextNormalizer's; TextFoldingTest pins them with the same
 * samples as the policy module's KeywordNormalizationTest.
 *
 * Judging a text costs a few microseconds; the last [CACHE_SIZE] short texts are remembered (the same titles come back with
 * every list the apps rebuild). Thread safe.
 */
class ExplicitFilter internal constructor(private val index: TermIndex) {

    private val cache = object : LinkedHashMap<String, Boolean>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?) = size > CACHE_SIZE
    }

    /** True when [text] is explicit; false for null, blank and anything the lists do not catch. */
    fun isExplicit(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        val cacheable = text.length <= MAX_CACHED_LENGTH
        if (cacheable) synchronized(cache) { cache[text] }?.let { return it }
        val verdict = judge(text)
        if (cacheable) synchronized(cache) { cache[text] = verdict }
        return verdict
    }

    /** True when any of [texts] (a title, a channel name, a snippet…) is explicit. */
    fun isExplicit(vararg texts: String?): Boolean = texts.any { isExplicit(it) }

    private fun judge(text: String): Boolean = TextFolding.hasAgeMark(text) || index.matches(TextFolding.readings(text))

    companion object {
        /** The filter both apps use. Built on first use: it folds and indexes every term (tens of milliseconds); see [warmUp]. */
        val default: ExplicitFilter by lazy { ExplicitFilter(TermIndex(ExplicitTerms.rules)) }

        /** Builds [default] on a background thread now, so that the first title judged on a busy thread does not wait for it. */
        fun warmUp() {
            Thread { default }.apply {
                name = "ExplicitFilter warm-up"
                isDaemon = true
                start()
            }
        }

        private const val CACHE_SIZE = 4096
        private const val MAX_CACHED_LENGTH = 160
    }
}
