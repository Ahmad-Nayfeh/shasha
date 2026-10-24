package com.ahmadnayfeh.shasha.mobile.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.mobile.FakeDownloadDao
import com.ahmadnayfeh.shasha.mobile.FakeFeedDao
import com.ahmadnayfeh.shasha.mobile.FakeHistoryDao
import com.ahmadnayfeh.shasha.mobile.FakeProgressDao
import com.ahmadnayfeh.shasha.mobile.FakeSubscriptionDao
import com.ahmadnayfeh.shasha.mobile.FakeVideoSource
import com.ahmadnayfeh.shasha.mobile.FakeWatchLaterDao
import com.ahmadnayfeh.shasha.mobile.MainDispatcherRule
import com.ahmadnayfeh.shasha.mobile.common.Loadable
import com.ahmadnayfeh.shasha.mobile.container
import com.ahmadnayfeh.shasha.mobile.data.FeedRepository
import com.ahmadnayfeh.shasha.mobile.data.LibraryRepository
import com.ahmadnayfeh.shasha.mobile.data.LocalPlaylistIds
import com.ahmadnayfeh.shasha.mobile.data.NOW_MS
import com.ahmadnayfeh.shasha.mobile.data.SubscriptionRepository
import com.ahmadnayfeh.shasha.mobile.data.db.DownloadEntity
import com.ahmadnayfeh.shasha.mobile.data.db.DownloadKind
import com.ahmadnayfeh.shasha.mobile.download.DeleteOutcome
import com.ahmadnayfeh.shasha.mobile.download.DownloadManager
import com.ahmadnayfeh.shasha.mobile.download.DownloadNotifications
import com.ahmadnayfeh.shasha.mobile.download.MediaSaver
import com.ahmadnayfeh.shasha.mobile.download.PendingMedia
import com.ahmadnayfeh.shasha.mobile.download.Remuxer
import com.ahmadnayfeh.shasha.mobile.player.PlayerPhase
import com.ahmadnayfeh.shasha.mobile.player.PlayerUiState
import com.ahmadnayfeh.shasha.mobile.testSettings
import com.ahmadnayfeh.shasha.mobile.ui.watch.WatchEvent
import com.ahmadnayfeh.shasha.mobile.ui.watch.WatchViewModel
import com.ahmadnayfeh.shasha.mobile.video
import com.ahmadnayfeh.shasha.youtube.PlaylistPage
import com.ahmadnayfeh.shasha.youtube.VideoItem
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileDescriptor

/**
 * What the player screen plays after the video: the viewer's own playlist (a `local:` id, read from the database, in the order
 * its videos were added) or a YouTube playlist (its pages), and what its related videos hold back when Shorts are off. The
 * player's state is a flow of the test's own; a download id keeps the screen from starting the player, which Robolectric cannot run.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = QuietSourceApp::class)
class WatchViewModelPlaylistTest {
    @get:Rule
    val main = MainDispatcherRule()

    @get:Rule
    val folder = TemporaryFolder()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val source = FakeVideoSource()
    private val subsDao = FakeSubscriptionDao()
    private val feedDao = FakeFeedDao()
    private val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val settings by lazy { testSettings(File(folder.root, "s.preferences_pb"), storeScope) }

    @After
    fun tearDown() = storeScope.cancel()

    private fun idleDownloads() = DownloadManager(
        dao = FakeDownloadDao(),
        source = { error("not used") },
        fetcher = { _, _, _ -> error("not used") },
        remuxer = object : Remuxer {
            override suspend fun mux(video: File?, audio: File, output: FileDescriptor, onProgress: (Float) -> Unit) = Unit
        },
        saver = object : MediaSaver {
            override fun create(kind: DownloadKind, displayName: String): PendingMedia = error("not used")
            override fun delete(uri: String): DeleteOutcome = DeleteOutcome.Deleted
            override fun exists(uri: String): Boolean = true
        },
        workDir = File(folder.root, "work"),
        scope = storeScope,
        notifications = object : DownloadNotifications {
            override fun completed(download: DownloadEntity) = Unit
            override fun failed(download: DownloadEntity) = Unit
        },
    )

    private fun viewModel(
        videoId: String,
        playlistId: String?,
        state: MutableStateFlow<PlayerUiState> = MutableStateFlow(PlayerUiState(phase = PlayerPhase.READY, video = video(videoId))),
        showShorts: () -> Boolean = { true },
        local: suspend (Long) -> List<VideoItem> = { emptyList() },
    ) = WatchViewModel(
        controller = context.container.player,
        source = source,
        subscriptions = SubscriptionRepository(subsDao, feedDao) { NOW_MS },
        feed = FeedRepository(source, subsDao, feedDao, settings, now = { NOW_MS }),
        library = LibraryRepository(FakeHistoryDao(), FakeWatchLaterDao(), FakeProgressDao()) { NOW_MS },
        downloads = idleDownloads(),
        videoId = videoId,
        playlistId = playlistId,
        downloadId = 1L,
        startMs = null,
        playerState = state,
        showShorts = showShorts,
        localVideos = local,
    )

    @Test
    fun aListOfTheViewersOwnIsTheQueue_inTheOrderItsVideosWereAdded_withoutAskingYouTube() = runTest(main.dispatcher) {
        source.onPlaylist = { _, _ -> error("a list of the viewer's own is not asked of YouTube") }
        var asked: Long? = null
        val vm = viewModel("b", LocalPlaylistIds.of(7)) { id -> asked = id; listOf(video("a"), video("b"), video("c")) }

        assertThat(asked).isEqualTo(7L)
        assertThat(vm.queueState.value.items.map { it.videoId }).containsExactly("a", "b", "c").inOrder()
        assertThat(vm.queueState.value.endReached).isTrue() // all of it came at once
        assertThat(vm.queueState.value.error).isNull()
        assertThat(vm.playlistId).isEqualTo("local:7")
    }

    @Test
    fun whenAVideoOfTheListEndsTheNextOneOfTheListIsOpened() = runTest(main.dispatcher) {
        val state = MutableStateFlow(PlayerUiState(phase = PlayerPhase.READY, video = video("b")))
        val vm = viewModel("b", LocalPlaylistIds.of(7), state) { listOf(video("a"), video("b"), video("c")) }

        state.value = state.value.copy(phase = PlayerPhase.ENDED)

        val event = vm.eventFlow.first()
        assertThat((event as WatchEvent.PlayNext).video.videoId).isEqualTo("c")
    }

    @Test
    fun theLastVideoOfTheListEndsTheListWithoutAnEvent() = runTest(main.dispatcher) {
        val state = MutableStateFlow(PlayerUiState(phase = PlayerPhase.READY, video = video("c")))
        val vm = viewModel("c", LocalPlaylistIds.of(7), state) { listOf(video("a"), video("b"), video("c")) }
        val events = ArrayList<WatchEvent>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.eventFlow.collect { events += it } }

        state.value = state.value.copy(phase = PlayerPhase.ENDED)

        assertThat(events).isEmpty()
        job.cancel()
    }

    @Test
    fun whenTheLastLoadedVideoEndsTheNextPageIsLoadedAndItsFirstVideoOpened() = runTest(main.dispatcher) {
        // A YouTube playlist whose first loaded page ends with the video that plays; its next page could not be read at first.
        val asked = ArrayList<String?>()
        source.onPlaylist = { id, page ->
            asked += page?.id
            if (page == null) {
                PlaylistPage(com.ahmadnayfeh.shasha.mobile.playlist(id), listOf(video("p1"), video("p2")), com.ahmadnayfeh.shasha.youtube.PageKey("2"))
            } else if (asked.count { it == "2" } == 1) {
                throw com.ahmadnayfeh.shasha.mobile.failure() // the first load stops after the first page (as a long playlist's does)
            } else {
                PlaylistPage(com.ahmadnayfeh.shasha.mobile.playlist(id), listOf(video("p3"), video("p4")), null)
            }
        }
        val state = MutableStateFlow(PlayerUiState(phase = PlayerPhase.READY, video = video("p2")))
        val vm = viewModel("p2", "PLabcdefghijklmnop", state)
        assertThat(vm.queueState.value.items.map { it.videoId }).containsExactly("p1", "p2").inOrder()
        assertThat(vm.queueState.value.endReached).isFalse()

        state.value = state.value.copy(phase = PlayerPhase.ENDED)

        val event = vm.eventFlow.first()
        assertThat((event as WatchEvent.PlayNext).video.videoId).isEqualTo("p3")
        assertThat(vm.queueState.value.items.map { it.videoId }).containsExactly("p1", "p2", "p3", "p4").inOrder()
    }

    @Test
    fun aYouTubePlaylistStillComesFromYouTube() = runTest(main.dispatcher) {
        source.onPlaylist = { id, _ -> PlaylistPage(com.ahmadnayfeh.shasha.mobile.playlist(id), listOf(video("y1"), video("y2")), null) }
        var localAsked = false
        val vm = viewModel("y1", "PLabcdefghijklmnop") { localAsked = true; emptyList() }

        assertThat(vm.queueState.value.items.map { it.videoId }).containsExactly("y1", "y2").inOrder()
        assertThat(localAsked).isFalse()
    }

    @Test
    fun withShortsOffTheRelatedVideosHoldNone_withThemOnTheyAreAllThere() = runTest(main.dispatcher) {
        source.onRelated = { listOf(video("v1"), video("s1").copy(isShortForm = true), video("v2")) }

        val off = viewModel("x", playlistId = null, showShorts = { false })
        off.retryRelated()
        assertThat((off.related.value as Loadable.Loaded).value.map { it.videoId }).containsExactly("v1", "v2").inOrder()

        val on = viewModel("x", playlistId = null, showShorts = { true })
        on.retryRelated()
        assertThat((on.related.value as Loadable.Loaded).value.map { it.videoId }).containsExactly("v1", "s1", "v2").inOrder()
    }
}
