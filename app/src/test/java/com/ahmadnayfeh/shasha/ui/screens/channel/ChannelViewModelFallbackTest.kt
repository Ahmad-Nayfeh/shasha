package com.ahmadnayfeh.shasha.ui.screens.channel

import android.app.Application
import android.util.Log
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.data.ADULT_PROFILE_ID
import com.ahmadnayfeh.shasha.data.MutableProfile
import com.ahmadnayfeh.shasha.data.NOW_MS
import com.ahmadnayfeh.shasha.data.ScriptedChannelSource
import com.ahmadnayfeh.shasha.data.SubscriptionRepository
import com.ahmadnayfeh.shasha.data.WatchRepository
import com.ahmadnayfeh.shasha.data.channelId
import com.ahmadnayfeh.shasha.data.db.ShashaDatabase
import com.ahmadnayfeh.shasha.data.inMemoryDatabase
import com.ahmadnayfeh.shasha.data.video
import com.ahmadnayfeh.shasha.policy.Allowlist
import com.ahmadnayfeh.shasha.policy.ContentFilter
import com.ahmadnayfeh.shasha.policy.ListedChannel
import com.ahmadnayfeh.shasha.policy.PolicyEngine
import com.ahmadnayfeh.shasha.policy.Profile
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import com.ahmadnayfeh.shasha.ui.model.VideoUi
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.time.Clock

/**
 * A channel's page (adult's and child's) must not fail where one way of reading it does: the first page of videos comes from
 * the Videos tab or, when that fails, from the same list as a playlist, and paging continues from that playlist; a header
 * that cannot be loaded (the channel's own page) does not hide the videos that were; only a channel that fails everywhere
 * (deleted, terminated) shows the error screen. The ways are scripted one by one (ScriptedChannelSource).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class ChannelViewModelFallbackTest {
    private lateinit var db: ShashaDatabase
    private val source = ScriptedChannelSource()
    private val engine = PolicyEngine(Clock.systemUTC())
    private val channel = channelId(1)
    private val longForm = "UULF" + channel.drop(2)
    private val owner = Profile.newAdult("الكبار", id = ADULT_PROFILE_ID)
    private val child = Profile.newChild("سارة", id = "kid").copy(allowlist = Allowlist(channels = listOf(ListedChannel(channel, "قناة"))))
    private val rules = MutableStateFlow(ContentFilter.of(engine, owner))
    private val viewModels = ViewModelStore()

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        db = inMemoryDatabase()
        ShadowLog.clear()
    }

    @After
    fun tearDown() {
        viewModels.clear()
        db.close()
        Dispatchers.resetMain()
    }

    private fun model(): ChannelViewModel {
        val subscriptions = SubscriptionRepository(db, source)
        val watch = WatchRepository(db, MutableProfile())
        return ViewModelProvider(viewModels, viewModelFactory { initializer { ChannelViewModel(channel, source, subscriptions, watch, CoroutineScope(Dispatchers.Unconfined), rules) } })[ChannelViewModel::class.java]
    }

    /** The header has been asked for (and the picture it brought stored, a database write on another thread): the test may end. */
    private fun ChannelViewModel.awaitHeader() = runBlocking { withTimeout(5_000) { header.first { it !is ChannelHeader.Loading } } }

    private fun v(n: Int, seconds: Long? = 600, short: Boolean = false) =
        video("vid" + n.toString().padStart(8, '0'), channel, NOW_MS - n * 60_000L, durationSeconds = seconds, short = short)

    @Test
    fun whenTheVideosTabFails_thePageStartsFromThePlaylist_andPagingContinuesFromIt() {
        source.tabFails(channel)
        source.playlist(longForm, (1..30).map { v(it) }, next = "k1")
        source.playlist(longForm, (31..40).map { v(it) }, key = "k1")
        val vm = model()
        vm.awaitHeader()

        assertThat(vm.items.value.error).isNull()
        assertThat(vm.items.value.items).hasSize(30)
        assertThat(vm.items.value.endReached).isFalse()
        assertThat(source.calls).containsExactly("channel:$channel", "tab:$channel", "playlist:$longForm").inOrder()

        // The focus nears the end of the list: the next page comes from the playlist, never from the tab (its keys are not the playlist's).
        vm.loadMore()
        assertThat(vm.items.value.items).hasSize(40)
        assertThat(vm.items.value.endReached).isTrue()
        assertThat(source.calls).containsExactly("channel:$channel", "tab:$channel", "playlist:$longForm", "playlist:$longForm:k1").inOrder()
        // The page itself asked the tab (once) and logged its failure; the uploads then started from the playlist.
        assertThat(ShadowLog.getLogsForTag("ChannelViewModel").filter { it.type == Log.WARN }.single().msg).contains(channel)
    }

    @Test
    fun theVideosTabAnswering_changesNothing() {
        source.tab(channel, (1..30).map { v(it) }, next = "t2")
        source.tab(channel, (31..35).map { v(it) }, key = "t2")
        val vm = model()
        vm.awaitHeader()
        vm.loadMore()
        assertThat(vm.items.value.items).hasSize(35)
        assertThat(source.calls).containsExactly("channel:$channel", "tab:$channel", "tab:$channel:t2").inOrder()
    }

    @Test
    fun aHeaderThatFails_doesNotHideTheVideosThatLoaded() {
        source.headerFails(channel)
        source.tab(channel, (1..3).map { v(it) })
        val vm = model()
        vm.awaitHeader()

        assertThat(vm.items.value.error).isNull()
        assertThat(vm.items.value.items).hasSize(3)
        // The name the videos give stands in for the header: the page opens with its videos (the screen shows the error only for a Failed header).
        val header = vm.header.value as ChannelHeader.Ready
        assertThat(header.details.channel.channelId).isEqualTo(channel)
        assertThat(header.details.channel.name).isEqualTo("Name of $channel")
        assertThat(ShadowLog.getLogsForTag("ChannelViewModel").filter { it.type == Log.WARN }.single().msg).contains(channel)
    }

    @Test
    fun aHeaderThatFailsWhileTheFirstPageLoads_isHeldBack_noErrorScreenThatGivesWayToThePage() {
        source.headerFails(channel)
        source.tab(channel, (1..3).map { v(it) })
        val gate = CompletableDeferred<Unit>()
        source.beforeTab = { gate.await() }
        val vm = model()

        // The header failed at once, the first page is still on its way: neither the error nor a header yet.
        assertThat(vm.items.value.loadingFirst).isTrue()
        assertThat(vm.header.value).isEqualTo(ChannelHeader.Loading)
        gate.complete(Unit)
        vm.awaitHeader()
        assertThat(vm.header.value).isInstanceOf(ChannelHeader.Ready::class.java)
        assertThat(vm.items.value.items).hasSize(3)
    }

    @Test
    fun aChannelThatFailsEverywhere_keepsTheErrorScreen() {
        // Deleted or terminated: the header, the Videos tab and the playlist all fail. The screen's error (with «إلغاء الاشتراك») is for this.
        source.headerFails(channel)
        source.tabFails(channel)
        source.playlistFails(longForm)
        val vm = model()
        vm.awaitHeader()

        assertThat(vm.header.value).isInstanceOf(ChannelHeader.Failed::class.java)
        assertThat(vm.items.value.error?.technicalName).isEqualTo("ContentNotAvailableException")
        assertThat(vm.items.value.items).isEmpty()
    }

    @Test
    fun anAdultWhoShowsShorts_getsRssAsTheLastWay_withoutTheFeedsShorts() {
        source.tabFails(channel)
        source.playlistFails(longForm)
        source.feed(channel, listOf(v(1, seconds = null), v(2, seconds = null, short = true), v(3, seconds = null)))
        val vm = model()
        vm.awaitHeader()

        assertThat(vm.items.value.error).isNull()
        assertThat(vm.items.value.items.map { (it as VideoUi).video.videoId }).containsExactly("vid00000001", "vid00000003").inOrder()
        // One page: RSS has no more.
        assertThat(vm.items.value.endReached).isTrue()
        vm.loadMore()
        assertThat(source.calls.filter { it.startsWith("rss:") }).hasSize(1)
    }

    @Test
    fun aChildWhoHidesShorts_getsNoRss_theRowsHaveNoDurationAndThePlayerWouldRefuseTheShortOnes() {
        rules.value = ContentFilter.of(engine, child)
        source.tabFails(channel)
        source.playlistFails(longForm)
        source.feed(channel, listOf(v(1, seconds = null), v(2, seconds = null)))
        val vm = model()
        vm.awaitHeader()

        assertThat(source.calls.filter { it.startsWith("rss:") }).isEmpty()
        assertThat(vm.items.value.error?.technicalName).isEqualTo("ContentNotAvailableException")
        assertThat(vm.items.value.items).isEmpty()
    }
}
