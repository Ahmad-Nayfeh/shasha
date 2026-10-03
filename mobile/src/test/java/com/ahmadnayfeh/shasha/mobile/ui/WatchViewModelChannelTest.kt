package com.ahmadnayfeh.shasha.mobile.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.mobile.AppContainer
import com.ahmadnayfeh.shasha.mobile.FakeDownloadDao
import com.ahmadnayfeh.shasha.mobile.FakeFeedDao
import com.ahmadnayfeh.shasha.mobile.FakeHistoryDao
import com.ahmadnayfeh.shasha.mobile.FakeProgressDao
import com.ahmadnayfeh.shasha.mobile.FakeSubscriptionDao
import com.ahmadnayfeh.shasha.mobile.FakeVideoSource
import com.ahmadnayfeh.shasha.mobile.FakeWatchLaterDao
import com.ahmadnayfeh.shasha.mobile.MainDispatcherRule
import com.ahmadnayfeh.shasha.mobile.MobileApp
import com.ahmadnayfeh.shasha.mobile.TestStreams
import com.ahmadnayfeh.shasha.mobile.channel
import com.ahmadnayfeh.shasha.mobile.container
import com.ahmadnayfeh.shasha.mobile.data.FeedRepository
import com.ahmadnayfeh.shasha.mobile.data.LibraryRepository
import com.ahmadnayfeh.shasha.mobile.data.NOW_MS
import com.ahmadnayfeh.shasha.mobile.data.SubscriptionRepository
import com.ahmadnayfeh.shasha.mobile.data.db.DownloadEntity
import com.ahmadnayfeh.shasha.mobile.data.db.DownloadKind
import com.ahmadnayfeh.shasha.mobile.data.db.SubscriptionEntity
import com.ahmadnayfeh.shasha.mobile.download.DeleteOutcome
import com.ahmadnayfeh.shasha.mobile.download.DownloadManager
import com.ahmadnayfeh.shasha.mobile.download.DownloadNotifications
import com.ahmadnayfeh.shasha.mobile.download.MediaSaver
import com.ahmadnayfeh.shasha.mobile.download.PendingMedia
import com.ahmadnayfeh.shasha.mobile.download.Remuxer
import com.ahmadnayfeh.shasha.mobile.failure
import com.ahmadnayfeh.shasha.mobile.player.PlayerPhase
import com.ahmadnayfeh.shasha.mobile.player.PlayerUiState
import com.ahmadnayfeh.shasha.mobile.testSettings
import com.ahmadnayfeh.shasha.mobile.ui.watch.WatchViewModel
import com.ahmadnayfeh.shasha.mobile.video
import com.ahmadnayfeh.shasha.youtube.ChannelDetails
import com.ahmadnayfeh.shasha.youtube.ChannelItem
import com.ahmadnayfeh.shasha.youtube.SourceErrorKind
import com.ahmadnayfeh.shasha.youtube.VideoSource
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
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

/** The app with a source that answers nothing: these tests never start the player, but the container builds it. */
class QuietSourceApp : MobileApp() {
    override fun createContainer(): AppContainer = object : AppContainer(this) {
        override val videoSource: VideoSource = FakeVideoSource()
    }
}

/**
 * The channel of the video on the player screen: where its picture comes from, and what subscribing from there stores. The
 * video's own page (the extraction's `StreamDetails`) names its uploader and has the uploader's picture, so a subscription
 * made here has it even where YouTube's page for the channel cannot be read (a phone on mobile data in Saudi Arabia gets
 * `ContentNotAvailableException` for it), which is why subscriptions were saved without a picture, and a generic person
 * icon showed on the home screen. The player's state is a flow of the test's own; a download id keeps the screen from
 * starting the player, which Robolectric cannot run.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = QuietSourceApp::class)
class WatchViewModelChannelTest {
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

    private val channelId = "UCwatchwatchwatchwatch00"
    private val picture = "https://yt3.ggpht.com/ytc/AIdro_kXyz=s176-c-k-c0x00ffffff-no-rj"
    private val watched = video("abcdefghijk", channelId = channelId, channelName = "أحمد السيد")
    private var pageAsked = 0

    @After
    fun tearDown() = storeScope.cancel()

    /** The player holding [watched], whose own page gave [withPicture] for its uploader. */
    private fun playing(withPicture: String? = picture) = PlayerUiState(
        phase = PlayerPhase.READY,
        video = watched,
        details = TestStreams.details(watched, withPicture),
    )

    /** A download manager that is never used: the screen asks it for the download its id names, which does not exist. */
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

    private fun viewModel(state: MutableStateFlow<PlayerUiState>) = WatchViewModel(
        controller = context.container.player,
        source = source,
        subscriptions = SubscriptionRepository(subsDao, feedDao) { NOW_MS },
        feed = FeedRepository(source, subsDao, feedDao, settings, now = { NOW_MS }),
        library = LibraryRepository(FakeHistoryDao(), FakeWatchLaterDao(), FakeProgressDao()) { NOW_MS },
        downloads = idleDownloads(),
        videoId = watched.videoId,
        playlistId = null,
        downloadId = 1L,
        startMs = null,
        playerState = state,
    )

    /** YouTube's page for the channel fails, as it does on the network this was found on. */
    private fun pageFails() {
        source.onChannel = { pageAsked++; throw failure(SourceErrorKind.CONTENT_UNAVAILABLE, "ContentNotAvailableException") }
    }

    private suspend fun follow(name: String, avatarUrl: String?, id: String = channelId) =
        subsDao.upsert(SubscriptionEntity(channelId = id, name = name, avatarUrl = avatarUrl, subscribedAt = NOW_MS))

    @Test
    fun theVideosOwnPageGivesTheChannelRowItsPictureWhereTheChannelsPageFails() = runTest(main.dispatcher) {
        pageFails()
        val state = MutableStateFlow(PlayerUiState(phase = PlayerPhase.LOADING, video = watched))
        val vm = viewModel(state)
        assertThat(vm.channel.value).isNull()

        state.value = playing()

        assertThat(vm.channel.value?.avatarUrl).isEqualTo(picture)
        assertThat(vm.channel.value?.channelId).isEqualTo(channelId)
        assertThat(vm.channel.value?.name).isEqualTo("أحمد السيد")
    }

    @Test
    fun subscribingStoresTheVideosOwnPictureWithoutAskingForTheChannelsPageAgain() = runTest(main.dispatcher) {
        pageFails()
        val state = MutableStateFlow(playing())
        val vm = viewModel(state)
        val askedBefore = pageAsked // the one request the screen made when it opened: it failed

        vm.toggleSubscribe()

        val stored = subsDao.rows.value.single()
        assertThat(stored.channelId).isEqualTo(channelId)
        assertThat(stored.name).isEqualTo("أحمد السيد")
        assertThat(stored.avatarUrl).isEqualTo(picture)
        assertThat(pageAsked).isEqualTo(askedBefore)
    }

    @Test
    fun withoutAnyPictureSubscribingStillAsksForTheChannelsPageAndFallsBackOnWhatTheVideoSays() = runTest(main.dispatcher) {
        pageFails()
        val state = MutableStateFlow(playing(withPicture = null))
        val vm = viewModel(state)
        val askedBefore = pageAsked

        vm.toggleSubscribe()

        assertThat(pageAsked).isEqualTo(askedBefore + 1)
        val stored = subsDao.rows.value.single()
        assertThat(stored.name).isEqualTo("أحمد السيد")
        assertThat(stored.avatarUrl).isNull()
    }

    @Test
    fun theChannelsPageAnsweringGivesThePictureTooAndAVideoPictureIsKeptWhereItHasNone() = runTest(main.dispatcher) {
        source.onChannel = { ChannelDetails(ChannelItem(it, "أحمد السيد", avatarUrl = null, subscriberCount = 2_000_000), null, null) }
        val state = MutableStateFlow(playing())

        val vm = viewModel(state)

        assertThat(vm.channel.value?.subscriberCount).isEqualTo(2_000_000)
        assertThat(vm.channel.value?.avatarUrl).isEqualTo(picture)
    }

    @Test
    fun aSubscriptionStoredWithoutAPictureGetsItWhenAVideoOfTheChannelLoads() = runTest(main.dispatcher) {
        pageFails()
        follow("أحمد السيد", avatarUrl = null)
        val state = MutableStateFlow(PlayerUiState(phase = PlayerPhase.LOADING, video = watched))
        viewModel(state)
        assertThat(subsDao.rows.value.single().avatarUrl).isNull()

        state.value = playing()

        assertThat(subsDao.rows.value.single().avatarUrl).isEqualTo(picture)
    }

    @Test
    fun aChangedNameAndPictureOfAFollowedChannelAreReplacedWhenAVideoOfItLoads() = runTest(main.dispatcher) {
        pageFails()
        follow("Old name", "https://yt3.ggpht.com/old")
        val state = MutableStateFlow(PlayerUiState(phase = PlayerPhase.LOADING, video = watched))
        viewModel(state)

        state.value = playing()

        val stored = subsDao.rows.value.single()
        assertThat(stored.name).isEqualTo("أحمد السيد")
        assertThat(stored.avatarUrl).isEqualTo(picture)
    }

    @Test
    fun aVideoOfAChannelThatIsNotFollowedStoresNothingAndNeitherDoesAnotherChannelsSubscription() = runTest(main.dispatcher) {
        pageFails()
        follow("Someone else", avatarUrl = null, id = "UCsomeoneelsesomeoneels0")
        val state = MutableStateFlow(PlayerUiState(phase = PlayerPhase.LOADING, video = watched))
        viewModel(state)

        state.value = playing()

        val stored = subsDao.rows.value.single()
        assertThat(stored.channelId).isEqualTo("UCsomeoneelsesomeoneels0")
        assertThat(stored.name).isEqualTo("Someone else")
        assertThat(stored.avatarUrl).isNull()
    }

    @Test
    fun aVideoWithoutAPictureLeavesTheStoredOneAlone() = runTest(main.dispatcher) {
        pageFails()
        follow("أحمد السيد", picture)
        val state = MutableStateFlow(PlayerUiState(phase = PlayerPhase.LOADING, video = watched))
        viewModel(state)

        state.value = playing(withPicture = null)

        assertThat(subsDao.rows.value.single().avatarUrl).isEqualTo(picture)
    }

    @Test
    fun whileANewerScreenHasThePlayerItsVideoIsNotThisScreensChannel() = runTest(main.dispatcher) {
        // Video A, its channel's page, video B: the player is the app's one, and A's screen (kept in the back stack) sees B's state.
        pageFails()
        val state = MutableStateFlow(PlayerUiState(phase = PlayerPhase.LOADING, video = watched))
        val vm = viewModel(state)
        val other = video("zzzzzzzzzzz", channelId = "UCotherotherotherother00", channelName = "Another channel")

        state.value = PlayerUiState(phase = PlayerPhase.READY, video = other, details = TestStreams.details(other, "https://yt3.ggpht.com/other"))

        // Not this screen's row (it would show B's picture next to A's name, and be what subscribing stores): B's own screen reads B's page.
        assertThat(vm.channel.value).isNull()
    }

    @Test
    fun theChannelsPageLoadingOnThePlayerScreenFillsInTheStoredPictureToo() = runTest(main.dispatcher) {
        follow("أحمد السيد", avatarUrl = null)
        source.onChannel = { ChannelDetails(channel(it, "أحمد السيد").copy(avatarUrl = picture), null, null) }

        viewModel(MutableStateFlow(PlayerUiState(phase = PlayerPhase.LOADING, video = watched)))

        assertThat(subsDao.rows.value.single().avatarUrl).isEqualTo(picture)
    }

    @Test
    fun unsubscribingStillWorks() = runTest(main.dispatcher) {
        pageFails()
        val vm = viewModel(MutableStateFlow(playing()))
        // The screen keeps `subscribed` collected, and so does this.
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.subscribed.collect {} }
        vm.toggleSubscribe()
        assertThat(subsDao.rows.value).hasSize(1)
        assertThat(vm.subscribed.value).isTrue()

        vm.toggleSubscribe()

        assertThat(subsDao.rows.value).isEmpty()
        assertThat(vm.subscribed.value).isFalse()
    }
}
