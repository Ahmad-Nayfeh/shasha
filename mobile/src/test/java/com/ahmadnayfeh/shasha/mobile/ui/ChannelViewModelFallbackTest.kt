package com.ahmadnayfeh.shasha.mobile.ui

import com.ahmadnayfeh.shasha.mobile.FakeFeedDao
import com.ahmadnayfeh.shasha.mobile.FakeSubscriptionDao
import com.ahmadnayfeh.shasha.mobile.FakeVideoSource
import com.ahmadnayfeh.shasha.mobile.MainDispatcherRule
import com.ahmadnayfeh.shasha.mobile.ScriptedChannelSource
import com.ahmadnayfeh.shasha.mobile.channelId
import com.ahmadnayfeh.shasha.mobile.common.Loadable
import com.ahmadnayfeh.shasha.mobile.data.FeedRepository
import com.ahmadnayfeh.shasha.mobile.data.NOW_MS
import com.ahmadnayfeh.shasha.mobile.data.SubscriptionRepository
import com.ahmadnayfeh.shasha.mobile.data.db.SubscriptionEntity
import com.ahmadnayfeh.shasha.mobile.failure
import com.ahmadnayfeh.shasha.mobile.playlist
import com.ahmadnayfeh.shasha.mobile.testSettings
import com.ahmadnayfeh.shasha.mobile.ui.channel.ChannelHeader
import com.ahmadnayfeh.shasha.mobile.ui.channel.ChannelViewModel
import com.ahmadnayfeh.shasha.mobile.video
import com.ahmadnayfeh.shasha.youtube.ChannelDetails
import com.ahmadnayfeh.shasha.youtube.ChannelItem
import com.ahmadnayfeh.shasha.youtube.SourceErrorKind
import com.ahmadnayfeh.shasha.youtube.SourceException
import com.ahmadnayfeh.shasha.youtube.VideoItem
import com.ahmadnayfeh.shasha.youtube.VideoSource
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * A channel's page must not dead-end where one way of reading it fails, as it did on a phone on mobile data in Saudi Arabia
 * that got `ContentNotAvailableException` for the channel's page and tabs: the header is made of what is known (the
 * subscription, what the opening screen passed, the channel's videos) with a quiet way to ask again; the videos come from the
 * uploads playlists and then RSS, and paging goes on from the list that answered; the Shorts from the Shorts playlist; and
 * subscribing works from that header. The ways are scripted one by one ([ScriptedChannelSource]), so each can fail alone.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChannelViewModelFallbackTest {
    @get:Rule
    val main = MainDispatcherRule()

    @get:Rule
    val folder = TemporaryFolder()

    private val source = ScriptedChannelSource()
    private val subsDao = FakeSubscriptionDao()
    private val feedDao = FakeFeedDao()
    private val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val settings by lazy { testSettings(File(folder.root, "s.preferences_pb"), storeScope) }

    private val channel = channelId(1)
    private val longForm = "UULF" + channel.drop(2)
    private val everyUpload = "UU" + channel.drop(2)
    private val shortsList = "UUSH" + channel.drop(2)
    private val avatar = "https://yt3.ggpht.com/ytc/AIdro_kXyz=s176-c-k-c0x00ffffff-no-rj"

    @After
    fun tearDown() = storeScope.cancel()

    private fun viewModel(id: String = channel, hint: ChannelItem? = null, from: VideoSource = source) = ChannelViewModel(
        id, from, SubscriptionRepository(subsDao, feedDao) { NOW_MS }, FeedRepository(from, subsDao, feedDao, settings, now = { NOW_MS }), hint,
    )

    private suspend fun follow(name: String, avatarUrl: String?, id: String = channel) =
        subsDao.upsert(SubscriptionEntity(channelId = id, name = name, avatarUrl = avatarUrl, subscribedAt = NOW_MS))

    private fun v(n: Int, name: String? = "Name of the channel", of: String? = channel): VideoItem =
        video("vid" + n.toString().padStart(8, '0'), channelId = of, channelName = name)

    private fun ChannelViewModel.ready() = header.value as ChannelHeader.Ready

    /** The screen collects [ChannelViewModel.subscribed] (it is only kept while someone does). */
    private fun TestScope.collecting(vm: ChannelViewModel) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.subscribed.collect {} }
    }

    // ---- the header, when the channel's page fails -----------------------------------------------------------------------

    @Test
    fun theReportedCaseEveryPageOfTheChannelFailsAndTheSubscriptionHasNoPicture() = runTest(main.dispatcher) {
        // «أحمد السيد» and «عبدالله العجيري», subscribed from the player screen while the channel's page failed: stored without a picture.
        // On that phone the channel's page, its Videos tab and its Shorts tab all answer with ContentNotAvailableException.
        val reported = mapOf("UC7AbKZa30XPMInZIsViiBOQ" to "أحمد السيد", "UC3m94fTQg5dVIyoLcWtw2Rg" to "عبدالله العجيري")
        for ((id, name) in reported) {
            follow(name, avatarUrl = null, id = id)
            source.headerFails(id)
            source.tabFails(id)
            source.shortsTabFails(id)
            source.playlist("UULF" + id.drop(2), (1..3).map { v(it, name = name, of = id) })
            source.playlist("UUSH" + id.drop(2), listOf(v(9, name = name, of = id)))
        }

        for ((id, name) in reported) {
            val vm = viewModel(id)
            vm.loadShorts()

            // No error anywhere: the header is the subscription's, the videos and the Shorts are the playlists'.
            val header = vm.ready()
            assertThat(header.channel.channelId).isEqualTo(id)
            assertThat(header.channel.name).isEqualTo(name)
            assertThat(header.complete).isFalse()
            assertThat(vm.videoState.value.error).isNull()
            assertThat(vm.videoState.value.items).hasSize(3)
            assertThat((vm.shorts.value as Loadable.Loaded).value).hasSize(1)
        }
    }

    @Test
    fun whenTheChannelsPageFailsTheHeaderIsTheSubscriptionsNameAndPicture() = runTest(main.dispatcher) {
        follow("أحمد السيد", avatar)
        source.headerFails(channel)

        val vm = viewModel()

        val header = vm.ready()
        assertThat(header.complete).isFalse()
        assertThat(header.refreshing).isFalse()
        assertThat(header.channel.channelId).isEqualTo(channel)
        assertThat(header.channel.name).isEqualTo("أحمد السيد")
        assertThat(header.channel.avatarUrl).isEqualTo(avatar)
        assertThat(header.canSubscribe).isTrue()
        assertThat(source.calls).contains("channel:$channel")
    }

    @Test
    fun theHeaderIsWhatTheOpeningScreenPassedWhenTheChannelIsNotFollowed() = runTest(main.dispatcher) {
        source.headerFails(channel)

        val vm = viewModel(hint = ChannelItem(channel, "عبدالله العجيري", avatar, subscriberCount = 1_200_000))

        val header = vm.ready()
        assertThat(header.complete).isFalse()
        assertThat(header.channel.name).isEqualTo("عبدالله العجيري")
        assertThat(header.channel.avatarUrl).isEqualTo(avatar)
        assertThat(header.channel.subscriberCount).isEqualTo(1_200_000)
    }

    @Test
    fun whatIsStoredAndWhatWasPassedAreCombinedFieldByField() = runTest(main.dispatcher) {
        // The subscription was saved without a picture; the search result the viewer tapped has one.
        follow("Stored name", avatarUrl = null)
        source.headerFails(channel)

        val vm = viewModel(hint = ChannelItem(channel, "Name from the result", avatar, null))

        assertThat(vm.ready().channel.name).isEqualTo("Stored name")
        assertThat(vm.ready().channel.avatarUrl).isEqualTo(avatar)
    }

    @Test
    fun withNeitherTheHeaderTakesTheNameFromTheChannelsFirstVideosAndHasNoPicture() = runTest(main.dispatcher) {
        source.headerFails(channel)
        source.tabFails(channel)
        source.playlist(longForm, listOf(v(1, name = "Name from the videos"), v(2)))

        val vm = viewModel()

        val header = vm.ready()
        assertThat(header.channel.name).isEqualTo("Name from the videos")
        // The screen draws the initial of the name for the missing picture.
        assertThat(header.channel.avatarUrl).isNull()
        assertThat(header.complete).isFalse()
        assertThat(vm.videoState.value.items).hasSize(2)
    }

    @Test
    fun aSubscriptionSavedUnderItsIdHasNoNameToShow() = runTest(main.dispatcher) {
        follow(name = channel, avatarUrl = null)
        source.headerFails(channel)

        val vm = viewModel(hint = ChannelItem(channel, "Name from the result", null, null))

        assertThat(vm.ready().channel.name).isEqualTo("Name from the result")
    }

    @Test
    fun theIdPassedAsANameIsNoNameEitherTheVideosNameTheChannelInstead() = runTest(main.dispatcher) {
        // The home screen passes the stored name of a subscription that was saved without one: its id.
        source.headerFails(channel)
        source.tabFails(channel)
        source.playlist(longForm, listOf(v(1, name = "Name from the videos")))

        val vm = viewModel(hint = ChannelItem(channel, channel, null, null))

        assertThat(vm.ready().channel.name).isEqualTo("Name from the videos")
    }

    @Test
    fun withNothingKnownThePageWaitsForTheHeaderThenShowsAnEmptyOneAndNeverAnError() = runTest(main.dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val slow = FakeVideoSource().apply { onChannel = { gate.await(); throw failure(SourceErrorKind.CONTENT_UNAVAILABLE, "ContentNotAvailableException") } }

        val vm = viewModel(from = slow)
        assertThat(vm.header.value).isEqualTo(ChannelHeader.Loading)

        gate.complete(Unit)

        val header = vm.ready()
        assertThat(header.channel.channelId).isEqualTo(channel)
        assertThat(header.channel.name).isEmpty()
        assertThat(header.complete).isFalse()
        assertThat(header.refreshing).isFalse()
    }

    @Test
    fun withSomethingKnownThePageOpensAtOnceAndFillsInWhenTheChannelsPageArrives() = runTest(main.dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val slow = FakeVideoSource().apply {
            onChannel = { gate.await(); ChannelDetails(ChannelItem(it, "Full name", "https://yt3.ggpht.com/full", 5_000), null, "About it") }
        }
        follow("Stored name", avatar)

        val vm = viewModel(from = slow)

        // The subscription's name and picture are there while the page is on its way, with a spinner for it.
        val waiting = vm.ready()
        assertThat(waiting.channel.name).isEqualTo("Stored name")
        assertThat(waiting.refreshing).isTrue()
        assertThat(waiting.complete).isFalse()

        gate.complete(Unit)

        val full = vm.ready()
        assertThat(full.complete).isTrue()
        assertThat(full.channel.name).isEqualTo("Full name")
        assertThat(full.channel.subscriberCount).isEqualTo(5_000)
        assertThat(full.description).isEqualTo("About it")
        assertThat(full.refreshing).isFalse()
    }

    @Test
    fun theQuietTryAgainAsksTheChannelsPageAgainAndTheHeaderBecomesComplete() = runTest(main.dispatcher) {
        follow("Stored name", avatarUrl = null)
        source.headerFails(channel)
        val vm = viewModel()
        assertThat(vm.ready().complete).isFalse()

        source.header(channel, ChannelDetails(ChannelItem(channel, "Full name", avatar, 7), null, null))
        vm.retryHeader()

        assertThat(vm.ready().complete).isTrue()
        assertThat(vm.ready().channel.avatarUrl).isEqualTo(avatar)
        assertThat(source.calls.filter { it == "channel:$channel" }).hasSize(2)
    }

    @Test
    fun aTryAgainWhileTheChannelsPageIsOnItsWayAsksNothingMore() = runTest(main.dispatcher) {
        val gate = CompletableDeferred<Unit>()
        var asked = 0
        val slow = FakeVideoSource().apply { onChannel = { asked++; gate.await(); throw failure() } }
        follow("Stored name", avatarUrl = null)
        val vm = viewModel(from = slow)

        vm.retryHeader()
        vm.retryHeader()
        gate.complete(Unit)

        assertThat(asked).isEqualTo(1)
        vm.retryHeader()
        assertThat(asked).isEqualTo(2)
    }

    @Test
    fun aChannelTheContentFilterRefusesIsRefusedAndNotRebuiltFromWhatIsKnown() = runTest(main.dispatcher) {
        // The filter holds back a channel by name or description: the page must not show its name and picture from the subscription.
        follow("Held back name", avatar)
        source.headerFails(channel, SourceException(SourceErrorKind.CONTENT_UNAVAILABLE, "ContentFiltered", "Held back by the built-in content filter", null))
        val vm = viewModel(hint = ChannelItem(channel, "Held back name", avatar, null))

        val header = vm.header.value as ChannelHeader.Refused
        assertThat(header.error.technicalName).isEqualTo("ContentFiltered")
        assertThat(header.error.kind).isEqualTo(SourceErrorKind.CONTENT_UNAVAILABLE)

        // Nothing to subscribe to or to ask again from there.
        vm.toggleSubscribe()
        vm.retryHeader()
        vm.retryHeader()
        assertThat(subsDao.rows.value).hasSize(1) // the subscription is as it was: the page did not toggle it
        assertThat(vm.header.value).isInstanceOf(ChannelHeader.Refused::class.java)
    }

    @Test
    fun anyOtherFailureOfTheChannelsPageIsMadeLightOf() = runTest(main.dispatcher) {
        follow("Name", avatar)
        for (error in listOf(
            ScriptedChannelSource.contentUnavailable(),
            ScriptedChannelSource.offline(),
            SourceException(SourceErrorKind.BLOCKED_403, "HTTP 403", "forbidden", null),
            SourceException(SourceErrorKind.ACCOUNT_TERMINATED, "AccountTerminatedException", "gone", null),
            SourceException(SourceErrorKind.EXTRACTION_BROKEN, "ParsingException", "changed", null),
        )) {
            source.headerFails(channel, error)

            assertThat(viewModel().header.value).isInstanceOf(ChannelHeader.Ready::class.java)
        }
    }

    // ---- the videos ----------------------------------------------------------------------------------------------------

    @Test
    fun whenTheVideosTabFailsTheVideosComeFromTheLongFormPlaylistAndPagingContinuesFromIt() = runTest(main.dispatcher) {
        source.headerFails(channel)
        source.tabFails(channel)
        source.playlist(longForm, (1..12).map { v(it) }, next = "k1")
        source.playlist(longForm, (13..15).map { v(it) }, key = "k1")

        val vm = viewModel()

        assertThat(vm.videoState.value.error).isNull()
        assertThat(vm.videoState.value.items).hasSize(12)
        assertThat(vm.videoState.value.endReached).isFalse()

        // The list nears its end: the next page comes from the playlist, never from the tab (its keys are not the playlist's).
        vm.loadMoreVideos()
        assertThat(vm.videoState.value.items).hasSize(15)
        assertThat(vm.videoState.value.endReached).isTrue()
        assertThat(source.calls.filter { !it.startsWith("channel:") })
            .containsExactly("tab:$channel", "playlist:$longForm", "playlist:$longForm:k1").inOrder()
    }

    @Test
    fun whenTheLongFormPlaylistFailsTooTheUploadsPlaylistGivesTheVideos() = runTest(main.dispatcher) {
        source.headerFails(channel)
        source.tabFails(channel)
        source.playlistFails(longForm)
        source.playlist(everyUpload, (1..3).map { v(it) })

        val vm = viewModel()

        assertThat(vm.videoState.value.error).isNull()
        assertThat(vm.videoState.value.items).hasSize(3)
        assertThat(vm.videoState.value.endReached).isTrue()
    }

    @Test
    fun whenEveryPlaylistFailsTooTheFeedGivesTheVideosInOnePageWithoutItsShorts() = runTest(main.dispatcher) {
        source.headerFails(channel)
        source.tabFails(channel)
        source.playlistFails(longForm)
        source.playlistFails(everyUpload)
        source.feed(channel, listOf(v(1), v(2).copy(isShortForm = true), v(3)))

        val vm = viewModel()
        vm.loadMoreVideos()

        assertThat(vm.videoState.value.error).isNull()
        assertThat(vm.videoState.value.items.map { it.videoId }).containsExactly("vid00000001", "vid00000003").inOrder()
        assertThat(vm.videoState.value.endReached).isTrue()
        assertThat(source.calls.filter { it.startsWith("rss:") }).hasSize(1)
    }

    @Test
    fun aChannelThatFailsEverywhereShowsTheFailureInItsVideosListOnlyAndLoadsWhenAskedAgain() = runTest(main.dispatcher) {
        follow("Stored name", avatar)
        source.headerFails(channel)
        val vm = viewModel()

        // The page is still the page: its header, and the error only where the videos would be.
        assertThat(vm.header.value).isInstanceOf(ChannelHeader.Ready::class.java)
        assertThat(vm.ready().channel.name).isEqualTo("Stored name")
        assertThat(vm.videoState.value.error?.technicalName).isEqualTo("ContentNotAvailableException")
        assertThat(vm.videoState.value.items).isEmpty()

        source.header(channel, ChannelDetails(ChannelItem(channel, "Stored name", avatar, 1), null, null))
        source.tab(channel, listOf(v(1)))
        vm.retry()

        assertThat(vm.videoState.value.error).isNull()
        assertThat(vm.videoState.value.items).hasSize(1)
        assertThat(vm.ready().complete).isTrue()
    }

    @Test
    fun aConnectivityFailureAsksNoMoreThanTheTabTheListSaysSoAndTheHeaderStaysQuiet() = runTest(main.dispatcher) {
        follow("Stored name", avatar)
        source.headerFails(channel, ScriptedChannelSource.offline())
        source.tabFails(channel, ScriptedChannelSource.offline())
        source.playlist(longForm, listOf(v(1)))

        val vm = viewModel()

        assertThat(vm.videoState.value.error?.kind).isEqualTo(SourceErrorKind.NETWORK)
        assertThat(source.calls.filter { it.startsWith("playlist:") || it.startsWith("rss:") }).isEmpty()
        assertThat(vm.ready().channel.name).isEqualTo("Stored name")
    }

    // ---- the Shorts and the Playlists ------------------------------------------------------------------------------------

    @Test
    fun whenTheShortsTabFailsTheShortsComeFromTheShortsPlaylist() = runTest(main.dispatcher) {
        source.shortsTabFails(channel)
        source.playlist(shortsList, listOf(v(1), v(2)))
        val vm = viewModel()

        vm.loadShorts()

        val shorts = (vm.shorts.value as Loadable.Loaded).value
        assertThat(shorts.map { it.videoId }).containsExactly("vid00000001", "vid00000002").inOrder()
        assertThat(shorts.all { it.isShortForm }).isTrue()
    }

    @Test
    fun shortsThatFailEverywhereCanBeAskedForAgain() = runTest(main.dispatcher) {
        source.shortsTabFails(channel)
        source.playlistFails(shortsList)
        val vm = viewModel()

        vm.loadShorts()
        assertThat(vm.shorts.value).isInstanceOf(Loadable.Failed::class.java)

        source.shortsTab(channel, listOf(v(1)))
        vm.loadShorts(force = true)

        assertThat((vm.shorts.value as Loadable.Loaded).value).hasSize(1)
    }

    @Test
    fun aPlaylistsTabThatFailsFailsOnlyInItsOwnList() = runTest(main.dispatcher) {
        follow("Stored name", avatar)
        source.headerFails(channel)
        source.tab(channel, listOf(v(1)))
        val vm = viewModel()

        vm.loadPlaylists()

        assertThat(vm.playlistState.value.started).isTrue()
        assertThat(vm.playlistState.value.items).isEmpty()
        assertThat(vm.playlistState.value.error).isNotNull()
        // The rest of the page does not care.
        assertThat(vm.videoState.value.items).hasSize(1)
        assertThat(vm.ready().channel.name).isEqualTo("Stored name")

        source.playlistsTab(channel, listOf(playlist("PL1")))
        vm.loadMorePlaylists()
        assertThat(vm.playlistState.value.items.map { it.playlistId }).containsExactly("PL1")
    }

    // ---- subscribing from a header made of what is known -----------------------------------------------------------------

    @Test
    fun subscribingFromTheFallbackHeaderStoresWhatIsKnownBringsTheFeedAndTheButtonFlips() = runTest(main.dispatcher) {
        source.headerFails(channel)
        source.feed(channel, listOf(v(1).copy(publishedAtEpochMs = 5)))
        val vm = viewModel(hint = ChannelItem(channel, "عبدالله العجيري", avatar, null))
        collecting(vm)
        assertThat(vm.subscribed.value).isFalse()

        vm.toggleSubscribe()

        val stored = subsDao.rows.value.single()
        assertThat(stored.channelId).isEqualTo(channel)
        assertThat(stored.name).isEqualTo("عبدالله العجيري")
        assertThat(stored.avatarUrl).isEqualTo(avatar)
        assertThat(vm.subscribed.value).isTrue()
        assertThat(feedDao.rows.value.map { it.videoId }).containsExactly("vid00000001")

        vm.toggleSubscribe()

        assertThat(subsDao.rows.value).isEmpty()
        assertThat(vm.subscribed.value).isFalse()
        assertThat(feedDao.rows.value).isEmpty()
    }

    @Test
    fun aSubscribedChannelWhosePageFailsCanBeUnsubscribedFromItsHeader() = runTest(main.dispatcher) {
        follow("Stored name", avatar)
        source.headerFails(channel)
        val vm = viewModel()
        collecting(vm)
        assertThat(vm.subscribed.value).isTrue()

        vm.toggleSubscribe()

        assertThat(subsDao.rows.value).isEmpty()
        assertThat(vm.subscribed.value).isFalse()
    }

    @Test
    fun aPageOpenedWithAHandleIsSubscribedUnderTheIdItsVideosName() = runTest(main.dispatcher) {
        source.headerFails("@kids")
        source.tab("@kids", listOf(v(1, name = "Kids channel")))
        val vm = viewModel(id = "@kids")
        collecting(vm)

        assertThat(vm.ready().channel.channelId).isEqualTo(channel)
        assertThat(vm.ready().channel.name).isEqualTo("Kids channel")
        assertThat(vm.ready().canSubscribe).isTrue()

        vm.toggleSubscribe()

        assertThat(subsDao.rows.value.map { it.channelId }).containsExactly(channel)
        assertThat(vm.subscribed.value).isTrue()
    }

    @Test
    fun aPageOpenedWithAHandleCannotBeSubscribedToUntilSomethingSaysWhichChannelItIs() = runTest(main.dispatcher) {
        source.headerFails("@kids")
        val vm = viewModel(id = "@kids")

        assertThat(vm.ready().canSubscribe).isFalse()
        vm.toggleSubscribe()

        assertThat(subsDao.rows.value).isEmpty()
    }

    @Test
    fun aChannelSubscribedWithoutAnyNameIsStoredUnderItsIdAndTheNextPageThatLoadsFillsInTheNameAndPicture() = runTest(main.dispatcher) {
        source.headerFails(channel)
        val vm = viewModel()

        vm.toggleSubscribe()

        // Nothing named the channel: the id stands in for the name, as the screens never show an empty one.
        assertThat(subsDao.rows.value.single().name).isEqualTo(channel)
        assertThat(subsDao.rows.value.single().avatarUrl).isNull()

        source.header(channel, ChannelDetails(ChannelItem(channel, "The real name", avatar, 9), null, null))
        vm.retryHeader()

        val stored = subsDao.rows.value.single()
        assertThat(stored.name).isEqualTo("The real name")
        assertThat(stored.avatarUrl).isEqualTo(avatar)
    }

    // ---- the stored details follow the channel's page ------------------------------------------------------------------

    @Test
    fun aPageOfTheChannelThatLoadsFillsInTheMissingPictureOfItsSubscription() = runTest(main.dispatcher) {
        follow("Name", avatarUrl = null)
        source.header(channel, ChannelDetails(ChannelItem(channel, "Name", avatar, 100), null, null))

        viewModel()

        assertThat(subsDao.rows.value.single().avatarUrl).isEqualTo(avatar)
    }

    @Test
    fun aPageOfTheChannelThatLoadsReplacesAChangedNameAndPicture() = runTest(main.dispatcher) {
        follow("Old name", "https://yt3.ggpht.com/old")
        source.header(channel, ChannelDetails(ChannelItem(channel, "New name", avatar, 100), null, null))

        viewModel()

        val stored = subsDao.rows.value.single()
        assertThat(stored.name).isEqualTo("New name")
        assertThat(stored.avatarUrl).isEqualTo(avatar)
    }

    @Test
    fun aPageWithoutAPictureKeepsTheStoredOne() = runTest(main.dispatcher) {
        follow("Name", avatar)
        source.header(channel, ChannelDetails(ChannelItem(channel, "Name", null, 100), null, null))

        viewModel()

        assertThat(subsDao.rows.value.single().avatarUrl).isEqualTo(avatar)
    }

    @Test
    fun aPageOfAChannelThatIsNotFollowedSubscribesNothing() = runTest(main.dispatcher) {
        source.header(channel, ChannelDetails(ChannelItem(channel, "Name", avatar, 100), null, null))

        val vm = viewModel()

        assertThat(vm.ready().complete).isTrue()
        assertThat(subsDao.rows.value).isEmpty()
    }
}
