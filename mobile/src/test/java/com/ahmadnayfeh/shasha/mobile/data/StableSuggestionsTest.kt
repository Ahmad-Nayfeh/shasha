package com.ahmadnayfeh.shasha.mobile.data

import com.ahmadnayfeh.shasha.mobile.video
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The rules of what the Home's suggestion rows show ([StableSuggestions]): they change at a start of the app, when the viewer asks,
 * and when the viewer comes back to the Home after a video played; never because the screen was left and opened again, a list
 * arrived meanwhile, or the history changed under it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StableSuggestionsTest {
    private val live = MutableStateFlow(HomeSuggestions.Suggested())

    private fun rows(vararg ids: String, working: Boolean = false, withShorts: Boolean = true) =
        HomeSuggestions.Suggested(videos = ids.map { video(it) }, working = working, withShorts = withShorts)

    private val StateFlow<HomeSuggestions.Suggested>.ids get() = value.videos.map { it.videoId }

    /** What a Home screen shows: a new collector each time (a screen that is opened again, a recreated view model). */
    private fun TestScope.screen(board: StableSuggestions): StateFlow<HomeSuggestions.Suggested> =
        board.present(live).stateIn(backgroundScope + UnconfinedTestDispatcher(testScheduler), SharingStarted.Eagerly, HomeSuggestions.Suggested())

    @Test
    fun atTheStartTheRowsFillAsTheFirstRoundBringsListsAndThenHoldStill() = runTest {
        val board = StableSuggestions()
        val shown = screen(board)
        assertThat(shown.ids).isEmpty()

        // The first round: the file's lists, then each fresh answer, all shown while it runs.
        live.value = rows("file1", working = true)
        assertThat(shown.ids).containsExactly("file1")
        live.value = rows("fresh1", "file1", working = true)
        assertThat(shown.ids).containsExactly("fresh1", "file1").inOrder()
        live.value = rows("fresh1", "fresh2", "file1")
        assertThat(shown.ids).containsExactly("fresh1", "fresh2", "file1").inOrder()
        assertThat(board.isOpen).isFalse()

        // Afterwards nothing that changes in the ranking reaches the rows.
        live.value = rows("other", "fresh1")
        live.value = rows("other", "fresh1", working = true)
        live.value = rows("again", "other")
        assertThat(shown.ids).containsExactly("fresh1", "fresh2", "file1").inOrder()
    }

    @Test
    fun leavingTheHomeAndComingBackShowsTheSameRows_alsoToANewScreen() = runTest {
        val board = StableSuggestions()
        live.value = rows("a", "b")
        val first = screen(board)
        assertThat(first.ids).containsExactly("a", "b").inOrder()

        // Away from the Home (another tab, a channel), the lists change.
        live.value = rows("c", "a", "b")
        // The Home opened again, by the same screen or a new one (its view model was recreated).
        board.onHomeShown()
        assertThat(first.ids).containsExactly("a", "b").inOrder()
        assertThat(screen(board).ids).containsExactly("a", "b").inOrder()
    }

    @Test
    fun aRefreshTheViewerAsksForShowsWhatItsRoundBrings() = runTest {
        val board = StableSuggestions()
        live.value = rows("a")
        val shown = screen(board)
        live.value = rows("b", "a")
        assertThat(shown.ids).containsExactly("a")

        val round = CompletableDeferred<Unit>()
        val refresh = launch {
            board.hold {
                live.value = rows("b", "a", working = true)
                round.await()
                live.value = rows("c", "b", "a")
            }
        }
        runCurrent()
        assertThat(shown.ids).containsExactly("b", "a").inOrder() // open from the moment it was asked
        round.complete(Unit)
        refresh.join()
        assertThat(shown.ids).containsExactly("c", "b", "a").inOrder()

        // Held again once the round is over.
        live.value = rows("d")
        assertThat(shown.ids).containsExactly("c", "b", "a").inOrder()
    }

    @Test
    fun aRefreshThatFindsNothingNewShowsTheLatestRankingAndHoldsAgain() = runTest {
        val board = StableSuggestions()
        live.value = rows("a")
        val shown = screen(board)
        live.value = rows("b", "a") // came while the rows were held

        board.hold { }

        assertThat(shown.ids).containsExactly("b", "a").inOrder()
        live.value = rows("c")
        assertThat(shown.ids).containsExactly("b", "a").inOrder()
    }

    @Test
    fun comingBackToTheHomeAfterAVideoPlayedShowsTheNewRows() = runTest {
        val board = StableSuggestions()
        live.value = rows("a")
        val shown = screen(board)

        // A video plays (the Home under the watch page): its suggestions come, the rows do not move yet.
        board.markPlayed("v1")
        live.value = rows("from-v1", "a")
        assertThat(shown.ids).containsExactly("a")

        // Back on the Home: they change, once.
        board.onHomeShown()
        assertThat(shown.ids).containsExactly("from-v1", "a").inOrder()
        live.value = rows("later", "from-v1")
        board.onHomeShown()
        assertThat(shown.ids).containsExactly("from-v1", "a").inOrder()
    }

    @Test
    fun theSameVideoPlayingAgain_pausedAndResumed_countsOnce() = runTest {
        val board = StableSuggestions()
        live.value = rows("a")
        val shown = screen(board)
        board.markPlayed("v1")
        board.onHomeShown()

        live.value = rows("b")
        board.markPlayed("v1") // resumed in the mini player
        board.onHomeShown()
        assertThat(shown.ids).containsExactly("a")

        board.markPlayed("v2")
        board.onHomeShown()
        assertThat(shown.ids).containsExactly("b")
    }

    @Test
    fun aPlaybackWhileTheRoundIsStillRunningKeepsTheRowsOpenUntilItEnds() = runTest {
        val board = StableSuggestions()
        live.value = rows("a")
        val shown = screen(board)
        board.markPlayed("v1")
        live.value = rows("a", working = true)
        board.onHomeShown()
        live.value = rows("from-v1", "a", working = true)
        assertThat(shown.ids).containsExactly("from-v1", "a").inOrder()
        live.value = rows("from-v1", "x", "a")
        assertThat(shown.ids).containsExactly("from-v1", "x", "a").inOrder()
        live.value = rows("y")
        assertThat(shown.ids).containsExactly("from-v1", "x", "a").inOrder()
    }

    @Test
    fun switchingShortsAndClearingTheHistoryComeThroughAtOnce() = runTest {
        val board = StableSuggestions()
        live.value = rows("a")
        val shown = screen(board)

        live.value = rows("a", "b", withShorts = false)
        assertThat(shown.value.withShorts).isFalse()
        assertThat(shown.ids).containsExactly("a", "b").inOrder()

        live.value = rows()
        assertThat(shown.value.isEmpty).isTrue()
    }

    @Test
    fun aNewProcessStartsOpen() {
        assertThat(StableSuggestions().isOpen).isTrue()
    }
}
