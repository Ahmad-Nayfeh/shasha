package com.ahmadnayfeh.shasha.data

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.data.HomeSuggestions.Suggested
import com.ahmadnayfeh.shasha.youtube.VideoItem
import com.ahmadnayfeh.shasha.youtube.VideoSource
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.ConcurrentHashMap

/**
 * «مقترحة لك» stay put (the owner's report: moving along the home's rows brought new suggestions). The list a home shows changes
 * only on «تحديث», in a new session (a cold start, the app opened again, another profile) and after a video was played; what comes
 * meanwhile is added at the end, never in place of a card on screen.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class StableSuggestionsTest {
    private val rounds = mutableListOf<Long>()

    private fun stable() = StableSuggestions().apply {
        requestRound = { maxAge -> rounds += maxAge; rounds.size.toLong() }
    }

    private fun v(vararg ids: String): List<VideoItem> = ids.map { video(it, "UC$it", null) }

    private fun s(videos: List<VideoItem>, shorts: List<VideoItem> = emptyList(), head: String? = "w@1", settled: Long = 0) =
        Suggested(videos, shorts, settled = settled, head = head)

    private fun Suggested.ids() = videos.map { it.videoId }

    @Test
    fun theFirstList_isTheRankingAsItIs() {
        assertThat(stable().next("p", s(v("a", "b", "c"))).ids()).containsExactly("a", "b", "c").inOrder()
        assertThat(rounds).isEmpty()
    }

    @Test
    fun aNewRanking_whileTheViewerMovesAlong_onlyAddsAtTheEnd() {
        val st = stable()
        st.next("p", s(v("a", "b", "c")))
        // A list came in: the ranking now puts new suggestions first and reorders the old ones.
        val after = st.next("p", s(v("x", "c", "y", "a", "b")))
        assertThat(after.ids()).containsExactly("a", "b", "c", "x", "y").inOrder()
        // The same again, and nothing new: nothing moves.
        assertThat(st.next("p", s(v("y", "x", "b", "a", "c"))).ids()).containsExactly("a", "b", "c", "x", "y").inOrder()
        assertThat(rounds).isEmpty()
    }

    @Test
    fun theShortsRow_staysPutToo() {
        val st = stable()
        st.next("p", s(v("a"), shorts = v("s1", "s2")))
        assertThat(st.next("p", s(v("a"), shorts = v("s3", "s2", "s1"))).shorts.map { it.videoId }).containsExactly("s1", "s2", "s3").inOrder()
    }

    @Test
    fun afterAVideoWasPlayed_theRankingReplacesTheList_onceItsRoundHasIt() {
        val st = stable()
        st.next("p", s(v("a", "b"), head = "w@1"))
        // Back from the player: the history's newest viewing is new. One round is asked for; until it has the played
        // video's suggestions the list stays (with additions at the end).
        val waiting = st.next("p", s(v("n", "a", "b"), head = "played@2", settled = 0))
        assertThat(rounds).containsExactly(HomeSuggestions.FRESH_MS)
        assertThat(waiting.ids()).containsExactly("a", "b", "n").inOrder()
        // The round has it: the new ranking, about what was just watched.
        assertThat(st.next("p", s(v("n", "m", "a"), head = "played@2", settled = 1)).ids()).containsExactly("n", "m", "a").inOrder()
        // And then it stays put again.
        assertThat(st.next("p", s(v("z", "n", "m", "a"), head = "played@2", settled = 1)).ids()).containsExactly("n", "m", "a", "z").inOrder()
        assertThat(rounds).hasSize(1)
    }

    @Test
    fun refresh_showsWhatTheRowHadNotShownYet_andAsksForOldListsAgain() {
        val st = stable()
        val ids = (1..40).map { "v$it" }.toTypedArray()
        st.next("p", s(v(*ids)))
        st.refresh()
        assertThat(rounds).containsExactly(HomeSuggestions.REFRESH_MAX_AGE_MS)
        val refreshed = st.next("p", s(v(*ids))).ids()
        // The 20 cards that were on screen go to the end; the row starts with ones not seen.
        assertThat(refreshed.take(20)).isEqualTo(ids.drop(20))
        assertThat(refreshed.drop(20)).isEqualTo(ids.take(20))
        // Then stable again.
        assertThat(st.next("p", s(v(*ids))).ids()).isEqualTo(refreshed)
    }

    @Test
    fun anotherProfile_hasItsOwnList_andANewSessionStartsOver() {
        val st = stable()
        st.next("adult", s(v("a", "b")))
        assertThat(st.next("child", s(v("k", "a"))).ids()).containsExactly("k", "a").inOrder()
        assertThat(st.next("child", s(v("j", "k", "a"))).ids()).containsExactly("k", "a", "j").inOrder()
        // Back to the adult: its own list, the ranking as it is now (switching profiles is a new session).
        assertThat(st.next("adult", s(v("b", "c", "a"))).ids()).containsExactly("b", "c", "a").inOrder()
        // The app opened again.
        st.reset()
        assertThat(st.next("adult", s(v("c", "a", "b"))).ids()).containsExactly("c", "a", "b").inOrder()
    }

    @Test
    fun aListThatBecomesEmpty_isEmptyHereToo() {
        val st = stable()
        st.next("p", s(v("a", "b"), shorts = v("s1")))
        // Shorts turned off.
        val noShorts = st.next("p", s(v("a", "b")))
        assertThat(noShorts.shorts).isEmpty()
        // The history cleared (no viewing at all any more): nothing to suggest.
        assertThat(st.next("p", s(emptyList(), head = null)).videos).isEmpty()
    }

    /** Through [HomeSuggestions]: the lists that come in one by one while the home is on screen never reorder the row. */
    @Test
    fun withTheRealRounds_listsComingInOneByOne_neverReorderTheRow() = runBlocking<Unit> {
        val gates = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
        val source: VideoSource = object : VideoSource by FakeVideoSource() {
            override suspend fun related(videoId: String): List<VideoItem> {
                gates.getOrPut(videoId) { CompletableDeferred() }.await()
                return listOf(video("${videoId}_1", "UC${videoId}1", null), video("shared", "UCshared", null), video("${videoId}_2", "UC${videoId}2", null))
            }
        }
        val suggestions = HomeSuggestions(source, null, clock = { NOW_MS }, playing = MutableStateFlow(false), lowPriority = { it() }, io = Dispatchers.Unconfined, pauseMs = 0)
        val history = MutableStateFlow(listOf(HomeSuggestions.Watched("a", NOW_MS - HOUR_MS, "UCa"), HomeSuggestions.Watched("b", NOW_MS - DAY_MS, "UCb")))
        val shorts = MutableStateFlow(false)
        val shown = MutableStateFlow(Suggested())
        val job = launch(Dispatchers.Default) {
            launch { suggestions.keepFresh(history, shorts) }
            suggestions.stable.stable("p", suggestions.suggested(history, shorts)).collect { shown.value = it }
        }
        suspend fun until(check: (Suggested) -> Boolean) = withTimeout(5_000) { shown.first(check) }
        fun open(id: String) = gates.getOrPut(id) { CompletableDeferred() }.complete(Unit)

        open("a")
        val first = until { it.videos.isNotEmpty() }.videos.map { it.videoId }
        assertThat(first).containsExactly("a_1", "shared", "a_2").inOrder()
        // "b"'s list comes in: the ranking would put "shared" first now (it is beside both); the row keeps its order.
        open("b")
        val after = until { it.videos.size == 5 }.videos.map { it.videoId }
        assertThat(after).containsExactly("a_1", "shared", "a_2", "b_1", "b_2").inOrder()

        // A video is played: back home, the row is the new ranking, once the played video's list is in.
        history.value = listOf(HomeSuggestions.Watched("c", NOW_MS, "UCc")) + history.value
        open("c")
        val replaced = until { it.videos.firstOrNull()?.videoId == "shared" }.videos.map { it.videoId }
        assertThat(replaced.first()).isEqualTo("shared")
        assertThat(replaced).contains("c_1")
        job.cancelAndJoin()
    }
}
