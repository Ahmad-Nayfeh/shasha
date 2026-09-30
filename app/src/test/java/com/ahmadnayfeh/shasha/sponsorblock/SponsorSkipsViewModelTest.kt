package com.ahmadnayfeh.shasha.sponsorblock

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * One player visit's SponsorBlock: asked at once, filtered by the selected categories, never in the way, and
 * asked again when a lookup failed (the network back, or a while later) until one answers.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class SponsorSkipsViewModelTest {
    private val scheduler = TestCoroutineScheduler()
    private val asked = mutableListOf<String>()
    private val answer = CompletableDeferred<List<SponsorSegment>>()
    private val categories = MutableStateFlow(SponsorCategory.DEFAULTS)
    private val online = MutableStateFlow(true)

    /** What each lookup answers, in turn; after the list, [answer] (a lookup that waits for the test). */
    private val lookups = ArrayDeque<SponsorBlockRepository.Lookup>()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun visit(undo: Boolean = true) = SponsorSkipsViewModel(
        videoId = VIDEO,
        lookup = { id ->
            asked += id
            lookups.removeFirstOrNull() ?: SponsorBlockRepository.Lookup.Fetched(answer.await())
        },
        categories = categories,
        undoAllowed = undo,
        online = online,
        clock = { 0L },
    )

    private fun failed(vararg older: SponsorSegment) = SponsorBlockRepository.Lookup.Failed(older.toList(), IOException("timeout"))

    @Test
    fun theSegmentsAreAskedForAsTheScreenOpens_andNothingWaitsForThem() {
        val vm = visit()
        // Asked right away, beside the extraction; the answer has not come, and nothing is held back meanwhile.
        assertThat(asked).containsExactly(VIDEO)
        assertThat(vm.skipper.current).isEmpty()

        answer.complete(listOf(SPONSOR, FILLER))
        assertThat(vm.skipper.current).containsExactly(SPONSOR)
    }

    @Test
    fun aChangeInTheSettings_appliesWithoutAskingAgain() {
        val vm = visit()
        answer.complete(listOf(SPONSOR, FILLER))
        categories.value = SponsorCategory.DEFAULTS + SponsorCategory.FILLER
        assertThat(vm.skipper.current).containsExactly(SPONSOR, FILLER).inOrder()
        categories.value = setOf(SponsorCategory.FILLER)
        assertThat(vm.skipper.current).containsExactly(FILLER)
        assertThat(asked).hasSize(1)
    }

    @Test
    fun withNoCategoryChosen_SponsorBlockIsNotEvenAsked() {
        categories.value = emptySet()
        val vm = visit()
        assertThat(asked).isEmpty()
        assertThat(vm.skipper.current).isEmpty()
    }

    @Test
    fun theUndoFollowsTheProfile() {
        assertThat(visit(undo = true).skipper.undoAllowed).isTrue()
        assertThat(visit(undo = false).skipper.undoAllowed).isFalse()
    }

    @Test
    fun aFailure_leavesTheVideoWithoutSkips() {
        val vm = visit()
        answer.completeExceptionally(IllegalStateException("database closed"))
        assertThat(vm.skipper.current).isEmpty()
    }

    @Test
    fun aLookupThatFailedOffline_isAskedAgainAsSoonAsTheNetworkIsBack() {
        // A new video opened while the Wi-Fi was down: no answer, and none in the cache.
        online.value = false
        lookups += failed()
        val vm = visit()
        assertThat(asked).hasSize(1)
        assertThat(vm.skipper.current).isEmpty()

        // The Wi-Fi comes back: asked again at once, and the sponsor is skipped from now on.
        online.value = true
        answer.complete(listOf(SPONSOR))
        assertThat(asked).hasSize(2)
        assertThat(vm.skipper.current).containsExactly(SPONSOR)

        // Once answered, never again in this visit (the network coming and going changes nothing).
        online.value = false
        online.value = true
        scheduler.advanceTimeBy(60 * 60_000L)
        scheduler.runCurrent()
        assertThat(asked).hasSize(2)
    }

    @Test
    fun aLookupThatTimedOut_isAskedAgainAWhileLater_andAnOlderAnswerSkipsMeanwhile() {
        // SponsorBlock slow (the network is fine): the cache's answer from three days ago is used meanwhile.
        lookups += failed(SPONSOR)
        lookups += failed(SPONSOR)
        val vm = visit()
        assertThat(vm.skipper.current).containsExactly(SPONSOR)
        assertThat(asked).hasSize(1)

        scheduler.advanceTimeBy(SponsorSkipsViewModel.RETRY_DELAYS_MS[0] - 1)
        scheduler.runCurrent()
        assertThat(asked).hasSize(1)
        scheduler.advanceTimeBy(1)
        scheduler.runCurrent()
        assertThat(asked).hasSize(2)

        // It failed again: the next try waits longer.
        scheduler.advanceTimeBy(SponsorSkipsViewModel.RETRY_DELAYS_MS[1] - 1)
        scheduler.runCurrent()
        assertThat(asked).hasSize(2)
        scheduler.advanceTimeBy(1)
        scheduler.runCurrent()
        assertThat(asked).hasSize(3)
        answer.complete(listOf(SPONSOR, FILLER))
        assertThat(vm.skipper.current).containsExactly(SPONSOR)
    }

    @Test
    fun aFailedLookup_isNotAskedAgainWhileNoCategoryIsChosen() {
        lookups += failed()
        val vm = visit()
        categories.value = emptySet() // the adult turned SponsorBlock off meanwhile
        scheduler.advanceTimeBy(60 * 60_000L)
        scheduler.runCurrent()
        assertThat(asked).hasSize(1)

        categories.value = SponsorCategory.DEFAULTS
        answer.complete(listOf(SPONSOR))
        assertThat(asked).hasSize(2)
        assertThat(vm.skipper.current).containsExactly(SPONSOR)
    }

    private companion object {
        const val VIDEO = "lC3h3Lm3q54"
        val SPONSOR = SponsorSegment("s", SponsorCategory.SPONSOR, SegmentAction.SKIP, 119_513, 162_837)
        val FILLER = SponsorSegment("f", SponsorCategory.FILLER, SegmentAction.SKIP, 200_000, 230_000)
    }
}
