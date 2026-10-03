package com.ahmadnayfeh.shasha.mobile.ui

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ahmadnayfeh.shasha.mobile.MainDispatcherRule
import com.ahmadnayfeh.shasha.mobile.common.ShortsQueues
import com.ahmadnayfeh.shasha.mobile.player.PlayerPhase
import com.ahmadnayfeh.shasha.mobile.player.PlayerUiState
import com.ahmadnayfeh.shasha.mobile.player.ShortsPlayer
import com.ahmadnayfeh.shasha.mobile.ui.shorts.ShortsQueue
import com.ahmadnayfeh.shasha.mobile.ui.shorts.ShortsViewModel
import com.ahmadnayfeh.shasha.mobile.video
import com.ahmadnayfeh.shasha.youtube.VideoItem
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Rule
import org.junit.Test

private fun short(id: String) = video(id).copy(isShortForm = true)

/** The queue the Shorts viewer pages through, and above all what happens at its two ends. */
class ShortsQueueTest {
    private val list = listOf(short("a"), short("b"), short("c"))

    @Test
    fun theViewerOpensAtTheShortThatWasTapped() {
        val queue = ShortsQueue.of(list, startId = "b")

        assertThat(queue.items.map { it.videoId }).containsExactly("a", "b", "c").inOrder()
        assertThat(queue.startIndex).isEqualTo(1)
        assertThat(queue.at(queue.startIndex)?.videoId).isEqualTo("b")
    }

    @Test
    fun swipingUpGoesToTheNextShortAndStopsAtTheLastOne() {
        val queue = ShortsQueue.of(list, "a")

        assertThat(queue.next(0)?.videoId).isEqualTo("b")
        assertThat(queue.next(1)?.videoId).isEqualTo("c")
        assertThat(queue.next(2)).isNull() // the last one: nothing to look up ahead
        assertThat(queue.hasNext(1)).isTrue()
        assertThat(queue.hasNext(2)).isFalse()
        assertThat(queue.next(7)).isNull()
        assertThat(queue.next(-1)).isNull()
    }

    @Test
    fun swipingDownGoesBackAndStopsAtTheFirstOne() {
        val queue = ShortsQueue.of(list, "c")

        assertThat(queue.hasPrevious(2)).isTrue()
        assertThat(queue.hasPrevious(1)).isTrue()
        assertThat(queue.hasPrevious(0)).isFalse()
        assertThat(queue.hasPrevious(3)).isFalse()
        assertThat(queue.at(-1)).isNull()
        assertThat(queue.at(3)).isNull()
    }

    @Test
    fun aNumberOutsideTheQueueIsHeldInsideIt() {
        val queue = ShortsQueue.of(list, "a")

        assertThat(queue.clamp(-4)).isEqualTo(0)
        assertThat(queue.clamp(1)).isEqualTo(1)
        assertThat(queue.clamp(40)).isEqualTo(2)
    }

    @Test
    fun aShortListedTwiceStaysWhereItFirstIs() {
        val queue = ShortsQueue.of(listOf(short("a"), short("b"), short("a"), short("c")), "c")

        assertThat(queue.items.map { it.videoId }).containsExactly("a", "b", "c").inOrder()
        assertThat(queue.startIndex).isEqualTo(2)
    }

    @Test
    fun aQueueOfOneShortHasNoEnds_toGoTo() {
        val queue = ShortsQueue.of(listOf(short("only")), "only")

        assertThat(queue.size).isEqualTo(1)
        assertThat(queue.hasNext(0)).isFalse()
        assertThat(queue.hasPrevious(0)).isFalse()
        assertThat(queue.next(0)).isNull()
    }

    @Test
    fun withoutItsListTheViewerShowsTheOneShortItsRouteNames() {
        for (lost in listOf(emptyList(), list)) {
            val queue = ShortsQueue.of(lost, "gone")

            assertThat(queue.items.map { it.videoId }).containsExactly("gone")
            assertThat(queue.startIndex).isEqualTo(0)
            assertThat(queue.at(0)?.isShortForm).isTrue()
        }
    }

    @Test
    fun theListsAWaitingForTheViewerAreKeptByNumber_andOnlyTheLastFew() {
        val queues = ShortsQueues()

        val first = queues.put(list)
        assertThat(queues.get(first)).isEqualTo(list)

        val later = (1..4).map { queues.put(listOf(short("q$it"))) }
        assertThat(queues.get(first)).isNull() // pushed out: the viewer then shows the one Short its route names
        assertThat(later.map { queues.get(it)?.single()?.videoId }).containsExactly("q1", "q2", "q3", "q4").inOrder()
        assertThat(queues.get(-1)).isNull()
        assertThat(later.toSet()).hasSize(4) // every list has a number of its own
    }
}

/** A player that records what the viewer asks of it; its state is the test's to set. */
private class RecordingShortsPlayer : ShortsPlayer {
    override val state = MutableStateFlow(PlayerUiState())
    val calls = ArrayList<String>()

    override fun playShort(video: VideoItem) {
        calls += "play:${video.videoId}"
        state.value = PlayerUiState(phase = PlayerPhase.LOADING, video = video)
    }

    override fun prefetch(videoId: String) {
        calls += "prefetch:$videoId"
    }

    override fun togglePlay() {
        calls += "toggle"
    }

    override fun resumePlayback() {
        calls += "resume"
    }

    override fun pause() {
        calls += "pause"
    }

    override fun retry() {
        calls += "retry"
    }

    override fun stop() {
        calls += "stop"
        state.value = PlayerUiState()
    }
}

/** The Shorts viewer's screen logic over a recording player: which Short plays, which streams are looked up ahead, who leaves. */
class ShortsViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val player = RecordingShortsPlayer()
    private val store = ViewModelStore()
    private val queue = ShortsQueue.of(listOf(short("a"), short("b"), short("c")), "a")

    private fun viewModel(queue: ShortsQueue = this.queue): ShortsViewModel =
        ViewModelProvider.create(store, viewModelFactory { initializer { ShortsViewModel(player, queue) } })[ShortsViewModel::class]

    @Test
    fun theViewerPlaysTheShortItOpensAtAndLooksUpTheNextOneAhead() {
        viewModel()

        assertThat(player.calls).containsExactly("play:a", "prefetch:b").inOrder()
    }

    @Test
    fun opensInTheMiddleOfAListWithTheNextOneAheadAndTheShortBeforeUntouched() {
        val vm = viewModel(ShortsQueue.of(queue.items, "b"))

        assertThat(vm.index.value).isEqualTo(1)
        assertThat(player.calls).containsExactly("play:b", "prefetch:c").inOrder()
    }

    @Test
    fun theFirstSettleOnThePageItOpenedAtIsNotAskedTwice() {
        val vm = viewModel()

        vm.onPageSettled(0) // the pager reports the page it starts on

        assertThat(player.calls).containsExactly("play:a", "prefetch:b").inOrder()
    }

    @Test
    fun swipingUpPlaysTheNextShortAndLooksUpTheOneAfterIt() {
        val vm = viewModel()
        player.calls.clear()

        vm.onPageSettled(1)

        assertThat(vm.index.value).isEqualTo(1)
        assertThat(player.calls).containsExactly("play:b", "prefetch:c").inOrder()
    }

    @Test
    fun atTheLastShortNothingIsLookedUpAhead() {
        val vm = viewModel()
        vm.onPageSettled(1)
        player.calls.clear()

        vm.onPageSettled(2)

        assertThat(player.calls).containsExactly("play:c") // there is no next one
    }

    @Test
    fun swipingDownPlaysTheShortBeforeAndLooksUpTheOneThatWasThere() {
        val vm = viewModel(ShortsQueue.of(queue.items, "c"))
        player.calls.clear()

        vm.onPageSettled(1)

        assertThat(player.calls).containsExactly("play:b", "prefetch:c").inOrder()
        assertThat(vm.index.value).isEqualTo(1)
    }

    @Test
    fun aPageOutsideTheQueueIsIgnored() {
        val vm = viewModel()
        player.calls.clear()

        vm.onPageSettled(-1)
        vm.onPageSettled(3)

        assertThat(player.calls).isEmpty()
        assertThat(vm.index.value).isEqualTo(0)
    }

    @Test
    fun theControlsAreThePlayers() {
        val vm = viewModel()
        player.calls.clear()

        vm.togglePlay()
        vm.retry()

        assertThat(player.calls).containsExactly("toggle", "retry").inOrder()
    }

    @Test
    fun theChannelOfAShortPausesItAndTheWayBackPlaysItOn() {
        val vm = viewModel()
        vm.onShown() // the first time on screen: the start already played it
        player.calls.clear()

        vm.onLeaveForChannel()
        vm.onShown()

        assertThat(player.calls).containsExactly("pause", "resume").inOrder()
    }

    @Test
    fun comingBackWhenAnotherVideoHasThePlayerPlaysTheShortAgain() {
        val vm = viewModel()
        vm.onShown()
        player.state.value = PlayerUiState(phase = PlayerPhase.READY, video = video("elsewhere"))
        player.calls.clear()

        vm.onLeaveForChannel()
        vm.onShown()

        assertThat(player.calls).containsExactly("pause", "play:a", "prefetch:b").inOrder()
    }

    @Test
    fun theViewerBeingShownAgainWithoutADetourDoesNotRestartAnything() {
        val vm = viewModel()
        vm.onShown()
        player.calls.clear()

        vm.onShown() // the app came back to the front: the player's own handling covers it

        assertThat(player.calls).isEmpty()
    }

    @Test
    fun leavingStopsThePlayerOfThisViewer_butNotOneAnotherScreenHasTakenOver() {
        viewModel()
        store.clear()
        assertThat(player.calls.last()).isEqualTo("stop")

        // Another screen played another video meanwhile: it keeps its player.
        val other = RecordingShortsPlayer()
        val otherStore = ViewModelStore()
        ViewModelProvider.create(otherStore, viewModelFactory { initializer { ShortsViewModel(other, queue) } })[ShortsViewModel::class]
        other.state.value = PlayerUiState(phase = PlayerPhase.READY, video = video("someone-elses"))
        other.calls.clear()
        otherStore.clear()
        assertThat(other.calls).isEmpty()
    }
}
