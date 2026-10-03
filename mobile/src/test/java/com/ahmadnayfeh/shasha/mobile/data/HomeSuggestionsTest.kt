package com.ahmadnayfeh.shasha.mobile.data

import com.ahmadnayfeh.shasha.mobile.FakeHistoryDao
import com.ahmadnayfeh.shasha.mobile.FakeProgressDao
import com.ahmadnayfeh.shasha.mobile.FakeVideoSource
import com.ahmadnayfeh.shasha.mobile.FakeWatchLaterDao
import com.ahmadnayfeh.shasha.mobile.awaitReal
import com.ahmadnayfeh.shasha.mobile.failure
import com.ahmadnayfeh.shasha.mobile.video
import com.ahmadnayfeh.shasha.youtube.Recommendations
import com.ahmadnayfeh.shasha.youtube.SourceErrorKind
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * «مقترحة لك»: what YouTube suggests beside the newest watched videos, ranked newer viewing first, fetched a few at a time, kept for
 * hours in memory and in a small file, and never in the Home's way. The history is in memory and the source answers from the test;
 * the file is a real one (temporary), and the lists are read and written on the real IO threads.
 */
class HomeSuggestionsTest {
    @get:Rule
    val folder = TemporaryFolder()

    private var now = NOW_MS
    private val historyDao = FakeHistoryDao()
    private val library = LibraryRepository(historyDao, FakeWatchLaterDao(), FakeProgressDao()) { now }
    private val source = FakeVideoSource()
    private val file by lazy { File(folder.root, "home_suggestions.json") }

    private fun suggestions(file: File? = this.file, concurrency: Int = 3) =
        HomeSuggestions(library, source, file, clock = { now }, concurrency = concurrency)

    private suspend fun watch(id: String, daysAgo: Double = 0.0) =
        historyDao.upsert(video(id).toHistoryEntity(now - (daysAgo * DAY_MS).toLong()))

    private fun short(id: String) = video(id).copy(isShortForm = true)

    private suspend fun HomeSuggestions.row(shorts: Boolean = true) = suggested(flowOf(shorts)).first()

    // --- the ranking: newer viewings count more -------------------------------------------------------------------

    @Test
    fun theNewerTheViewingTheMoreItsSuggestionsCount() = runBlocking<Unit> {
        watch("old", daysAgo = 20.0)
        watch("new", daysAgo = 0.0)
        // X is the second suggestion beside the new video and the first beside the old one: with equal weights it would lead.
        source.onRelated = { id -> if (id == "old") listOf(video("X"), video("Y")) else listOf(video("Z"), video("X")) }
        val suggestions = suggestions()

        suggestions.refreshNow()

        assertThat(suggestions.row().videos.map { it.videoId }).containsExactly("Z", "X", "Y").inOrder()
    }

    @Test
    fun whatWasWatchedIsNeverSuggestedAgain_notEvenFromBeyondTheNewestFifteen() = runBlocking<Unit> {
        // 20 watched videos: the newest 15 are the seeds, but all 20 stay out of the row.
        for (i in 1..20) watch("w$i", daysAgo = i.toDouble() / 10)
        source.onRelated = { listOf(video("w1"), video("w20"), video("fresh")) }
        val suggestions = suggestions()

        suggestions.refreshNow()

        assertThat(suggestions.row().videos.map { it.videoId }).containsExactly("fresh")
    }

    @Test
    fun theRowTakesTheNewestFifteenWatchedVideosAsSeeds() = runBlocking<Unit> {
        for (i in 1..20) watch("w$i", daysAgo = i.toDouble())
        val asked = ConcurrentLinkedQueue<String>()
        source.onRelated = { id -> asked += id; emptyList() }

        suggestions().refreshNow()

        assertThat(asked).containsExactlyElementsIn((1..15).map { "w$it" })
    }

    @Test
    fun shortsAreInTheRowOnlyWhenShortsAreOn() = runBlocking<Unit> {
        watch("a")
        source.onRelated = { listOf(video("v1"), short("s1"), video("v2"), short("s2")) }
        val suggestions = suggestions()
        suggestions.refreshNow()

        val on = suggestions.row(shorts = true)
        val off = suggestions.row(shorts = false)

        assertThat(on.videos.map { it.videoId }).containsExactly("v1", "v2").inOrder()
        assertThat(on.shorts.map { it.videoId }).containsExactly("s1", "s2").inOrder()
        assertThat(off.videos.map { it.videoId }).containsExactly("v1", "v2").inOrder()
        assertThat(off.shorts).isEmpty()
    }

    @Test
    fun oneChannelDoesNotFillTheRow() = runBlocking<Unit> {
        watch("a")
        source.onRelated = { List(8) { i -> video("same$i", channelId = "UCsame0000000000000000A") } + video("other") }
        val suggestions = suggestions()

        suggestions.refreshNow()

        val ids = suggestions.row().videos.map { it.videoId }
        assertThat(ids.count { it.startsWith("same") }).isEqualTo(Recommendations.DEFAULT_PER_CHANNEL)
        assertThat(ids).contains("other")
    }

    // --- fetching gently --------------------------------------------------------------------------------------------

    @Test
    fun onlyAFewListsAreAskedForAtATime() = runBlocking<Unit> {
        for (i in 1..12) watch("w$i", daysAgo = i.toDouble())
        val running = AtomicInteger()
        val peak = AtomicInteger()
        val asked = AtomicInteger()
        source.onRelated = {
            peak.accumulateAndGet(running.incrementAndGet()) { a, b -> maxOf(a, b) }
            delay(20)
            running.decrementAndGet()
            asked.incrementAndGet()
            listOf(video("s-$it"))
        }

        suggestions(concurrency = 3).refreshNow()

        assertThat(asked.get()).isEqualTo(12)
        assertThat(peak.get()).isAtMost(3)
        assertThat(peak.get()).isGreaterThan(1) // a few at a time, not one by one
    }

    @Test
    fun aListIsKeptForHoursAndThenAskedForAgain() = runBlocking<Unit> {
        watch("a")
        val asked = AtomicInteger()
        source.onRelated = { asked.incrementAndGet(); listOf(video("x")) }
        val suggestions = suggestions()

        suggestions.refreshNow()
        suggestions.refreshNow()
        assertThat(asked.get()).isEqualTo(1)

        now += HomeSuggestions.FRESH_MS - 1
        suggestions.refreshNow()
        assertThat(asked.get()).isEqualTo(1)

        now += 2
        suggestions.refreshNow()
        assertThat(asked.get()).isEqualTo(2)
    }

    @Test
    fun aNewlyWatchedVideoIsTheOnlyOneAskedFor() = runBlocking<Unit> {
        watch("a", daysAgo = 1.0)
        val asked = ConcurrentLinkedQueue<String>()
        source.onRelated = { id -> asked += id; listOf(video("from-$id")) }
        val suggestions = suggestions()
        suggestions.refreshNow()

        watch("b")
        suggestions.refreshNow()

        assertThat(asked).containsExactly("a", "b").inOrder()
        assertThat(suggestions.row().videos.map { it.videoId }).containsExactly("from-b", "from-a").inOrder() // the newer viewing first
    }

    @Test
    fun aFailureKeepsTheOthersAndTheNextVisitAsksAgainOnlyForIt() = runBlocking<Unit> {
        watch("a", daysAgo = 2.0)
        watch("b", daysAgo = 1.0)
        val asked = ConcurrentLinkedQueue<String>()
        var failB = true
        source.onRelated = { id ->
            asked += id
            if (id == "b" && failB) throw failure(SourceErrorKind.NETWORK, "UnknownHostException") else listOf(video("from-$id"))
        }
        val suggestions = suggestions()

        suggestions.refreshNow()
        assertThat(suggestions.row().videos.map { it.videoId }).containsExactly("from-a")

        failB = false
        suggestions.refreshNow()

        assertThat(asked.toList().sorted()).containsExactly("a", "b", "b").inOrder()
        assertThat(suggestions.row().videos.map { it.videoId }).containsExactly("from-b", "from-a")
    }

    @Test
    fun aRateLimitEndsTheRoundWithoutAskingForTheRest() = runBlocking<Unit> {
        for (i in 1..5) watch("w$i", daysAgo = i.toDouble())
        val asked = ConcurrentLinkedQueue<String>()
        source.onRelated = { id -> asked += id; throw failure(SourceErrorKind.RATE_LIMITED, "HTTP 429") }

        suggestions(concurrency = 1).refreshNow()

        assertThat(asked).containsExactly("w1") // the newest was asked, YouTube said slow down, the others wait for the next visit
    }

    @Test
    fun theHomeIsNotWaitingAndKnowsTheListsAreOnTheirWay() = runBlocking<Unit> {
        watch("a")
        val gate = CompletableDeferred<Unit>()
        source.onRelated = { gate.await(); listOf(video("x")) }
        val suggestions = suggestions()
        val seen = CopyOnWriteArrayList<HomeSuggestions.Suggested>()
        val watcher = launch(Dispatchers.Default) { suggestions.suggested(flowOf(true)).collect { seen += it } }

        awaitReal { seen.isNotEmpty() }
        assertThat(seen.last().isEmpty).isTrue()
        assertThat(seen.last().pending).isFalse() // nothing is on its way yet

        val refresh = launch(Dispatchers.Default) { suggestions.refreshNow() }
        awaitReal { seen.last().pending }
        gate.complete(Unit)
        refresh.join()
        awaitReal { seen.last().videos.isNotEmpty() }

        assertThat(seen.last().pending).isFalse()
        assertThat(seen.last().videos.map { it.videoId }).containsExactly("x")
        watcher.cancel()
    }

    @Test
    fun keepFreshFollowsWhatTheViewerWatches() = runBlocking<Unit> {
        watch("a", daysAgo = 1.0)
        val asked = ConcurrentLinkedQueue<String>()
        source.onRelated = { id -> asked += id; listOf(video("from-$id")) }
        val suggestions = suggestions()
        val latest = AtomicReference(HomeSuggestions.Suggested())
        val showing = launch(Dispatchers.Default) { suggestions.suggested(flowOf(true)).collect { latest.set(it) } }
        val keeping = launch(Dispatchers.Default) { suggestions.keepFresh() }

        awaitReal { asked.toList() == listOf("a") }
        awaitReal { latest.get().videos.map { it.videoId } == listOf("from-a") }
        // Something new is watched: its suggestions are asked for, and count most.
        watch("b")
        awaitReal { asked.toList() == listOf("a", "b") }
        awaitReal { latest.get().videos.map { it.videoId } == listOf("from-b", "from-a") }
        // The history is cleared: nothing is left to suggest.
        historyDao.clear()
        awaitReal { latest.get().isEmpty }

        keeping.cancel()
        showing.cancel()
    }

    // --- the file ---------------------------------------------------------------------------------------------------

    @Test
    fun theListsAreKeptInTheFileForTheNextStart() = runBlocking<Unit> {
        watch("a")
        source.onRelated = { listOf(video("x"), short("s")) }
        suggestions().refreshNow()
        assertThat(file.isFile).isTrue()

        // A new start (a new object, the same file): the row is there without a request.
        source.onRelated = { throw AssertionError("YouTube must not be asked: the list is fresh") }
        val restarted = suggestions()
        restarted.refreshNow()

        val row = restarted.row()
        assertThat(row.videos.map { it.videoId }).containsExactly("x")
        assertThat(row.shorts.map { it.videoId }).containsExactly("s")
    }

    @Test
    fun aListOlderThanTheFreshTimeShowsAtOnceFromTheFileUntilTheNewAnswerComes() = runBlocking<Unit> {
        watch("a")
        source.onRelated = { listOf(video("old-answer")) }
        suggestions().refreshNow()
        now += HomeSuggestions.FRESH_MS + 1 // stale, but still worth showing

        val gate = CompletableDeferred<Unit>()
        source.onRelated = { gate.await(); listOf(video("new-answer")) }
        val restarted = suggestions()
        val refresh = launch(Dispatchers.Default) { restarted.refreshNow() }
        awaitReal { restarted.kept.value.isNotEmpty() } // read from the file while the new answer is awaited
        assertThat(restarted.row().videos.map { it.videoId }).containsExactly("old-answer")

        gate.complete(Unit)
        refresh.join()
        assertThat(restarted.row().videos.map { it.videoId }).containsExactly("new-answer")
    }

    @Test
    fun aListOlderThanTwoDaysIsNotShownFromTheFile() = runBlocking<Unit> {
        watch("a")
        source.onRelated = { listOf(video("x")) }
        suggestions().refreshNow()
        now += HomeSuggestions.SHOWN_FOR_MS + 1
        source.onRelated = { throw failure() } // offline too

        val restarted = suggestions()
        restarted.refreshNow()

        assertThat(restarted.row().isEmpty).isTrue()
    }

    @Test
    fun aDamagedFileStartsOverInsteadOfFailing() = runBlocking<Unit> {
        watch("a")
        file.writeText("{ this is not json")
        source.onRelated = { listOf(video("x")) }
        val suggestions = suggestions()

        suggestions.refreshNow()

        assertThat(suggestions.row().videos.map { it.videoId }).containsExactly("x")
        // The file was rewritten whole.
        val reopened = suggestions()
        source.onRelated = { throw AssertionError("not asked") }
        reopened.refreshNow()
        assertThat(reopened.row().videos.map { it.videoId }).containsExactly("x")
    }

    @Test
    fun whatTheBuiltInFilterHoldsBackIsNotShownFromTheFileEither() = runBlocking<Unit> {
        watch("a")
        // A file from before the filter existed holds a title that the filter refuses.
        file.writeText(
            """{"entries":[{"seed":"a","atMs":$now,"videos":[
                {"id":"good","title":"Cooking pasta","url":"https://www.youtube.com/watch?v=good"},
                {"id":"bad","title":"porn videos","url":"https://www.youtube.com/watch?v=bad"}]}]}""",
        )
        source.onRelated = { throw failure() } // the file is all there is
        val suggestions = suggestions()

        suggestions.refreshNow()

        assertThat(suggestions.row().videos.map { it.videoId }).containsExactly("good")
    }

    @Test
    fun withoutAFileEverythingStillWorksInMemory() = runBlocking<Unit> {
        watch("a")
        source.onRelated = { listOf(video("x")) }
        val suggestions = suggestions(file = null)

        suggestions.refreshNow()

        assertThat(suggestions.row().videos.map { it.videoId }).containsExactly("x")
        assertThat(withContext(Dispatchers.IO) { folder.root.listFiles().orEmpty().toList() }).isEmpty()
    }

    @Test
    fun theKeptListsAreCappedSoTheFileStaysSmall() = runBlocking<Unit> {
        source.onRelated = { id -> listOf(video("from-$id")) }
        val suggestions = suggestions()
        for (i in 1..15) watch("w$i", daysAgo = 10.0 + i)
        suggestions.refreshNow()
        // Fifteen newer videos are watched: they are the seeds now, and the older lists are still in the cache.
        for (i in 1..15) watch("v$i", daysAgo = i / 10.0)
        suggestions.refreshNow()

        assertThat(suggestions.kept.value.size).isEqualTo(HomeSuggestions.MAX_KEPT) // 30 lists were fetched, the newest 24 are kept
        assertThat(suggestions.kept.value.keys).containsAtLeastElementsIn((1..15).map { "v$it" })
    }
}
