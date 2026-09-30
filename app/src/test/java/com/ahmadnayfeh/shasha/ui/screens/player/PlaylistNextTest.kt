package com.ahmadnayfeh.shasha.ui.screens.player

import com.ahmadnayfeh.shasha.player.NextInPlaylist
import com.ahmadnayfeh.shasha.player.PlayQueue
import com.ahmadnayfeh.shasha.player.PlayQueues
import com.ahmadnayfeh.shasha.ui.FakeUiSource
import com.ahmadnayfeh.shasha.ui.UiFixtures
import com.ahmadnayfeh.shasha.youtube.PageKey
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** Inside a playlist the next video follows (SPEC: the only autoplay); nothing follows the last one. */
class PlaylistNextTest {
    private val source = FakeUiSource()
    private val queues = PlayQueues()
    private val next = NextInPlaylist(queues, source)
    private val v = UiFixtures.videos

    @Test
    fun theNextVideoComesFromTheQueueHandedOverByThePlaylistPage() = runTest {
        queues.put(PlayQueue("PL1", listOf(v[0], v[1], v[2]), next = null))
        assertThat(next.after("PL1", 0)).isEqualTo(v[1])
        assertThat(next.after("PL1", 1)).isEqualTo(v[2])
        assertThat(next.after("PL1", 2)).isNull()
        assertThat(source.playlistCalls).isEmpty()
    }

    @Test
    fun atTheEndOfWhatWasLoadedTheNextPageIsFetchedOnce() = runTest {
        queues.put(PlayQueue("PL1", listOf(v[0], v[1]), next = PageKey("p2")))
        source.playlistPages[PageKey("p2")] = listOf(v[1], v[2], v[3]) // v[1] repeats across pages
        assertThat(next.after("PL1", 1)).isEqualTo(v[2])
        assertThat(next.after("PL1", 2)).isEqualTo(v[3])
        assertThat(next.after("PL1", 3)).isNull()
        assertThat(source.playlistCalls).containsExactly(PageKey("p2"))
    }

    @Test
    fun afterTheProcessWasKilledThePlaylistIsLoadedAgain() = runTest {
        source.playlistPages[null] = listOf(v[4], v[5])
        assertThat(next.after("PL9", 0)).isEqualTo(v[5])
        assertThat(queues.get("PL9")?.videos).containsExactly(v[4], v[5]).inOrder()
    }

    @Test
    fun onlyTheLastTwoPlaylistsAreKept() {
        val queues = PlayQueues()
        queues.put(PlayQueue("PL1", listOf(v[0]), next = null))
        queues.put(PlayQueue("PL2", listOf(v[1]), next = null))
        queues.get("PL1") // used again: PL2 is now the oldest
        queues.put(PlayQueue("PL3", listOf(v[2]), next = null))
        assertThat(queues.get("PL2")).isNull()
        assertThat(queues.get("PL1")?.videos).containsExactly(v[0])
        assertThat(queues.get("PL3")?.videos).containsExactly(v[2])
    }
}
