package com.ahmadnayfeh.shasha.data

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.youtube.SourceErrorKind
import com.ahmadnayfeh.shasha.youtube.SourceException
import com.ahmadnayfeh.shasha.youtube.VideoItem
import com.ahmadnayfeh.shasha.youtube.VideoSource
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * «مقترحة لك»: the suggestions beside the last videos watched are mixed in turns, kept a few hours (on disk, so a restart
 * asks nothing), asked for only when missing or old, never while a video plays, and a failure keeps what was there.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class HomeSuggestionsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private var now = NOW_MS
    private val playing = MutableStateFlow(false)
    private val asked = CopyOnWriteArrayList<String>()
    private val failing = HashSet<String>()

    /** Each watched video's suggestions: three of its own, the second shared with every other one. */
    private fun relatedOf(seed: String) = listOf(video("${seed}_1", "UCa", null), video("shared", "UCa", null), video("${seed}_3", "UCa", null))

    private val source: VideoSource = object : VideoSource by FakeVideoSource() {
        override suspend fun related(videoId: String): List<VideoItem> {
            asked += videoId
            if (videoId in failing) throw SourceException(SourceErrorKind.NETWORK, "UnknownHostException", videoId, null)
            return relatedOf(videoId)
        }
    }

    private val file: File get() = File(tmp.root, HomeSuggestions.FILE_NAME)

    private fun suggestions(on: File? = file) =
        HomeSuggestions(source, on, clock = { now }, playing = playing, lowPriority = { it() }, io = Dispatchers.Unconfined)

    @Test
    fun theRow_takesTurns_withoutRepeats_watchedVideos_orTheSeeds() {
        val kept = mapOf(
            "a" to HomeSuggestions.Kept(relatedOf("a"), now),
            "b" to HomeSuggestions.Kept(relatedOf("b"), now),
            "c" to HomeSuggestions.Kept(listOf(video("a", "UCa", null), video("c_1", "UCa", null)), now),
        )
        val row = HomeSuggestions.merge(listOf("a", "b", "c", "missing"), kept, exclude = setOf("b_3"))
        // The newest watched leads, one of each in turn; "shared" once; "a" (a seed) and "b_3" (watched) never.
        assertThat(row.map { it.videoId }).containsExactly("a_1", "b_1", "shared", "c_1", "a_3").inOrder()
        assertThat(HomeSuggestions.merge(emptyList(), kept)).isEmpty()
    }

    @Test
    fun asksOnlyForWhatIsMissingOrOlderThanAFewHours() = runBlocking<Unit> {
        val s = suggestions()
        s.refresh(listOf("a", "b", "c"))
        assertThat(asked).containsExactly("a", "b", "c").inOrder()
        assertThat(s.kept.value.keys).containsExactly("a", "b", "c")

        // Back on the home a little later: nothing is asked.
        now += HomeSuggestions.FRESH_MS - 1
        s.refresh(listOf("a", "b", "c"))
        assertThat(asked).hasSize(3)

        // A new video was watched: only it is asked for.
        s.refresh(listOf("d", "a", "b"))
        assertThat(asked).containsExactly("a", "b", "c", "d").inOrder()

        // Hours later, the old answers are asked for again.
        now += 2
        s.refresh(listOf("d", "a", "b"))
        assertThat(asked).containsExactly("a", "b", "c", "d", "a", "b").inOrder()
    }

    @Test
    fun keptOnDisk_aRestartShowsThemAndAsksNothing() = runBlocking<Unit> {
        suggestions().refresh(listOf("a", "b"))
        assertThat(file.isFile).isTrue()
        asked.clear()

        val restarted = suggestions()
        assertThat(restarted.kept.value).isEmpty() // nothing read before it is asked to: the home never waits for the file
        restarted.refresh(listOf("a", "b"))
        assertThat(asked).isEmpty()
        assertThat(restarted.kept.value.getValue("a").videos).isEqualTo(relatedOf("a"))
        assertThat(HomeSuggestions.merge(listOf("a", "b"), restarted.kept.value).map { it.videoId })
            .containsExactly("a_1", "b_1", "shared", "a_3", "b_3").inOrder()
    }

    @Test
    fun oldListsShowUntilReplaced_veryOldOnesAreDropped() = runBlocking<Unit> {
        suggestions().refresh(listOf("a"))
        now += HomeSuggestions.FRESH_MS + 1
        failing += "a" // no network on the next start
        val offline = suggestions()
        offline.refresh(listOf("a"))
        assertThat(offline.kept.value.getValue("a").videos).isEqualTo(relatedOf("a"))

        now += HomeSuggestions.SHOWN_FOR_MS
        val muchLater = suggestions()
        muchLater.refresh(listOf("a"))
        assertThat(muchLater.kept.value).isEmpty()
    }

    @Test
    fun nothingIsAskedWhileAVideoPlays() = runBlocking<Unit> {
        playing.value = true
        val s = suggestions()
        val job = CoroutineScope(Dispatchers.Default).launch { s.refresh(listOf("a")) }
        Thread.sleep(150)
        assertThat(asked).isEmpty()
        playing.value = false
        withTimeout(5_000) { s.kept.first { "a" in it } }
        job.join()
        assertThat(asked).containsExactly("a")
    }

    @Test
    fun aDamagedFile_isForgotten() = runBlocking<Unit> {
        file.writeText("{ not json")
        val s = suggestions()
        s.refresh(listOf("a"))
        assertThat(asked).containsExactly("a")
        assertThat(s.kept.value.keys).containsExactly("a")
    }

    @Test
    fun withoutAFile_onlyMemory() = runBlocking<Unit> {
        val s = suggestions(on = null)
        s.refresh(listOf("a"))
        s.refresh(listOf("a"))
        assertThat(asked).containsExactly("a")
    }

    @Test
    fun atMostTheNewestTwelveWatchedVideosAreKept() = runBlocking<Unit> {
        val s = suggestions()
        for (i in 1..15) {
            now += 1
            s.refresh(listOf("v$i"))
        }
        assertThat(s.kept.value).hasSize(HomeSuggestions.MAX_KEPT)
        assertThat(s.kept.value.keys).doesNotContain("v1")
        assertThat(s.kept.value.keys).contains("v15")
    }
}
