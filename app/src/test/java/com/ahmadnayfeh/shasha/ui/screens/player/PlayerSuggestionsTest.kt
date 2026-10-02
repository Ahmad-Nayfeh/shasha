package com.ahmadnayfeh.shasha.ui.screens.player

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.TestStreams
import com.ahmadnayfeh.shasha.data.StreamRepository
import com.ahmadnayfeh.shasha.policy.clip
import com.ahmadnayfeh.shasha.ui.FakeUiSource
import com.ahmadnayfeh.shasha.youtube.SourceErrorKind
import com.ahmadnayfeh.shasha.youtube.SourceException
import com.ahmadnayfeh.shasha.youtube.StreamDetails
import com.ahmadnayfeh.shasha.youtube.VideoItem
import com.ahmadnayfeh.shasha.youtube.VideoSource
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The player's «مقترحة»: asked for once the video is extracted (so VideoSource.related answers from that extraction),
 * once per visit, never before; a failure only leaves the row empty.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class PlayerSuggestionsTest {
    private val video = clip("playing0001")
    private val events = CopyOnWriteArrayList<String>()

    private val source: VideoSource = object : VideoSource by FakeUiSource() {
        override suspend fun stream(videoId: String, allowLowQuality: Boolean): StreamDetails {
            events += "stream $videoId"
            return TestStreams.details(video)
        }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun player(suggestionsFor: (suspend (String) -> List<VideoItem>)?) =
        PlayerViewModel(video.videoId, StreamRepository(source), suggestionsFor = suggestionsFor)

    @Test
    fun askedOnceTheVideoIsExtracted_andOnlyOnce() = runBlocking<Unit> {
        val vm = player { id ->
            events += "suggestions $id"
            listOf(clip("related0001"), clip("related0002"))
        }
        val shown = withTimeout(5_000) { vm.suggestions.first { it.isNotEmpty() } }
        assertThat(shown.map { it.videoId }).containsExactly("related0001", "related0002").inOrder()
        // The extraction first: its related items are what the source answers with.
        assertThat(events).containsExactly("stream playing0001", "suggestions playing0001").inOrder()

        // A fresh extraction later in the visit (the network came back, URLs went stale) asks nothing more.
        vm.fail(SourceException(SourceErrorKind.NETWORK, "SocketTimeoutException", null, null))
        vm.retry()
        withTimeout(5_000) { vm.load.first { it is PlayerViewModel.Load.Ready } }
        assertThat(events.count { it.startsWith("stream") }).isEqualTo(2)
        assertThat(events.count { it.startsWith("suggestions") }).isEqualTo(1)
    }

    @Test
    fun aFailure_leavesTheRowEmpty() = runBlocking<Unit> {
        val vm = player { throw SourceException(SourceErrorKind.NETWORK, "UnknownHostException", null, null) }
        withTimeout(5_000) { vm.load.first { it is PlayerViewModel.Load.Ready } }
        assertThat(vm.suggestions.value).isEmpty()
    }

    @Test
    fun withoutSuggestions_theRowStaysEmpty() = runBlocking<Unit> {
        val vm = player(null)
        withTimeout(5_000) { vm.load.first { it is PlayerViewModel.Load.Ready } }
        assertThat(vm.suggestions.value).isEmpty()
    }

    @Test
    fun aVideoThatCannotPlay_asksForNoSuggestions() = runBlocking<Unit> {
        val broken: VideoSource = object : VideoSource by FakeUiSource() {}
        val asked = CopyOnWriteArrayList<String>()
        val vm = PlayerViewModel(video.videoId, StreamRepository(broken), suggestionsFor = { id -> asked += id; listOf(clip("related0001")) })
        withTimeout(5_000) { vm.load.first { it is PlayerViewModel.Load.Failed } }
        assertThat(asked).isEmpty()
        assertThat(vm.suggestions.value).isEmpty()
    }
}
