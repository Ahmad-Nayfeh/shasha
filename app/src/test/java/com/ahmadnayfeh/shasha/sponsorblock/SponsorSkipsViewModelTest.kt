package com.ahmadnayfeh.shasha.sponsorblock

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** One player visit's SponsorBlock: asked at once, filtered by the selected categories, never in the way. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class SponsorSkipsViewModelTest {
    private val asked = mutableListOf<String>()
    private val answer = CompletableDeferred<List<SponsorSegment>>()
    private val categories = MutableStateFlow(SponsorCategory.DEFAULTS)

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun visit(undo: Boolean = true) = SponsorSkipsViewModel(
        videoId = VIDEO,
        segments = { id ->
            asked += id
            answer.await()
        },
        categories = categories,
        undoAllowed = undo,
        clock = { 0L },
    )

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

    private companion object {
        const val VIDEO = "lC3h3Lm3q54"
        val SPONSOR = SponsorSegment("s", SponsorCategory.SPONSOR, SegmentAction.SKIP, 119_513, 162_837)
        val FILLER = SponsorSegment("f", SponsorCategory.FILLER, SegmentAction.SKIP, 200_000, 230_000)
    }
}
