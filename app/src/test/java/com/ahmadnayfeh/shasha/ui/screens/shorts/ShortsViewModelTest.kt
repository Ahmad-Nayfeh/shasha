package com.ahmadnayfeh.shasha.ui.screens.shorts

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.policy.clip
import com.ahmadnayfeh.shasha.youtube.SourceErrorKind
import com.ahmadnayfeh.shasha.youtube.SourceException
import com.ahmadnayfeh.shasha.youtube.VideoItem
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The Shorts player's list: DOWN is the next Short and UP the one before, each stops at its end and says so, a Short that came
 * without its list plays alone, and the Short after the one on screen is got ready one at a time, never for nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ShortsViewModelTest {
    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val a = clip("aaaaaaaaaaa", short = true)
    private val b = clip("bbbbbbbbbbb", short = true)
    private val c = clip("ccccccccccc", short = true)
    private fun ids(vm: ShortsViewModel) = vm.shorts.map { it.videoId }

    @Test
    fun startsAtTheShortChosen_whereverItIsInTheList() {
        val vm = ShortsViewModel(listOf(a, b, c), startVideoId = "bbbbbbbbbbb")
        assertThat(vm.index.value).isEqualTo(1)
        assertThat(vm.current).isEqualTo(b)
        assertThat(ids(vm)).containsExactly("aaaaaaaaaaa", "bbbbbbbbbbb", "ccccccccccc").inOrder()
    }

    @Test
    fun down_isTheNextShort_andStopsAtTheLastOne() {
        val vm = ShortsViewModel(listOf(a, b, c), startVideoId = "aaaaaaaaaaa")
        assertThat(vm.next()).isTrue()
        assertThat(vm.current).isEqualTo(b)
        assertThat(vm.next()).isTrue()
        assertThat(vm.current).isEqualTo(c)
        // At the end it says so (the screen shows «هذا آخر مقطع») and stays where it is, however often it is pressed.
        assertThat(vm.next()).isFalse()
        assertThat(vm.next()).isFalse()
        assertThat(vm.index.value).isEqualTo(2)
        assertThat(vm.current).isEqualTo(c)
    }

    @Test
    fun up_isTheShortBefore_andStopsAtTheFirstOne() {
        val vm = ShortsViewModel(listOf(a, b, c), startVideoId = "ccccccccccc")
        assertThat(vm.previous()).isTrue()
        assertThat(vm.current).isEqualTo(b)
        assertThat(vm.previous()).isTrue()
        assertThat(vm.current).isEqualTo(a)
        assertThat(vm.previous()).isFalse()
        assertThat(vm.index.value).isEqualTo(0)
        // And back down again: the ends are not walls.
        assertThat(vm.next()).isTrue()
        assertThat(vm.current).isEqualTo(b)
    }

    @Test
    fun aListWithOneShort_hasNowhereToGo() {
        val vm = ShortsViewModel(listOf(a), startVideoId = "aaaaaaaaaaa")
        assertThat(vm.next()).isFalse()
        assertThat(vm.previous()).isFalse()
        assertThat(vm.index.value).isEqualTo(0)
    }

    @Test
    fun eachShortIsInTheListOnce() {
        val vm = ShortsViewModel(listOf(a, b, a, c, b), startVideoId = "ccccccccccc")
        assertThat(ids(vm)).containsExactly("aaaaaaaaaaa", "bbbbbbbbbbb", "ccccccccccc").inOrder()
        assertThat(vm.index.value).isEqualTo(2)
    }

    @Test
    fun aShortWithoutItsList_playsAlone_asAShort() {
        // A link from the phone, or a list that is gone (the process was killed): the one Short that is known.
        val none = ShortsViewModel(emptyList(), startVideoId = "ddddddddddd")
        assertThat(ids(none)).containsExactly("ddddddddddd")
        assertThat(none.current.isShortForm).isTrue()
        assertThat(none.current.url).isEqualTo("https://www.youtube.com/shorts/ddddddddddd")
        assertThat(none.next()).isFalse()
        assertThat(none.previous()).isFalse()

        // A list that does not hold the Short that was asked for does not stand in for it.
        val other = ShortsViewModel(listOf(a, b), startVideoId = "ddddddddddd")
        assertThat(ids(other)).containsExactly("ddddddddddd")
    }

    // ---- getting the next Short ready ---------------------------------------------------------------------------------

    private val prefetched = CopyOnWriteArrayList<String>()
    private val cancelled = CopyOnWriteArrayList<String>()
    private val gates = HashMap<String, CompletableDeferred<Unit>>()

    private fun vm(start: String, fail: Set<String> = emptySet()) = ShortsViewModel(listOf(a, b, c), start) { short: VideoItem ->
        prefetched += short.videoId
        val gate = gates.getOrPut(short.videoId) { CompletableDeferred() }
        try {
            gate.await()
        } catch (e: CancellationException) {
            cancelled += short.videoId
            throw e
        }
        if (short.videoId in fail) throw SourceException(SourceErrorKind.NETWORK, "UnknownHostException", short.videoId, null)
    }

    @Test
    fun theNextShortIsGotReady_andOnlyTheNextOne() {
        val vm = vm("aaaaaaaaaaa")
        vm.prefetchAhead()
        assertThat(prefetched).containsExactly("bbbbbbbbbbb")
        // Asked again for the same Short (the player says it plays again after a pause): the one before is replaced, never two at a time.
        vm.prefetchAhead()
        assertThat(prefetched).containsExactly("bbbbbbbbbbb", "bbbbbbbbbbb").inOrder()
        assertThat(cancelled).containsExactly("bbbbbbbbbbb")
    }

    @Test
    fun movingOn_dropsTheOneThatWasBeingGotReady() {
        val vm = vm("aaaaaaaaaaa")
        vm.prefetchAhead()
        vm.next()
        // The Short that was being got ready is on screen now: its stream is the player's to get, not the prefetch's.
        assertThat(cancelled).containsExactly("bbbbbbbbbbb")
        // ...and moving on already starts getting the one after it ready (no wait for the new Short to play).
        assertThat(prefetched).containsExactly("bbbbbbbbbbb", "ccccccccccc").inOrder()
    }

    @Test
    fun theNextTwoAreGotReady_nearestFirst() {
        val vm = vm("aaaaaaaaaaa")
        assertThat(vm.prefetchWindow().map { it.videoId }).containsExactly("bbbbbbbbbbb", "ccccccccccc").inOrder()
        vm.prefetchAhead()
        assertThat(prefetched).containsExactly("bbbbbbbbbbb")
        gates.getValue("bbbbbbbbbbb").complete(Unit)
        assertThat(prefetched).containsExactly("bbbbbbbbbbb", "ccccccccccc").inOrder()
        // Near the end the window is shorter.
        assertThat(vm("bbbbbbbbbbb").prefetchWindow().map { it.videoId }).containsExactly("ccccccccccc")
        assertThat(vm("ccccccccccc").prefetchWindow()).isEmpty()
    }

    @Test
    fun atTheLastShort_thereIsNothingToGetReady() {
        val vm = vm("ccccccccccc")
        vm.prefetchAhead()
        assertThat(prefetched).isEmpty()
    }

    @Test
    fun aFailureToGetItReady_isNothing_theShortLoadsWhenItIsReached() {
        val vm = vm("aaaaaaaaaaa", fail = setOf("bbbbbbbbbbb"))
        vm.prefetchAhead()
        gates.getValue("bbbbbbbbbbb").complete(Unit)
        // Nothing is thrown at the screen, and the list works as before.
        assertThat(vm.next()).isTrue()
        assertThat(vm.current).isEqualTo(b)
    }
}
