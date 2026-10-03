package com.ahmadnayfeh.shasha.remote

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.policy.ALLOWED_CHANNEL
import com.ahmadnayfeh.shasha.policy.clip
import com.ahmadnayfeh.shasha.youtube.safety.SafeVideoSource
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The phone page's search goes through the TV (TvRemoteController.search asks the TV's source), so it meets the built-in filter
 * there, in the owner's profile too: the page gets an empty list, which it shows as its ordinary «لا نتائج» line.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class RemoteExplicitSearchTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var env: RemoteTestEnv
    private lateinit var filtered: TvRemoteController

    @Before
    fun setUp() {
        env = remoteEnv(tmp.root)
        // AppContainer's videoSource is the extractor behind the filter: the fake stands for the extractor.
        filtered = env.newController(env.phonePin, source = SafeVideoSource(env.source))
    }

    @After
    fun tearDown() = env.close()

    @Test
    fun aSearchTheFilterHoldsBack_isAnEmptyList_andYouTubeIsNeverAsked() = runBlocking<Unit> {
        env.source.searchResults = listOf(clip("clean000001", ALLOWED_CHANNEL, title = "Cooking"))

        assertThat(filtered.search("Sexy twerking")).isEmpty()
        assertThat(filtered.search("belly dance")).isEmpty()
        assertThat(filtered.search("رقص شرقي")).isEmpty()
        assertThat(env.source.searches).isEmpty()
    }

    @Test
    fun anOrdinarySearch_comesBackWithoutTheExplicitResults() = runBlocking<Unit> {
        env.source.searchResults = listOf(
            clip("bad00000001", ALLOWED_CHANNEL, title = "Hot girls twerk"),
            clip("good0000001", ALLOWED_CHANNEL, title = "Dance tutorial for beginners"),
            clip("bad00000002", ALLOWED_CHANNEL, title = "رقص مثير للكبار"),
        )

        val items = filtered.search("dance")

        assertThat(env.source.searches).containsExactly("dance")
        assertThat(items.map { it.title }).containsExactly("Dance tutorial for beginners")
    }

    @Test
    fun withoutTheFilter_theFakeAnswersEverything_soTheEmptyListAboveIsTheFiltersDoing() = runBlocking<Unit> {
        env.source.searchResults = listOf(clip("good0000001", ALLOWED_CHANNEL, title = "Cooking"))
        assertThat(env.controller.search("Sexy twerking")).hasSize(1)
        assertThat(env.source.searches).containsExactly("Sexy twerking")
    }
}
