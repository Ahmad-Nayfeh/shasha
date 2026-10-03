package com.ahmadnayfeh.shasha.ui.screens.channel

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.data.ADULT_PROFILE_ID
import com.ahmadnayfeh.shasha.data.MutableProfile
import com.ahmadnayfeh.shasha.data.SubscriptionRepository
import com.ahmadnayfeh.shasha.data.WatchRepository
import com.ahmadnayfeh.shasha.data.db.ShashaDatabase
import com.ahmadnayfeh.shasha.policy.Allowlist
import com.ahmadnayfeh.shasha.policy.Blocklist
import com.ahmadnayfeh.shasha.policy.ContentFilter
import com.ahmadnayfeh.shasha.policy.ListedChannel
import com.ahmadnayfeh.shasha.policy.PolicyEngine
import com.ahmadnayfeh.shasha.policy.Profile
import com.ahmadnayfeh.shasha.ui.model.ItemUi
import com.ahmadnayfeh.shasha.ui.model.PlaylistUi
import com.ahmadnayfeh.shasha.ui.model.VideoUi
import com.ahmadnayfeh.shasha.ui.paging.ScriptedSource.Companion.CHANNEL
import com.ahmadnayfeh.shasha.ui.paging.ScriptedSource.Companion.videos
import com.ahmadnayfeh.shasha.youtube.ChannelSort
import com.ahmadnayfeh.shasha.youtube.ChannelTab
import com.ahmadnayfeh.shasha.youtube.SourceErrorKind
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Clock

/**
 * A channel's sections and orders: what is read when, what the chips offer, how every list pages, and that the profile's rules
 * hold for every item of every section. The source is a fake whose sections are scripted page by page; the view model and the
 * rules are the real ones.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class ChannelViewModelTest {
    private lateinit var db: ShashaDatabase
    private val source = FakeChannelSource()
    private val engine = PolicyEngine(Clock.systemUTC())
    private val owner = Profile.newAdult("الكبار", id = ADULT_PROFILE_ID)
    private val rules = MutableStateFlow(ContentFilter.of(engine, owner))

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        // Every database call runs right where it is made, so the whole page is settled when a call returns.
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), ShashaDatabase::class.java)
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { it.run() }
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private fun model() = ChannelViewModel(
        CHANNEL,
        source,
        SubscriptionRepository(db, source),
        WatchRepository(db, MutableProfile()),
        CoroutineScope(Dispatchers.Unconfined),
        rules,
    )

    /** Sara: a child who may open the channel; her videos hide Shorts. */
    private val child = Profile.newChild("سارة", id = "kid").copy(allowlist = Allowlist(channels = listOf(ListedChannel(CHANNEL, "Channel"))))

    private fun ids(items: List<ItemUi>) = items.filterIsInstance<VideoUi>().map { it.video.videoId }

    private fun id(n: Int) = "vid" + n.toString().padStart(8, '0')

    private fun playlist(n: Int, title: String = "Playlist $n") = FakeChannelSource.playlist(n, title)

    // ---- what is read, and when -------------------------------------------------------------------------------------------------

    @Test
    fun theVideosInYouTubesOwnOrderAreReadAtOnce_andNothingElse() {
        source.sections[ChannelTab.VIDEOS to ChannelSort.LATEST] = listOf(videos(1..30))
        val vm = model()

        assertThat(source.requests).containsExactly("VIDEOS:LATEST:0")
        assertThat(ids(vm.items.value.items)).hasSize(30)
        assertThat(vm.sections.value.selected).isEqualTo(ChannelTab.VIDEOS)
        assertThat(vm.sections.value.tabs).containsExactly(ChannelTab.VIDEOS, ChannelTab.SHORTS, ChannelTab.LIVE, ChannelTab.PLAYLISTS).inOrder()
    }

    @Test
    fun theOrdersAreThoseTheSectionsFirstPageOffered() {
        source.sections[ChannelTab.VIDEOS to ChannelSort.LATEST] = listOf(videos(1..30))
        val vm = model()

        assertThat(vm.sections.value.sorts).containsExactly(ChannelSort.LATEST, ChannelSort.POPULAR, ChannelSort.OLDEST).inOrder()
        assertThat(vm.sections.value.showsSorts).isTrue()
        assertThat(vm.sections.value.sort).isEqualTo(ChannelSort.LATEST)
    }

    @Test
    fun aSectionIsReadWhenFirstPicked_andWhatWasReadStays() {
        source.sections[ChannelTab.VIDEOS to ChannelSort.LATEST] = listOf(videos(1..30))
        source.sections[ChannelTab.SHORTS to ChannelSort.LATEST] = listOf(videos(101..110, short = { true }))
        val vm = model()

        vm.selectTab(ChannelTab.SHORTS)
        assertThat(source.requests).containsExactly("VIDEOS:LATEST:0", "SHORTS:LATEST:0").inOrder()
        assertThat(ids(vm.items.value.items)).hasSize(10)
        assertThat(vm.sections.value.selected).isEqualTo(ChannelTab.SHORTS)

        vm.selectTab(ChannelTab.VIDEOS)
        vm.selectTab(ChannelTab.SHORTS)
        vm.selectTab(ChannelTab.VIDEOS)
        // Back and forth: nothing is asked again.
        assertThat(source.requests).hasSize(2)
        assertThat(ids(vm.items.value.items)).hasSize(30)
    }

    @Test
    fun aSectionBeingReadIsNotNothing() {
        source.sections[ChannelTab.VIDEOS to ChannelSort.LATEST] = listOf(videos(1..30))
        source.hold = setOf("SHORTS:LATEST:0")
        val vm = model()

        vm.selectTab(ChannelTab.SHORTS)

        // Its page is on its way: the page says so, not "no Shorts".
        assertThat(vm.items.value.loadingFirst).isTrue()
        assertThat(vm.items.value.items).isEmpty()
    }

    // ---- orders -----------------------------------------------------------------------------------------------------------------

    @Test
    fun anOrderIsAListOfItsOwn_withItsOwnPages() {
        source.sections[ChannelTab.VIDEOS to ChannelSort.LATEST] = listOf(videos(1..30))
        source.sections[ChannelTab.VIDEOS to ChannelSort.POPULAR] = listOf(videos(201..230), videos(231..260), videos(261..270))
        val vm = model()

        vm.selectSort(ChannelSort.POPULAR)
        assertThat(source.requests).containsExactly("VIDEOS:LATEST:0", "VIDEOS:POPULAR:0").inOrder()
        assertThat(ids(vm.items.value.items).first()).isEqualTo(id(201))
        assertThat(vm.sections.value.sort).isEqualTo(ChannelSort.POPULAR)

        vm.loadMore()
        assertThat(vm.items.value.items).hasSize(60)
        assertThat(source.requests.last()).isEqualTo("VIDEOS:POPULAR:1")

        // Back to the newest: the first list as it was, nothing asked again.
        vm.selectSort(ChannelSort.LATEST)
        assertThat(ids(vm.items.value.items)).hasSize(30)
        assertThat(ids(vm.items.value.items).first()).isEqualTo(id(1))
        assertThat(source.requests).hasSize(3)

        // And the popular list went on where it was.
        vm.selectSort(ChannelSort.POPULAR)
        assertThat(vm.items.value.items).hasSize(60)
        vm.loadMore()
        assertThat(vm.items.value.items).hasSize(70)
        assertThat(vm.items.value.endReached).isTrue()
    }

    @Test
    fun anOrderTheSectionDoesNotOfferIsNeverAsked() {
        source.offered[ChannelTab.VIDEOS] = listOf(ChannelSort.LATEST, ChannelSort.POPULAR)
        source.sections[ChannelTab.VIDEOS to ChannelSort.LATEST] = listOf(videos(1..30))
        val vm = model()

        assertThat(vm.sections.value.sorts).containsExactly(ChannelSort.LATEST, ChannelSort.POPULAR).inOrder()
        vm.selectSort(ChannelSort.OLDEST)

        assertThat(vm.sections.value.sort).isEqualTo(ChannelSort.LATEST)
        assertThat(source.requests).containsExactly("VIDEOS:LATEST:0")
    }

    @Test
    fun aSectionWithNoChoiceOfOrderShowsNoChips() {
        source.sections[ChannelTab.VIDEOS to ChannelSort.LATEST] = listOf(videos(1..30))
        source.sections[ChannelTab.LIVE to ChannelSort.LATEST] = listOf(videos(301..302))
        source.offered[ChannelTab.LIVE] = listOf(ChannelSort.LATEST)
        source.sections[ChannelTab.PLAYLISTS to ChannelSort.LATEST] = listOf(listOf(playlist(1)))
        val vm = model()

        vm.selectTab(ChannelTab.LIVE)
        assertThat(vm.sections.value.sorts).isEmpty()
        assertThat(vm.sections.value.showsSorts).isFalse()

        // The playlists are not sorted whatever the source says.
        source.offered[ChannelTab.PLAYLISTS] = ChannelSort.entries
        vm.selectTab(ChannelTab.PLAYLISTS)
        assertThat(vm.sections.value.sorts).isEmpty()
        vm.selectSort(ChannelSort.POPULAR)
        assertThat(source.requests).doesNotContain("PLAYLISTS:POPULAR:0")
    }

    @Test
    fun theOrderIsRememberedForEachSection() {
        source.sections[ChannelTab.VIDEOS to ChannelSort.LATEST] = listOf(videos(1..30))
        source.sections[ChannelTab.VIDEOS to ChannelSort.OLDEST] = listOf(videos(201..230))
        source.sections[ChannelTab.SHORTS to ChannelSort.LATEST] = listOf(videos(101..110, short = { true }))
        val vm = model()

        vm.selectSort(ChannelSort.OLDEST)
        vm.selectTab(ChannelTab.SHORTS)
        assertThat(vm.sections.value.sort).isEqualTo(ChannelSort.LATEST)
        vm.selectTab(ChannelTab.VIDEOS)

        assertThat(vm.sections.value.sort).isEqualTo(ChannelSort.OLDEST)
        assertThat(ids(vm.items.value.items).first()).isEqualTo(id(201))
    }

    // ---- what the profile may see -------------------------------------------------------------------------------------------------

    @Test
    fun noShortsSection_whereTheProfileHidesShorts() {
        rules.value = ContentFilter.of(engine, owner.copy(hideShorts = true))
        source.sections[ChannelTab.VIDEOS to ChannelSort.LATEST] = listOf(videos(1..30))
        val vm = model()

        assertThat(vm.sections.value.tabs).containsExactly(ChannelTab.VIDEOS, ChannelTab.LIVE, ChannelTab.PLAYLISTS).inOrder()
        vm.selectTab(ChannelTab.SHORTS)
        assertThat(vm.sections.value.selected).isEqualTo(ChannelTab.VIDEOS)
        assertThat(source.requests).containsExactly("VIDEOS:LATEST:0")
    }

    @Test
    fun theShortsSectionGoesWhenTheProfileStopsShowingShorts() {
        source.sections[ChannelTab.VIDEOS to ChannelSort.LATEST] = listOf(videos(1..30))
        source.sections[ChannelTab.SHORTS to ChannelSort.LATEST] = listOf(videos(101..110, short = { true }))
        val vm = model()
        vm.selectTab(ChannelTab.SHORTS)
        assertThat(ids(vm.items.value.items)).hasSize(10)

        rules.value = ContentFilter.of(engine, owner.copy(hideShorts = true))

        assertThat(vm.sections.value.tabs).doesNotContain(ChannelTab.SHORTS)
        assertThat(vm.sections.value.selected).isEqualTo(ChannelTab.VIDEOS)
        assertThat(ids(vm.items.value.items)).hasSize(30)
    }

    @Test
    fun aChannelWithoutSomeSectionsShowsOnlyThoseItHas() {
        source.tabs = listOf(ChannelTab.VIDEOS, ChannelTab.PLAYLISTS)
        source.sections[ChannelTab.VIDEOS to ChannelSort.LATEST] = listOf(videos(1..30))
        val vm = model()

        assertThat(vm.sections.value.tabs).containsExactly(ChannelTab.VIDEOS, ChannelTab.PLAYLISTS).inOrder()
        // One section is no choice to show; a channel of only the videos has no row at all.
        source.tabs = listOf(ChannelTab.VIDEOS)
        assertThat(model().sections.value.showsTabs).isFalse()
    }

    @Test
    fun aChannelWithoutVideosOpensOnItsFirstSection() {
        source.tabs = listOf(ChannelTab.SHORTS, ChannelTab.PLAYLISTS)
        source.sections[ChannelTab.SHORTS to ChannelSort.LATEST] = listOf(videos(101..110, short = { true }))
        val vm = model()

        assertThat(vm.sections.value.selected).isEqualTo(ChannelTab.SHORTS)
        assertThat(ids(vm.items.value.items)).hasSize(10)
    }

    @Test
    fun everyItemOfEverySectionPassesTheProfilesRules() {
        rules.value = ContentFilter.of(engine, owner.copy(blocklist = Blocklist(titleKeywords = listOf("spoiler"))))
        val bad = videos(1..1).single().copy(title = "Big SPOILER inside")
        source.sections[ChannelTab.VIDEOS to ChannelSort.LATEST] = listOf(listOf(bad) + videos(2..10))
        source.sections[ChannelTab.LIVE to ChannelSort.LATEST] = listOf(listOf(bad.copy(videoId = "vid00000099", isLive = true)) + videos(21..22))
        source.sections[ChannelTab.PLAYLISTS to ChannelSort.LATEST] = listOf(listOf(playlist(1, "Spoiler series"), playlist(2, "Hits")))
        val vm = model()

        assertThat(ids(vm.items.value.items)).hasSize(9)
        assertThat(ids(vm.items.value.items)).doesNotContain(id(1))
        vm.selectTab(ChannelTab.LIVE)
        assertThat(ids(vm.items.value.items)).containsExactly(id(21), id(22)).inOrder()
        vm.selectTab(ChannelTab.PLAYLISTS)
        assertThat(vm.items.value.items.map { (it as PlaylistUi).playlist.title }).containsExactly("Hits")

        // And a rule that changes while the page is open takes effect at once.
        rules.value = ContentFilter.of(engine, owner)
        assertThat(vm.items.value.items).hasSize(2)
    }

    @Test
    fun aChildSeesTheChannelsPlaylists_andOnlyWhatHerRulesAllow() {
        rules.value = ContentFilter.of(engine, child)
        source.sections[ChannelTab.VIDEOS to ChannelSort.LATEST] = listOf(videos(1..30))
        // Her videos hide Shorts, and the channel's playlists are hers to open.
        source.sections[ChannelTab.PLAYLISTS to ChannelSort.LATEST] = listOf(listOf(playlist(1), playlist(2)))
        val vm = model()

        assertThat(vm.sections.value.tabs).doesNotContain(ChannelTab.SHORTS)
        assertThat(ids(vm.items.value.items)).hasSize(30)
        vm.selectTab(ChannelTab.PLAYLISTS)
        // Not allowlisted themselves, but the channel's: a child may open them.
        assertThat(vm.items.value.items).hasSize(2)
    }

    @Test
    fun aChildsVideosHideShorts_inEverySection() {
        rules.value = ContentFilter.of(engine, child)
        source.sections[ChannelTab.VIDEOS to ChannelSort.LATEST] = listOf(videos(1..30, short = { it % 2 == 0 }))
        source.sections[ChannelTab.LIVE to ChannelSort.LATEST] = listOf(videos(31..40, short = { it % 2 == 0 }))
        val vm = model()

        assertThat(ids(vm.items.value.items)).hasSize(15)
        vm.selectTab(ChannelTab.LIVE)
        assertThat(ids(vm.items.value.items)).hasSize(5)
    }

    @Test
    fun nothingIsReadBeforeTheProfileMayOpenTheChannel() {
        // Sara may open another channel only.
        rules.value = ContentFilter.of(engine, child.copy(allowlist = Allowlist(channels = listOf(ListedChannel("UCother000000000000000a1", "Other")))))
        val vm = model()

        assertThat(vm.access.value!!.isAllowed).isFalse()
        assertThat(source.requests).isEmpty()
        assertThat(source.headerRequests).isEqualTo(0)
        // And a pick changes nothing.
        vm.selectTab(ChannelTab.PLAYLISTS)
        assertThat(source.requests).isEmpty()
    }

    // ---- paging -----------------------------------------------------------------------------------------------------------------

    @Test
    fun aSectionFillsItsFirstScreenThroughTheRules_thenPagesWhileScrolled() {
        rules.value = ContentFilter.of(engine, child)
        // Pages of 30 videos, four in five of them Shorts: 6 shown per page, so 24 take four pages.
        source.sections[ChannelTab.VIDEOS to ChannelSort.LATEST] = (0 until 6).map { page -> videos(page * 30 + 1..page * 30 + 30, short = { it % 5 != 0 }) }
        val vm = model()

        assertThat(source.requests).containsExactly("VIDEOS:LATEST:0", "VIDEOS:LATEST:1", "VIDEOS:LATEST:2", "VIDEOS:LATEST:3").inOrder()
        assertThat(vm.items.value.items).hasSize(24)
        vm.loadMore()
        assertThat(vm.items.value.items).hasSize(36)
    }

    @Test
    fun aListThatEndsStopsAsking() {
        source.sections[ChannelTab.VIDEOS to ChannelSort.LATEST] = listOf(videos(1..30), videos(31..40))
        val vm = model()

        vm.loadMore()
        assertThat(vm.items.value.endReached).isTrue()
        repeat(3) { vm.loadMore() }
        assertThat(source.requests).containsExactly("VIDEOS:LATEST:0", "VIDEOS:LATEST:1").inOrder()
    }

    // ---- failures ---------------------------------------------------------------------------------------------------------------

    @Test
    fun aSectionThatFailsFailsAlone_andIsReadAgainOnRetry() {
        source.sections[ChannelTab.VIDEOS to ChannelSort.LATEST] = listOf(videos(1..30))
        source.sections[ChannelTab.LIVE to ChannelSort.LATEST] = listOf(videos(301..305))
        source.failing = setOf("LIVE:LATEST:0")
        val vm = model()

        vm.selectTab(ChannelTab.LIVE)
        assertThat(vm.items.value.error?.kind).isEqualTo(SourceErrorKind.NETWORK)
        assertThat(vm.items.value.items).isEmpty()
        // The other section is as it was.
        vm.selectTab(ChannelTab.VIDEOS)
        assertThat(vm.items.value.error).isNull()
        assertThat(ids(vm.items.value.items)).hasSize(30)

        source.failing = emptySet()
        vm.selectTab(ChannelTab.LIVE)
        vm.retry()
        assertThat(vm.items.value.error).isNull()
        assertThat(ids(vm.items.value.items)).hasSize(5)
    }

    @Test
    fun aLaterPageThatFailsKeepsTheListAndWaitsForRetry() {
        source.sections[ChannelTab.VIDEOS to ChannelSort.LATEST] = listOf(videos(1..30), videos(31..60), videos(61..70))
        source.failing = setOf("VIDEOS:LATEST:1")
        val vm = model()

        vm.loadMore()
        assertThat(vm.items.value.items).hasSize(30)
        assertThat(vm.items.value.moreError?.kind).isEqualTo(SourceErrorKind.NETWORK)
        repeat(3) { vm.loadMore() }
        assertThat(source.requests).containsExactly("VIDEOS:LATEST:0", "VIDEOS:LATEST:1").inOrder()

        source.failing = emptySet()
        vm.retryMore()
        assertThat(vm.items.value.items).hasSize(60)
    }

    @Test
    fun aHeaderThatFails_stillLeavesTheVideosAlone() {
        source.sections[ChannelTab.VIDEOS to ChannelSort.LATEST] = listOf(videos(1..30))
        source.headerFails = true
        val vm = model()

        // The videos came: their channel's name stands in for the header, so the page shows them instead of the error screen.
        assertThat(vm.header.value).isInstanceOf(ChannelHeader.Ready::class.java)
        // Without the channel's list of sections the page is the videos it has always been.
        assertThat(vm.sections.value.tabs).containsExactly(ChannelTab.VIDEOS)
        assertThat(ids(vm.items.value.items)).hasSize(30)
    }
}
