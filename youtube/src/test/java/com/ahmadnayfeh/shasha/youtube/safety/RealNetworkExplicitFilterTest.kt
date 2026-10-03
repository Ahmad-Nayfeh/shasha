package com.ahmadnayfeh.shasha.youtube.safety

import com.ahmadnayfeh.shasha.youtube.ChannelItem
import com.ahmadnayfeh.shasha.youtube.PageKey
import com.ahmadnayfeh.shasha.youtube.PlaylistItem
import com.ahmadnayfeh.shasha.youtube.SourceItem
import com.ahmadnayfeh.shasha.youtube.VideoItem
import com.ahmadnayfeh.shasha.youtube.VideoSource
import com.ahmadnayfeh.shasha.youtube.newpipe.NewPipeVideoSource
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * The built-in filter against the real YouTube. Skipped unless run with:
 *   ./gradlew :youtube:testDebugUnitTest --tests '*RealNetworkExplicitFilterTest' -Dyoutube.realNetwork=true
 * Prints one "REAL-RESULT" line per search (how many results the extractor gave, how many are left after [SafeVideoSource]) and
 * a "LIVE-FILTER" line for every title still shown, for a person to read: a list of words cannot tell whether a thumbnail is decent.
 *
 * Two kinds of search are run. The owner's own ("twerking", "sexy dance", "belly dance", "رقص شرقي") are held back before any
 * request, so nothing is left of them; the extractor is asked for them too, only to show how many results were avoided and how
 * many of those the title check would have caught alone. The others are ordinary searches that sit next to them ("dance",
 * "رقص", "gym girls", "yoga pants"): they reach YouTube, and what is left of them is what the viewer would see.
 */
class RealNetworkExplicitFilterTest {

    companion object {
        private lateinit var raw: VideoSource
        private lateinit var safe: VideoSource

        @BeforeClass
        @JvmStatic
        fun setUp() {
            assumeTrue("real-network tests disabled", System.getProperty("youtube.realNetwork") == "true")
            raw = NewPipeVideoSource(OkHttpClient())
            safe = SafeVideoSource(raw)
        }

        private const val PAGES = 3

        private fun report(query: String, outcome: String) = println("REAL-RESULT | explicit filter: $query | $outcome")

        private fun shown(query: String, label: String) = println("LIVE-FILTER | $query | $label")
    }

    /** The first [PAGES] pages of a search, until one fails (a failure ends the list: the numbers are of what arrived). */
    private suspend fun results(source: VideoSource, query: String): List<SourceItem> {
        val all = ArrayList<SourceItem>()
        var page: PageKey? = null
        repeat(PAGES) {
            val answer = try {
                source.search(query, page = page)
            } catch (e: Exception) {
                report(query, "a page failed: ${e.javaClass.simpleName}: ${e.message?.take(80)}")
                return all
            }
            all += answer.items
            page = answer.next ?: return all
        }
        return all
    }

    private fun SourceItem.label(): String = when (this) {
        is VideoItem -> "video | $title | ${channelName.orEmpty()}"
        is ChannelItem -> "channel | $name"
        is PlaylistItem -> "playlist | $title | ${channelName.orEmpty()}"
    }

    private fun SourceItem.texts(): List<String?> = when (this) {
        is VideoItem -> listOf(title, channelName, snippet)
        is ChannelItem -> listOf(name, snippet)
        is PlaylistItem -> listOf(title, channelName)
    }

    @Test
    fun theOwnersSearches_haveNothingLeft() = runBlocking<Unit> {
        val filter = ExplicitFilter.default
        for (query in listOf("twerking", "sexy dance", "belly dance", "رقص شرقي", "Sexy twerking")) {
            val answer = safe.search(query)
            assertThat(answer.items).isEmpty()
            assertThat(answer.next).isNull()

            // What YouTube would have answered, and how much of it the title check would have caught without the query check.
            val asked = results(raw, query)
            val caught = asked.count { item -> filter.isExplicit(*item.texts().toTypedArray()) }
            report(query, "held back at the query: 0 left; without that, the extractor gave ${asked.size} (the title check alone catches $caught)")
        }
    }

    @Test
    fun ordinarySearchesNextToThem_loseTheirExplicitResults_andWhatIsLeftIsPrinted() = runBlocking<Unit> {
        val filter = ExplicitFilter.default
        val queries = listOf("dance", "dance challenge", "رقص", "hip hop dance", "tiktok dance", "gym girls", "yoga pants", "swimsuit", "fashion model", "ballet")
        for (query in queries) {
            val asked = results(raw, query)
            val held = asked.count { item -> filter.isExplicit(*item.texts().toTypedArray()) }
            val left = results(safe, query)
            // Two requests, so the lists can differ a little (YouTube does not answer the same twice).
            report(query, "extractor ${asked.size} of which the filter holds back $held; through SafeVideoSource ${left.size} shown")
            left.forEach { shown(query, it.label()) }
            for (item in left) {
                assertWithMessage("$query: ${item.label()}").that(filter.isExplicit(*item.texts().toTypedArray())).isFalse()
            }
        }
    }
}
