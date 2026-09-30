package com.ahmadnayfeh.shasha.youtube.newpipe

import com.ahmadnayfeh.shasha.youtube.MAX_SUGGESTIONS
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Test

/** What the search screen gets from YouTube's suggestions: clean, short and never a request for nothing. */
class SuggestionsTest {

    @Test
    fun cleaned_trimmedDeduplicatedAndCapped() {
        val raw = listOf(" قرآن ", "قرآن", null, "", "   ", "Quran\n kareem", "QURAN KAREEM") + (1..20).map { "x$it" }
        val clean = NewPipeVideoSource.cleanSuggestions(raw)
        assertThat(clean.take(2)).containsExactly("قرآن", "Quran kareem").inOrder()
        assertThat(clean).hasSize(MAX_SUGGESTIONS)
        assertThat(clean).doesNotContain("")
    }

    @Test
    fun blankQuery_noRequestAtAll() = runBlocking {
        // An OkHttpClient that would fail any call: nothing is asked for a blank query.
        val source = NewPipeVideoSource(OkHttpClient())
        assertThat(source.suggestions("   ", "ar")).isEmpty()
        assertThat(source.suggestions("", "en")).isEmpty()
    }
}
