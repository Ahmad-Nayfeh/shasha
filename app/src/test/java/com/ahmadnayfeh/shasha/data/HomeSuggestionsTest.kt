package com.ahmadnayfeh.shasha.data

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.youtube.SourceErrorKind
import com.ahmadnayfeh.shasha.youtube.SourceException
import com.ahmadnayfeh.shasha.youtube.VideoItem
import com.ahmadnayfeh.shasha.youtube.VideoSource
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.cancelAndJoin
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
 * «مقترحة لك»: the suggestions beside the last videos watched are ranked so that a newer viewing weighs more, kept a few hours (on
 * disk, so a restart asks nothing), asked for only when missing or old, never while a video plays, one at a time and gently, and a
 * failure keeps what was there. The Shorts come from the channels watched from, through the same ranking.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class HomeSuggestionsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private var now = NOW_MS
    private val playing = MutableStateFlow(false)
    private val asked = CopyOnWriteArrayList<String>()

    /** Each watched video's suggestions: three of its own, the second shared with every other one. */
    private fun relatedOf(seed: String) = listOf(video("${seed}_1", "UCa", null), video("shared", "UCa", null), video("${seed}_3", "UCa", null))

    private val source: VideoSource = object : VideoSource by FakeVideoSource() {
        override suspend fun related(videoId: String): List<VideoItem> {
            asked += videoId
            failures[videoId]?.let { throw it }
            return relatedOf(videoId)
        }

        override suspend fun channelShorts(channelId: String): List<VideoItem> {
            asked += "shorts:$channelId"
            failures["shorts:$channelId"]?.let { throw it }
            return shortsOf(channelId)
        }
    }

    /** What the source answers with a failure instead (a watched video's id, or "shorts:" and a channel's). */
    private val failures = HashMap<String, SourceException>()

    private fun networkFailure(what: String) = SourceException(SourceErrorKind.NETWORK, "UnknownHostException", what, null)

    /** A channel's newest Shorts: three, marked short-form. */
    private fun shortsOf(channelId: String) = (1..3).map { video("${channelId}_s$it", channelId, null, short = true) }

    private val file: File get() = File(tmp.root, HomeSuggestions.FILE_NAME)

    private fun suggestions(on: File? = file, pauseMs: Long = 0) =
        HomeSuggestions(source, on, clock = { now }, playing = playing, lowPriority = { it() }, io = Dispatchers.Unconfined, pauseMs = pauseMs)

    /** A watched video: [ageDays] old (a day is 24 hours), made by [channel]. */
    private fun watched(id: String, ageDays: Int = 0, channel: String? = "UCa") = HomeSuggestions.Watched(id, now - ageDays * DAY_MS, channel)

    @Test
    fun theRanking_weighsANewerViewingMore_withoutRepeats_watchedVideos_orTheSeeds() {
        val kept = mapOf(
            "a" to HomeSuggestions.Kept(relatedOf("a"), now),
            "b" to HomeSuggestions.Kept(relatedOf("b"), now),
            "c" to HomeSuggestions.Kept(listOf(video("a", "UCa", null), video("c_1", "UCa", null)), now),
        )
        // a is watched now, c a week ago (half the weight), b two weeks ago (a quarter), and b_3 was watched too.
        val history = listOf(watched("a"), watched("c", ageDays = 7), watched("b", ageDays = 14), watched("b_3", ageDays = 40))
        val row = HomeSuggestions.rank(history, kept, emptyMap(), now).videos
        // a's own first and its third come first: "shared" beside a and b adds up and ties with a_1; "a" (a seed) and b_3 (watched) never
        // come back. Round-robin would have put b_1 second: the oldest viewing's first suggestion now comes last.
        assertThat(row.map { it.videoId }).containsExactly("a_1", "shared", "a_3", "c_1", "b_1").inOrder()
        assertThat(HomeSuggestions.rank(emptyList(), kept, emptyMap(), now).videos).isEmpty()
    }

    @Test
    fun theSeeds_areTheNewestTwelve_theRestOnlyKeepSuggestionsOut() {
        val history = (1..15).map { watched("w$it", ageDays = it) }
        val kept = history.associate { it.videoId to HomeSuggestions.Kept(listOf(video("${it.videoId}_1", "UCa", null)), now) } +
            ("w15" to HomeSuggestions.Kept(listOf(video("w14", "UCa", null), video("far_1", "UCa", null)), now))
        val row = HomeSuggestions.rank(history, kept, emptyMap(), now).videos.map { it.videoId }
        assertThat(row).contains("w1_1")
        assertThat(row).contains("w12_1")
        // The thirteenth watched video is no seed: nothing beside it counts.
        assertThat(row).doesNotContain("w13_1")
        assertThat(row).doesNotContain("far_1")
    }

    @Test
    fun theShorts_comeFromTheChannelsWatchedFrom_theNewerTheirViewingTheHigher() {
        val history = listOf(watched("a", channel = "UCa"), watched("b", ageDays = 14, channel = "UCb"), watched("c", ageDays = 1, channel = null))
        val channelShorts = mapOf(
            "UCa" to HomeSuggestions.Kept(shortsOf("UCa"), now),
            "UCb" to HomeSuggestions.Kept(shortsOf("UCb"), now),
        )
        val ranked = HomeSuggestions.rank(history, emptyMap(), channelShorts, now)
        assertThat(ranked.videos).isEmpty()
        assertThat(ranked.shorts.map { it.videoId })
            .containsExactly("UCa_s1", "UCa_s2", "UCa_s3", "UCb_s1", "UCb_s2", "UCb_s3").inOrder()
        // A profile that hides Shorts gets no Shorts row at all.
        assertThat(HomeSuggestions.rank(history, emptyMap(), channelShorts, now, showShorts = false).shorts).isEmpty()
    }

    @Test
    fun theShorts_alsoTakeTheShortsAmongTheSuggestions_andNeverAWatchedOne() {
        val history = listOf(watched("a", channel = "UCa"), watched("UCa_s1", ageDays = 3, channel = "UCa"))
        val related = listOf(video("x_1", "UCx", null), video("x_short", "UCx", null, short = true))
        val kept = mapOf("a" to HomeSuggestions.Kept(related, now))
        val channelShorts = mapOf("UCa" to HomeSuggestions.Kept(shortsOf("UCa"), now))
        val ranked = HomeSuggestions.rank(history, kept, channelShorts, now)
        // The video row has no Short; the Shorts row has the one among the suggestions, the channel's, not the one already watched.
        assertThat(ranked.videos.map { it.videoId }).containsExactly("x_1")
        assertThat(ranked.shorts.map { it.videoId }).containsExactly("UCa_s2", "x_short", "UCa_s3").inOrder()
    }

    @Test
    fun oneChannelDoesNotFillTheShortsRow() {
        val many = (1..9).map { video("m_$it", "UCm", null, short = true) }
        val history = listOf(watched("a", channel = "UCm"))
        val ranked = HomeSuggestions.rank(history, emptyMap(), mapOf("UCm" to HomeSuggestions.Kept(many, now)), now)
        assertThat(ranked.shorts.map { it.videoId }).containsExactly("m_1", "m_2", "m_3", "m_4", "m_5").inOrder()
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
        assertThat(HomeSuggestions.rank(listOf(watched("a"), watched("b")), restarted.kept.value, emptyMap(), now).videos.map { it.videoId })
            .containsExactly("a_1", "shared", "b_1", "a_3", "b_3").inOrder()
    }

    @Test
    fun oldListsShowUntilReplaced_veryOldOnesAreDropped() = runBlocking<Unit> {
        suggestions().refresh(listOf("a"))
        now += HomeSuggestions.FRESH_MS + 1
        failures["a"] = networkFailure("a") // no network on the next start
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
    fun atMostTheNewestWatchedVideosListsAreKept() = runBlocking<Unit> {
        val s = suggestions()
        for (i in 1..HomeSuggestions.MAX_KEPT + 3) {
            now += 1
            s.refresh(listOf("v$i"))
        }
        assertThat(s.kept.value).hasSize(HomeSuggestions.MAX_KEPT)
        assertThat(s.kept.value.keys).doesNotContain("v1")
        assertThat(s.kept.value.keys).contains("v${HomeSuggestions.MAX_KEPT + 3}")
    }

    @Test
    fun theShortsOfAChannel_areAskedAfterTheSuggestions_keptOnDisk_andAskedAgainOnlyWhenOld() = runBlocking<Unit> {
        val s = suggestions()
        s.refresh(listOf("a", "b"), shortsOf = listOf("UCa", "UCb"))
        assertThat(asked).containsExactly("a", "b", "shorts:UCa", "shorts:UCb").inOrder()
        assertThat(s.shorts.value.keys).containsExactly("UCa", "UCb")
        assertThat(s.shorts.value.getValue("UCa").videos.map { it.videoId }).containsExactly("UCa_s1", "UCa_s2", "UCa_s3").inOrder()

        // Back later: nothing is asked; after a restart too (the file has both kinds of list).
        now += HomeSuggestions.FRESH_MS - 1
        s.refresh(listOf("a", "b"), shortsOf = listOf("UCa", "UCb"))
        val restarted = suggestions()
        restarted.refresh(listOf("a", "b"), shortsOf = listOf("UCa", "UCb"))
        assertThat(asked).hasSize(4)
        assertThat(restarted.shorts.value.getValue("UCb").videos.map { it.videoId }).containsExactly("UCb_s1", "UCb_s2", "UCb_s3").inOrder()
        assertThat(restarted.shorts.value.getValue("UCb").videos.all { it.isShortForm }).isTrue()

        // Hours later only the old lists are asked for again, a new channel too.
        now += 2
        restarted.refresh(listOf("a"), shortsOf = listOf("UCc"))
        assertThat(asked.drop(4)).containsExactly("a", "shorts:UCc").inOrder()
    }

    @Test
    fun withoutAChannelListNoShortsAreAsked() = runBlocking<Unit> {
        suggestions().refresh(listOf("a", "b"))
        assertThat(asked).containsExactly("a", "b")
    }

    @Test
    fun aFailureKeepsTheRest_butASlowDownEndsTheRound() = runBlocking<Unit> {
        failures["b"] = networkFailure("b")
        val s = suggestions()
        s.refresh(listOf("a", "b", "c"), shortsOf = listOf("UCa"))
        // A network failure is only that list's: the others are still asked for.
        assertThat(asked).containsExactly("a", "b", "c", "shorts:UCa").inOrder()
        assertThat(s.kept.value.keys).containsExactly("a", "c")

        // YouTube said to slow down: nothing more is asked this round, and what came before stays.
        asked.clear()
        failures["e"] = SourceException(SourceErrorKind.BLOCKED_403, "HttpStatus403", "e", null)
        s.refresh(listOf("d", "e", "f", "g"), shortsOf = listOf("UCb"))
        assertThat(asked).containsExactly("d", "e").inOrder()
        assertThat(s.kept.value.keys).contains("d")
        assertThat(s.kept.value.keys).doesNotContain("f")
        assertThat(s.working.value).isFalse()
    }

    @Test
    fun workingIsTrueWhileARoundAsks_andFalseAfterwards_whateverHappened() = runBlocking<Unit> {
        val waiting = CompletableDeferred<Unit>()
        val slow = object : VideoSource by source {
            override suspend fun related(videoId: String): List<VideoItem> {
                waiting.await()
                return source.related(videoId)
            }
        }
        val s = HomeSuggestions(slow, null, clock = { now }, playing = playing, lowPriority = { it() }, io = Dispatchers.Unconfined, pauseMs = 0)
        assertThat(s.working.value).isFalse()
        val round = CoroutineScope(Dispatchers.Default).launch { s.refresh(listOf("a")) }
        withTimeout(5_000) { s.working.first { it } }
        waiting.complete(Unit)
        round.join()
        assertThat(s.working.value).isFalse()

        // A round that is cancelled ends too.
        val never = CompletableDeferred<Unit>()
        val stuck = object : VideoSource by source {
            override suspend fun related(videoId: String): List<VideoItem> {
                never.await()
                return emptyList()
            }
        }
        val other = HomeSuggestions(stuck, null, clock = { now }, playing = playing, lowPriority = { it() }, io = Dispatchers.Unconfined, pauseMs = 0)
        val cancelled = CoroutineScope(Dispatchers.Default).launch { other.refresh(listOf("z")) }
        withTimeout(5_000) { other.working.first { it } }
        cancelled.cancelAndJoin()
        assertThat(other.working.value).isFalse()
    }

    @Test
    fun theRequestsOfARound_areSpacedApart() = runBlocking<Unit> {
        val s = suggestions(on = null, pauseMs = 120)
        val started = System.nanoTime()
        s.refresh(listOf("a", "b", "c"))
        val tookMs = (System.nanoTime() - started) / 1_000_000
        // Two pauses between three requests (and none before the first).
        assertThat(tookMs).isAtLeast(230)
        assertThat(asked).containsExactly("a", "b", "c").inOrder()
    }

    @Test
    fun theSuggestedFlow_followsTheHistoryAndTheLists() = runBlocking<Unit> {
        val s = suggestions(on = null)
        val history = MutableStateFlow(listOf(watched("a")))
        val showsShorts = MutableStateFlow(true)
        val seen = s.suggested(history, showsShorts)
        assertThat(withTimeout(5_000) { seen.first() }.videos).isEmpty()
        s.refresh(listOf("a"), shortsOf = listOf("UCa"))
        val ranked = withTimeout(5_000) { seen.first { it.videos.isNotEmpty() } }
        assertThat(ranked.videos.map { it.videoId }).containsExactly("a_3", "shared", "a_1").inOrder().or(ranked.videos.map { it.videoId }.let { assertThat(it).containsExactly("a_1", "shared", "a_3").inOrder() })
        assertThat(ranked.shorts.map { it.videoId }).containsExactly("UCa_s1", "UCa_s2", "UCa_s3").inOrder()
        assertThat(ranked.working).isFalse()

        showsShorts.value = false
        assertThat(withTimeout(5_000) { seen.first { it.shorts.isEmpty() } }.videos).isNotEmpty()
    }
}
