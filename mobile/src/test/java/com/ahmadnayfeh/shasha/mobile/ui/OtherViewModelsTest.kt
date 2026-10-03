package com.ahmadnayfeh.shasha.mobile.ui

import com.ahmadnayfeh.shasha.mobile.FakeDownloadDao
import com.ahmadnayfeh.shasha.mobile.FakeFeedDao
import com.ahmadnayfeh.shasha.mobile.FakeHistoryDao
import com.ahmadnayfeh.shasha.mobile.FakeProgressDao
import com.ahmadnayfeh.shasha.mobile.FakeSubscriptionDao
import com.ahmadnayfeh.shasha.mobile.FakeVideoSource
import com.ahmadnayfeh.shasha.mobile.FakeWatchLaterDao
import com.ahmadnayfeh.shasha.mobile.MainDispatcherRule
import com.ahmadnayfeh.shasha.mobile.awaitReal
import com.ahmadnayfeh.shasha.mobile.channel
import com.ahmadnayfeh.shasha.mobile.common.Loadable
import com.ahmadnayfeh.shasha.mobile.data.AppLanguage
import com.ahmadnayfeh.shasha.mobile.data.AppSettings
import com.ahmadnayfeh.shasha.mobile.data.AppTheme
import com.ahmadnayfeh.shasha.mobile.data.FeedRepository
import com.ahmadnayfeh.shasha.mobile.data.LibraryRepository
import com.ahmadnayfeh.shasha.mobile.data.NOW_MS
import com.ahmadnayfeh.shasha.mobile.data.SubscriptionRepository
import com.ahmadnayfeh.shasha.mobile.data.db.DownloadEntity
import com.ahmadnayfeh.shasha.mobile.data.db.DownloadKind
import com.ahmadnayfeh.shasha.mobile.data.db.DownloadStatus
import com.ahmadnayfeh.shasha.mobile.download.DeleteOutcome
import com.ahmadnayfeh.shasha.mobile.download.DownloadManager
import com.ahmadnayfeh.shasha.mobile.download.DownloadNotifications
import com.ahmadnayfeh.shasha.mobile.download.MediaSaver
import com.ahmadnayfeh.shasha.mobile.download.PendingMedia
import com.ahmadnayfeh.shasha.mobile.download.Remuxer
import com.ahmadnayfeh.shasha.mobile.failure
import com.ahmadnayfeh.shasha.mobile.playlist
import com.ahmadnayfeh.shasha.mobile.sponsorblock.SponsorCategory
import com.ahmadnayfeh.shasha.mobile.testSettings
import com.ahmadnayfeh.shasha.mobile.ui.channel.ChannelHeader
import com.ahmadnayfeh.shasha.mobile.ui.channel.ChannelViewModel
import com.ahmadnayfeh.shasha.mobile.ui.downloads.DownloadSheetViewModel
import com.ahmadnayfeh.shasha.mobile.ui.downloads.DownloadsViewModel
import com.ahmadnayfeh.shasha.mobile.ui.library.LibraryViewModel
import com.ahmadnayfeh.shasha.mobile.ui.playlist.PlaylistViewModel
import com.ahmadnayfeh.shasha.mobile.ui.settings.SettingsViewModel
import com.ahmadnayfeh.shasha.mobile.video
import com.ahmadnayfeh.shasha.youtube.ChannelDetails
import com.ahmadnayfeh.shasha.youtube.ItemsPage
import com.ahmadnayfeh.shasha.youtube.PageKey
import com.ahmadnayfeh.shasha.youtube.PlaylistPage
import com.ahmadnayfeh.shasha.youtube.SourceErrorKind
import com.ahmadnayfeh.shasha.youtube.download.DownloadOptions
import com.ahmadnayfeh.shasha.youtube.download.DownloadStream
import com.ahmadnayfeh.shasha.youtube.download.VideoDownloadOption
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class ChannelViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    @get:Rule
    val folder = TemporaryFolder()

    private val source = FakeVideoSource()
    private val subsDao = FakeSubscriptionDao()
    private val feedDao = FakeFeedDao()
    private val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val settings by lazy { testSettings(File(folder.root, "s.preferences_pb"), storeScope) }

    @After
    fun tearDown() = storeScope.cancel()

    private fun viewModel(id: String = "UC1") = ChannelViewModel(
        id, source, SubscriptionRepository(subsDao, feedDao) { NOW_MS }, FeedRepository(source, subsDao, feedDao, settings, now = { NOW_MS }),
    )

    @Test
    fun theDetailsAndTheFirstVideosLoadWhenThePageOpens() = runTest(main.dispatcher) {
        source.onChannel = { ChannelDetails(channel(it, "Rick"), "https://banner", "About the channel") }
        source.onChannelVideos = { id, _ -> ItemsPage(listOf(video("a", channelId = id), video("b", channelId = id)), null) }

        val vm = viewModel()

        val header = vm.header.value as ChannelHeader.Ready
        assertThat(header.complete).isTrue()
        assertThat(header.channel.name).isEqualTo("Rick")
        assertThat(header.description).isEqualTo("About the channel")
        assertThat(vm.videoState.value.items.map { it.videoId }).containsExactly("a", "b").inOrder()
    }

    @Test
    fun subscribingStoresTheChannelBringsItsUploadsToTheFeedAndTheButtonFlips() = runTest(main.dispatcher) {
        source.onRecent = { id -> listOf(video("new", channelId = id, publishedAt = 5)) }
        val vm = viewModel("UC1")
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.subscribed.collect {} }
        assertThat(vm.subscribed.value).isFalse()

        vm.toggleSubscribe()

        assertThat(subsDao.rows.value.map { it.channelId }).containsExactly("UC1")
        assertThat(vm.subscribed.value).isTrue()
        assertThat(feedDao.rows.value.map { it.videoId }).containsExactly("new")

        vm.toggleSubscribe()
        assertThat(subsDao.rows.value).isEmpty()
        assertThat(vm.subscribed.value).isFalse()
        assertThat(feedDao.rows.value).isEmpty() // its uploads leave the feed with it
    }

    @Test
    fun theSubscriptionKeepsTheRealIdWhenThePageWasOpenedWithAHandle() = runTest(main.dispatcher) {
        source.onChannel = { ChannelDetails(channel("UCreal0000000000000000", "Handle channel"), null, null) }
        val vm = viewModel("@handle")

        vm.toggleSubscribe()

        assertThat(subsDao.rows.value.map { it.channelId }).containsExactly("UCreal0000000000000000")
    }

    @Test
    fun aChannelNothingIsKnownOfCannotBeSubscribedTo() = runTest(main.dispatcher) {
        // Its page fails, there is no subscription, nothing was passed and no video names it: which channel it is, is not known.
        source.onChannel = { throw failure() }
        val vm = viewModel()

        vm.toggleSubscribe()

        assertThat(subsDao.rows.value).isEmpty()
    }

    @Test
    fun shortsAndPlaylistsLoadOnlyWhenTheirTabOpens() = runTest(main.dispatcher) {
        var shortsAsked = 0
        var playlistsAsked = 0
        source.onChannelShorts = { shortsAsked++; listOf(video("s1")) }
        source.onChannelPlaylists = { _, _ -> playlistsAsked++; ItemsPage(listOf(playlist("PL1"), playlist("PL2")), null) }
        val vm = viewModel()
        assertThat(shortsAsked).isEqualTo(0)
        assertThat(playlistsAsked).isEqualTo(0)

        vm.loadShorts()
        vm.loadShorts() // the tab opened again: not asked again
        vm.loadPlaylists()

        assertThat(shortsAsked).isEqualTo(1)
        assertThat((vm.shorts.value as Loadable.Loaded).value.map { it.videoId }).containsExactly("s1")
        assertThat(playlistsAsked).isEqualTo(1)
        assertThat(vm.playlistState.value.items.map { it.playlistId }).containsExactly("PL1", "PL2").inOrder()
    }

    @Test
    fun aChannelWithoutPlaylistsOrShortsHasEmptyLists() = runTest(main.dispatcher) {
        val vm = viewModel()

        vm.loadShorts()
        vm.loadPlaylists()

        assertThat((vm.shorts.value as Loadable.Loaded).value).isEmpty()
        assertThat(vm.playlistState.value.started).isTrue()
        assertThat(vm.playlistState.value.items).isEmpty()
    }

    @Test
    fun theVideosPageOnAndAFailedShortsListCanBeRetried() = runTest(main.dispatcher) {
        source.onChannelVideos = { _, page -> if (page == null) ItemsPage(List(12) { video("a$it") }, PageKey("2")) else ItemsPage(List(3) { video("b$it") }, null) }
        var fail = true
        source.onChannelShorts = { if (fail) throw failure() else listOf(video("s")) }
        val vm = viewModel()

        vm.loadMoreVideos()
        assertThat(vm.videoState.value.items).hasSize(15)

        vm.loadShorts()
        assertThat(vm.shorts.value).isInstanceOf(Loadable.Failed::class.java)
        fail = false
        vm.loadShorts(force = true)
        assertThat((vm.shorts.value as Loadable.Loaded).value).hasSize(1)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    @Test
    fun theFirstPageBringsTheTitleAndTheVideos() = runTest(main.dispatcher) {
        val source = FakeVideoSource()
        source.onPlaylist = { id, page ->
            if (page == null) PlaylistPage(playlist(id, "Road trip"), List(12) { video("a$it") }, PageKey("2"))
            else PlaylistPage(playlist(id, "Road trip"), List(4) { video("b$it") }, null)
        }

        val vm = PlaylistViewModel("PL123", source)

        assertThat(vm.details.value!!.title).isEqualTo("Road trip")
        assertThat(vm.videoState.value.items).hasSize(12)

        vm.loadMore()
        assertThat(vm.videoState.value.items).hasSize(16)
        assertThat(vm.videoState.value.endReached).isTrue()
    }

    @Test
    fun aPlaylistThatFailsCanBeRetried() = runTest(main.dispatcher) {
        val source = FakeVideoSource()
        var fail = true
        source.onPlaylist = { id, _ -> if (fail) throw failure(SourceErrorKind.PRIVATE, "PrivateContentException") else PlaylistPage(playlist(id), listOf(video("a")), null) }

        val vm = PlaylistViewModel("PL123", source)
        assertThat(vm.videoState.value.error!!.kind).isEqualTo(SourceErrorKind.PRIVATE)

        fail = false
        vm.retry()
        assertThat(vm.videoState.value.items).hasSize(1)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val historyDao = FakeHistoryDao()
    private val watchLaterDao = FakeWatchLaterDao()
    private val subsDao = FakeSubscriptionDao()
    private val feedDao = FakeFeedDao()
    private var now = 1L
    private val library = LibraryRepository(historyDao, watchLaterDao, FakeProgressDao()) { now++ }

    private fun viewModel() = LibraryViewModel(library, SubscriptionRepository(subsDao, feedDao) { now++ })

    @Test
    fun theListsAreUnknownUntilTheyAreReadThenTheyAre() = runTest(main.dispatcher) {
        val vm = viewModel()
        assertThat(vm.history.value).isNull()

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.history.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.watchLater.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.channels.collect {} }

        assertThat(vm.history.value).isEmpty()
        assertThat(vm.watchLater.value).isEmpty()
        assertThat(vm.channels.value).isEmpty()
    }

    @Test
    fun historyAndWatchLaterCanBeEditedAndEmptied() = runTest(main.dispatcher) {
        val vm = viewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.history.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.watchLater.collect {} }
        library.recordWatched(video("a"))
        library.recordWatched(video("b"))
        library.toggleWatchLater(video("c"))
        library.toggleWatchLater(video("d"))
        assertThat(vm.history.value!!.map { it.videoId }).containsExactly("b", "a").inOrder()

        vm.removeFromHistory("b")
        assertThat(vm.history.value!!.map { it.videoId }).containsExactly("a")
        vm.clearHistory()
        assertThat(vm.history.value).isEmpty()

        vm.removeFromWatchLater("c")
        assertThat(vm.watchLater.value!!.map { it.videoId }).containsExactly("d")
        vm.clearWatchLater()
        assertThat(vm.watchLater.value).isEmpty()
    }

    @Test
    fun unsubscribingRemovesTheChannel() = runTest(main.dispatcher) {
        val vm = viewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.channels.collect {} }
        SubscriptionRepository(subsDao, feedDao) { now++ }.subscribe(channel("UC1", "One"))
        SubscriptionRepository(subsDao, feedDao) { now++ }.subscribe(channel("UC2", "Two"))
        assertThat(vm.channels.value!!.map { it.name }).containsExactly("One", "Two").inOrder()

        vm.unsubscribe("UC1")

        assertThat(vm.channels.value!!.map { it.name }).containsExactly("Two")
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    @get:Rule
    val folder = TemporaryFolder()

    private val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() = storeScope.cancel()

    @Test
    fun changesAreStoredAndShownAndEachGroupResets() = runTest(main.dispatcher) {
        val vm = SettingsViewModel(testSettings(File(folder.root, "s.preferences_pb"), storeScope))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.settings.collect {} }
        assertThat(vm.settings.value).isEqualTo(AppSettings())

        vm.setLanguage(AppLanguage.ARABIC)
        vm.setTheme(AppTheme.DARK)
        vm.setDefaultQuality(720)
        vm.setDownloadQuality(480)
        vm.setSponsorCategory(SponsorCategory.OUTRO, true)
        vm.setBackgroundPlayback(false)
        awaitReal { vm.settings.value.backgroundPlayback.not() && vm.settings.value.language == AppLanguage.ARABIC }

        assertThat(vm.settings.value.theme).isEqualTo(AppTheme.DARK)
        assertThat(vm.settings.value.defaultQuality).isEqualTo(720)
        assertThat(vm.settings.value.downloadQuality).isEqualTo(480)
        assertThat(vm.settings.value.sponsorCategories).contains(SponsorCategory.OUTRO)

        vm.resetLanguage()
        awaitReal { vm.settings.value.language == AppLanguage.SYSTEM }
        assertThat(vm.settings.value.theme).isEqualTo(AppTheme.DARK)

        vm.resetAll()
        awaitReal { vm.settings.value == AppSettings() }
        assertThat(vm.settings.value).isEqualTo(AppSettings())
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadViewModelsTest {
    @get:Rule
    val main = MainDispatcherRule()

    @get:Rule
    val folder = TemporaryFolder()

    private val dao = FakeDownloadDao()
    private val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var consent: DeleteOutcome = DeleteOutcome.Deleted
    private val deleted = ArrayList<String>()

    @After
    fun tearDown() = storeScope.cancel()

    private val saver = object : MediaSaver {
        override fun create(kind: DownloadKind, displayName: String): PendingMedia = error("not used")
        override fun delete(uri: String): DeleteOutcome {
            deleted += uri
            return consent
        }

        override fun exists(uri: String): Boolean = uri != "content://gone"
    }

    private val options = DownloadOptions(
        listOf(VideoDownloadOption(720, 30, DownloadStream("https://v", "video/mp4", "avc1", 1, 9_000, 136), DownloadStream("https://a", "audio/mp4", "mp4a", 1, 1_000, 140))),
        DownloadStream("https://a", "audio/mp4", "mp4a", 1, 1_000, 140),
        100,
    )

    private var optionsCalls = 0

    private val noRemuxer = object : Remuxer {
        override suspend fun mux(video: File?, audio: File, output: java.io.FileDescriptor, onProgress: (Float) -> Unit) = Unit
    }

    private fun manager(scope: CoroutineScope) = DownloadManager(
        dao, { optionsCalls++; options }, { _, target, _ -> target.parentFile?.mkdirs(); target.writeText("x"); 1 },
        noRemuxer, saver, File(folder.root, "work"), scope, object : DownloadNotifications {
            override fun completed(download: DownloadEntity) = Unit
            override fun failed(download: DownloadEntity) = Unit
        },
    )

    private fun row(status: DownloadStatus, uri: String? = null, title: String = "T") = DownloadEntity(
        videoId = "abc", title = title, channelName = null, thumbnailUrl = null, durationSeconds = 100, kind = DownloadKind.VIDEO,
        requestedHeight = 720, height = 720, status = status, fileBytes = 5, uri = uri, displayName = null, error = null, createdAt = 1, completedAt = null,
    )

    @Test
    fun theListIsUnknownThenTheStoredDownloadsWithTheRunningOnesProgress() = runTest(main.dispatcher) {
        val manager = manager(this)
        val vm = DownloadsViewModel(manager)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.rows.collect {} }

        dao.insert(row(DownloadStatus.DONE, "content://one"))
        dao.insert(row(DownloadStatus.FAILED))

        assertThat(vm.rows.value!!.map { it.entity.status }).containsExactly(DownloadStatus.DONE, DownloadStatus.FAILED)
        assertThat(vm.rows.value!!.all { it.progress == null }).isTrue()
    }

    @Test
    fun aFileRemovedInTheGalleryDisappearsFromTheListWhenTheScreenOpens() = runTest(main.dispatcher) {
        dao.insert(row(DownloadStatus.DONE, "content://gone"))
        dao.insert(row(DownloadStatus.DONE, "content://kept"))
        val manager = manager(this)

        val vm = DownloadsViewModel(manager)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.rows.collect {} }

        awaitReal { vm.rows.value?.size == 1 }
        assertThat(vm.rows.value!!.single().entity.uri).isEqualTo("content://kept")
    }

    @Test
    fun deleteReportsWhatTheSystemSaidAndForgetDropsTheRow() = runTest(main.dispatcher) {
        val id = dao.insert(row(DownloadStatus.DONE, "content://one"))
        val manager = manager(this)
        val vm = DownloadsViewModel(manager)
        var outcome: DeleteOutcome? = null

        vm.delete(id) { outcome = it }
        assertThat(outcome).isEqualTo(DeleteOutcome.Deleted)
        assertThat(deleted).containsExactly("content://one")
        assertThat(dao.get(id)).isNull()

        val second = dao.insert(row(DownloadStatus.DONE, "content://two"))
        consent = DeleteOutcome.Failed("SecurityException")
        vm.delete(second) { outcome = it }
        assertThat(outcome).isEqualTo(DeleteOutcome.Failed("SecurityException"))
        assertThat(dao.get(second)).isNotNull()

        vm.forget(second)
        assertThat(dao.get(second)).isNull()
    }

    @Test
    fun retryAndCancelWorkOnTheirRows() = runTest(main.dispatcher) {
        val failed = dao.insert(row(DownloadStatus.FAILED))
        val manager = manager(this)
        val vm = DownloadsViewModel(manager)

        vm.retry(failed)
        // It was queued again and the streams were looked up for it (this test's saver then refuses, which is not the point).
        awaitReal { optionsCalls == 1 }
        assertThat(optionsCalls).isEqualTo(1)

        vm.cancel(failed)
        awaitReal { dao.rows.value.isEmpty() }
        assertThat(dao.get(failed)).isNull()
    }

    @Test
    fun theSheetListsWhatTheVideoCanBeDownloadedAs() = runTest(main.dispatcher) {
        val manager = manager(this)
        val settings = testSettings(File(folder.root, "s.preferences_pb"), storeScope)

        val vm = DownloadSheetViewModel({ options }, manager, settings, video("abc"))

        val loaded = (vm.state.value as Loadable.Loaded).value
        assertThat(loaded.videos.map { it.height }).containsExactly(720)
        assertThat(loaded.audio).isNotNull()
    }

    @Test
    fun aVideoThatCannotBeLookedUpShowsTheErrorAndCanBeLoadedAgain() = runTest(main.dispatcher) {
        val manager = manager(this)
        val settings = testSettings(File(folder.root, "s.preferences_pb"), storeScope)
        var fail = true
        val vm = DownloadSheetViewModel({ if (fail) throw failure(SourceErrorKind.NETWORK, "UnknownHostException") else options }, manager, settings, video("abc"))
        assertThat((vm.state.value as Loadable.Failed).error.kind).isEqualTo(SourceErrorKind.NETWORK)

        fail = false
        vm.load()

        assertThat(vm.state.value).isInstanceOf(Loadable.Loaded::class.java)
    }

    @Test
    fun startingQueuesTheDownloadInTheManagersOwnScope() = runTest(main.dispatcher) {
        val manager = manager(this)
        val settings = testSettings(File(folder.root, "s.preferences_pb"), storeScope)
        val vm = DownloadSheetViewModel({ options }, manager, settings, video("abc", title = "Clip"))

        vm.start(DownloadKind.VIDEO, 720)

        val row = dao.rows.value.single()
        assertThat(row.videoId).isEqualTo("abc")
        assertThat(row.title).isEqualTo("Clip")
        assertThat(row.requestedHeight).isEqualTo(720)
    }
}
