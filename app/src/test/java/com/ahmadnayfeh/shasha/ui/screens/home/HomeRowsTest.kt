package com.ahmadnayfeh.shasha.ui.screens.home

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.data.FakeVideoSource
import com.ahmadnayfeh.shasha.data.DAY_MS
import com.ahmadnayfeh.shasha.data.FeedRepository
import com.ahmadnayfeh.shasha.data.HOUR_MS
import com.ahmadnayfeh.shasha.data.HomeSuggestions
import com.ahmadnayfeh.shasha.data.MutableProfile
import com.ahmadnayfeh.shasha.data.NOW_MS
import com.ahmadnayfeh.shasha.data.SubscriptionRepository
import com.ahmadnayfeh.shasha.data.WatchRepository
import com.ahmadnayfeh.shasha.data.db.FeedItemEntity
import com.ahmadnayfeh.shasha.data.db.ShashaDatabase
import com.ahmadnayfeh.shasha.data.inMemoryDatabase
import com.ahmadnayfeh.shasha.data.subscription
import com.ahmadnayfeh.shasha.policy.ALLOWED_CHANNEL
import com.ahmadnayfeh.shasha.policy.BLOCKED_CHANNEL
import com.ahmadnayfeh.shasha.policy.Blocklist
import com.ahmadnayfeh.shasha.policy.ContentFilter
import com.ahmadnayfeh.shasha.policy.ListedChannel
import com.ahmadnayfeh.shasha.policy.MutableClock
import com.ahmadnayfeh.shasha.policy.OTHER_CHANNEL
import com.ahmadnayfeh.shasha.policy.PolicyEngine
import com.ahmadnayfeh.shasha.policy.Profile
import com.ahmadnayfeh.shasha.policy.clip
import com.ahmadnayfeh.shasha.policy.instantAt
import com.ahmadnayfeh.shasha.policy.kid
import com.ahmadnayfeh.shasha.policy.owner
import com.ahmadnayfeh.shasha.ui.screens.kids.KidsFeed
import com.ahmadnayfeh.shasha.ui.screens.kids.KidsHomeViewModel
import com.ahmadnayfeh.shasha.youtube.VideoItem
import com.ahmadnayfeh.shasha.youtube.VideoSource
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
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
 * The home's rows: «Shorts» from the subscriptions (and never twice: not in the grid then), «مقترحة لك» and the suggested Shorts
 * from the ranking of what is suggested beside the last videos watched (a newer viewing weighs more), through the profile's
 * rules (adult and child, Shorts shown or hidden, suggestions on or off), and «شاهدتَها مؤخراً» for a home whose channels give it
 * no videos: a history is never an empty home.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class HomeRowsTest {
    private lateinit var db: ShashaDatabase
    private lateinit var watch: WatchRepository
    private val engine = PolicyEngine(MutableClock(instantAt("2026-09-28T12:00")))

    /** What YouTube suggests beside each watched video, and each channel's newest Shorts; every request is recorded. */
    private val related = HashMap<String, List<VideoItem>>()
    private val channelShorts = HashMap<String, List<VideoItem>>()
    private val asked = CopyOnWriteArrayList<String>()
    private val askedShorts = CopyOnWriteArrayList<String>()

    /** While it is set, the suggestions do not arrive (YouTube is slow). */
    @Volatile private var gate: CompletableDeferred<Unit>? = null
    private val fake = FakeVideoSource()
    private val source: VideoSource = object : VideoSource by fake {
        override suspend fun related(videoId: String): List<VideoItem> {
            asked += videoId
            gate?.await()
            return related[videoId].orEmpty()
        }

        override suspend fun channelShorts(channelId: String): List<VideoItem> {
            askedShorts += channelId
            return channelShorts[channelId].orEmpty()
        }
    }

    /** The time a video is watched at (history rows carry it: a newer viewing weighs more). */
    private var watchedAt = NOW_MS

    @Before
    fun setUp() = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        db = inMemoryDatabase()
        watch = WatchRepository(db, MutableProfile(), clock = { watchedAt })
        db.subscriptions().insertAll(listOf(subscription(ALLOWED_CHANNEL), subscription(OTHER_CHANNEL)))
        db.feed().upsert(
            listOf(
                row("regular0001", ALLOWED_CHANNEL, hoursAgo = 1, seconds = 600),
                row("short000001", ALLOWED_CHANNEL, hoursAgo = 2, short = true),
                row("regular0002", OTHER_CHANNEL, hoursAgo = 3, seconds = null),
                row("brief000001", OTHER_CHANNEL, hoursAgo = 4, seconds = 50),
                row("short000002", OTHER_CHANNEL, hoursAgo = 5, short = true),
            ),
        )
        Unit
    }

    @After
    fun tearDown() {
        viewModels.clear()
        db.close()
        Dispatchers.resetMain()
    }

    private val viewModels = ViewModelStore()
    private var created = 0

    /** Kept in [viewModels], so their work stops before the database closes. */
    private inline fun <reified T : ViewModel> keep(noinline make: () -> T): T =
        ViewModelProvider(viewModels, viewModelFactory { initializer { make() } })["vm${created++}", T::class.java]

    private fun row(id: String, channel: String, hoursAgo: Int, seconds: Long? = null, short: Boolean = false) = FeedItemEntity(
        videoId = id,
        channelId = channel,
        channelName = "Name of $channel",
        title = "Video $id",
        publishedAtMs = NOW_MS - hoursAgo * 3_600_000L,
        thumbnailUrl = null,
        durationSeconds = seconds,
        isShort = short,
        isLive = false,
        url = if (short) "https://www.youtube.com/shorts/$id" else "https://www.youtube.com/watch?v=$id",
        fetchedAtMs = NOW_MS,
    )

    private fun suggestions() =
        HomeSuggestions(source, file = null, clock = { NOW_MS }, playing = MutableStateFlow(false), lowPriority = { it() }, io = Dispatchers.Unconfined, pauseMs = 0)

    private fun home(profile: Profile, rules: MutableStateFlow<ContentFilter> = MutableStateFlow(ContentFilter.of(engine, profile))) =
        keep { HomeViewModel(FeedRepository(db, fake), SubscriptionRepository(db, fake), watch, flowOf(true), {}, rules, suggestions()) }

    private fun kidsHome(child: Profile) = keep {
        KidsHomeViewModel(
            MutableStateFlow(ContentFilter.of(engine, child)),
            FeedRepository(db, fake),
            KidsFeed(fake, isPlaying = { false }, lowPriority = { block -> block() }),
            watch,
            suggestions(),
        )
    }

    private fun HomeViewModel.settled(check: (HomeState) -> Boolean = { true }): HomeState =
        runBlocking { withTimeout(5_000) { state.first { it.loaded && check(it) } } }

    private fun List<com.ahmadnayfeh.shasha.ui.model.VideoUi>.ids() = map { it.video.videoId }

    @Test
    fun shortsShown_theyHaveTheirOwnRow_andLeaveTheGrid() {
        val state = home(owner()).settled()
        assertThat(state.shorts.ids()).containsExactly("short000001", "brief000001", "short000002").inOrder()
        assertThat(state.videos.ids()).containsExactly("regular0001", "regular0002").inOrder()
        assertThat(state.hasFeed).isTrue()
    }

    @Test
    fun shortsHidden_noRow_andNoShortInTheGrid() {
        val state = home(owner().copy(hideShorts = true)).settled()
        assertThat(state.shorts).isEmpty()
        assertThat(state.videos.ids()).containsExactly("regular0001", "regular0002").inOrder()
    }

    @Test
    fun suggestedForYou_besideTheLastVideosWatched_throughTheProfilesRules() = runBlocking<Unit> {
        related["regular0001"] = listOf(
            clip("sugg000001", channelId = OTHER_CHANNEL),
            clip("blocked0001", channelId = BLOCKED_CHANNEL),
            clip("regular0002", channelId = OTHER_CHANNEL), // watched already
            clip("sugshort001", channelId = OTHER_CHANNEL, short = true),
        )
        watch.addToHistory(clip("regular0002", channelId = OTHER_CHANNEL))
        watch.addToHistory(clip("regular0001", channelId = ALLOWED_CHANNEL))
        val household = Blocklist().withChannel(ListedChannel(BLOCKED_CHANNEL, "محظورة"))
        val rules = MutableStateFlow(ContentFilter.of(engine, owner().copy(blocklist = household)))
        val vm = home(owner(), rules)
        val state = vm.settled { it.suggested.isNotEmpty() && it.suggestedShorts.isNotEmpty() }
        // The video row has the videos, the Shorts row the Short: both from the one ranking.
        assertThat(state.suggested.ids()).containsExactly("sugg000001")
        assertThat(state.suggestedShorts.ids()).containsExactly("sugshort001")
        // YouTube is asked beside each of the last videos watched, one after the other in the background.
        withTimeout(5_000) { while (!asked.containsAll(listOf("regular0001", "regular0002"))) delay(10) }

        // Without Shorts the suggested Short goes too; with suggestions off, the whole row.
        rules.value = ContentFilter.of(engine, owner().copy(blocklist = household, hideShorts = true))
        val noShorts = vm.settled { it.suggestedShorts.isEmpty() && it.suggested.ids() == listOf("sugg000001") }
        assertThat(noShorts.suggested.ids()).containsExactly("sugg000001")
        rules.value = ContentFilter.of(engine, owner().copy(showSuggestions = false))
        val off = vm.settled { it.suggested.isEmpty() }
        assertThat(off.suggested).isEmpty()
        assertThat(off.suggestedShorts).isEmpty()
    }

    @Test
    fun aNewerViewingWeighsMore_inTheRowsOrder() = runBlocking<Unit> {
        related["old000000001"] = listOf(clip("o1", channelId = OTHER_CHANNEL))
        related["new000000001"] = listOf(clip("n1", channelId = ALLOWED_CHANNEL), clip("n2", channelId = OTHER_CHANNEL))
        watchedAt = NOW_MS - 20 * DAY_MS
        watch.addToHistory(clip("old000000001", channelId = OTHER_CHANNEL))
        watchedAt = NOW_MS
        watch.addToHistory(clip("new000000001", channelId = ALLOWED_CHANNEL))
        val state = home(owner()).settled { it.suggested.size == 3 }
        // Taking the lists in turns would put the old video's only suggestion second; its viewing is three weeks old: it is last.
        assertThat(state.suggested.ids()).containsExactly("n1", "n2", "o1").inOrder()
    }

    @Test
    fun theSuggestedShorts_comeFromTheChannelsWatched_andNeverRepeatTheFeedsShorts() = runBlocking<Unit> {
        channelShorts[ALLOWED_CHANNEL] = listOf(
            clip("short000001", channelId = ALLOWED_CHANNEL, short = true), // the feed's own Short: its row has it already
            clip("chshort0001", channelId = ALLOWED_CHANNEL, short = true),
            clip("chshort0002", channelId = ALLOWED_CHANNEL, short = true),
        )
        watch.addToHistory(clip("regular0001", channelId = ALLOWED_CHANNEL))
        val state = home(owner()).settled { it.suggestedShorts.isNotEmpty() }
        assertThat(state.suggestedShorts.ids()).containsExactly("chshort0001", "chshort0002").inOrder()
        assertThat(state.shorts.ids()).containsExactly("short000001", "brief000001", "short000002").inOrder()
        assertThat(askedShorts).containsExactly(ALLOWED_CHANNEL)
    }

    @Test
    fun aProfileThatHidesShorts_hasNoShortsRow_andNoChannelIsAskedForThem() = runBlocking<Unit> {
        channelShorts[ALLOWED_CHANNEL] = listOf(clip("chshort0001", channelId = ALLOWED_CHANNEL, short = true))
        related["regular0001"] = listOf(clip("sugg000001", channelId = OTHER_CHANNEL))
        watch.addToHistory(clip("regular0001", channelId = ALLOWED_CHANNEL))
        val state = home(owner().copy(hideShorts = true)).settled { it.suggested.isNotEmpty() }
        assertThat(state.suggestedShorts).isEmpty()
        assertThat(askedShorts).isEmpty()
    }

    @Test
    fun aHistoryWithoutChannels_isNeverAnEmptyHome() = runBlocking<Unit> {
        db.subscriptions().delete(ALLOWED_CHANNEL)
        db.subscriptions().delete(OTHER_CHANNEL)
        watchedAt = NOW_MS - 2 * HOUR_MS
        watch.addToHistory(clip("older0000001", channelId = OTHER_CHANNEL))
        watchedAt = NOW_MS - HOUR_MS
        watch.addToHistory(clip("newer0000001", channelId = ALLOWED_CHANNEL))
        // YouTube has not answered (it may not for a while): what was watched is the home.
        gate = CompletableDeferred()
        val vm = home(owner())
        val waiting = vm.settled { it.recent.isNotEmpty() && it.suggesting }
        assertThat(waiting.hasRows).isTrue()
        assertThat(waiting.videos).isEmpty()
        assertThat(waiting.subscriptionCount).isEqualTo(0)
        assertThat(waiting.recent.ids()).containsExactly("newer0000001", "older0000001").inOrder()
        // It says the suggestions are on their way, and shows no empty row for them.
        assertThat(waiting.suggested).isEmpty()

        // Then they arrive: above what was watched, which stays.
        related["newer0000001"] = listOf(clip("sugg000001", channelId = OTHER_CHANNEL))
        gate!!.complete(Unit)
        val arrived = vm.settled { it.suggested.isNotEmpty() }
        assertThat(arrived.suggested.ids()).containsExactly("sugg000001")
        assertThat(arrived.suggesting).isFalse()
        assertThat(arrived.recent.ids()).containsExactly("newer0000001", "older0000001").inOrder()
    }

    @Test
    fun withChannelsVideos_theHomeHasNoRecentRow() = runBlocking<Unit> {
        watch.addToHistory(clip("regular0001", channelId = ALLOWED_CHANNEL))
        val state = home(owner()).settled()
        assertThat(state.videos).isNotEmpty()
        assertThat(state.recent).isEmpty()
    }

    @Test
    fun theRecentRow_followsTheProfilesRules() = runBlocking<Unit> {
        db.subscriptions().delete(ALLOWED_CHANNEL)
        db.subscriptions().delete(OTHER_CHANNEL)
        watchedAt = NOW_MS - 3 * HOUR_MS
        watch.addToHistory(clip("fine00000001", channelId = ALLOWED_CHANNEL))
        watchedAt = NOW_MS - 2 * HOUR_MS
        watch.addToHistory(clip("blocked00001", channelId = BLOCKED_CHANNEL))
        watchedAt = NOW_MS - HOUR_MS
        watch.addToHistory(clip("brief0000001", channelId = ALLOWED_CHANNEL, durationSeconds = 45))
        val household = Blocklist().withChannel(ListedChannel(BLOCKED_CHANNEL, "محظورة"))
        val shown = home(owner().copy(blocklist = household)).settled { it.recent.isNotEmpty() }
        assertThat(shown.recent.ids()).containsExactly("brief0000001", "fine00000001").inOrder()
        // Where Shorts are hidden, a video as short as that one goes too.
        val hidden = home(owner().copy(blocklist = household, hideShorts = true)).settled { it.recent.isNotEmpty() }
        assertThat(hidden.recent.ids()).containsExactly("fine00000001")
    }

    @Test
    fun noHistoryAndNoChannels_thereIsNothingToShow() = runBlocking<Unit> {
        db.subscriptions().delete(ALLOWED_CHANNEL)
        db.subscriptions().delete(OTHER_CHANNEL)
        val state = home(owner()).settled()
        assertThat(state.hasRows).isFalse()
        assertThat(state.subscriptionCount).isEqualTo(0)
        assertThat(state.suggesting).isFalse()
    }

    @Test
    fun suggestionsOff_youTubeIsNotEvenAsked() = runBlocking<Unit> {
        related["regular0001"] = listOf(clip("sugg000001", channelId = OTHER_CHANNEL))
        watch.addToHistory(clip("regular0001", channelId = ALLOWED_CHANNEL))
        val state = home(owner().copy(showSuggestions = false)).settled()
        assertThat(state.suggested).isEmpty()
        assertThat(asked).isEmpty()
    }

    @Test
    fun aChildWithSuggestions_onlyItsAllowedChannels() = runBlocking<Unit> {
        related["regular0001"] = listOf(
            clip("outside0001", channelId = OTHER_CHANNEL),
            clip("inside00001", channelId = ALLOWED_CHANNEL),
            clip("insidebrief", channelId = ALLOWED_CHANNEL, durationSeconds = 40),
        )
        watch.addToHistory(clip("regular0001", channelId = ALLOWED_CHANNEL))
        val vm = kidsHome(kid().copy(showSuggestions = true))
        val state = withTimeout(5_000) { vm.state.first { it.loaded && it.suggested.isNotEmpty() } }
        // Not the other channel's, and not the 40-second video: a child hides Shorts by default.
        assertThat(state.suggested.ids()).containsExactly("inside00001")

        // The default child: none at all, and nothing asked for it.
        asked.clear()
        val plain = withTimeout(5_000) { kidsHome(kid()).state.first { it.loaded } }
        assertThat(plain.suggested).isEmpty()
        assertThat(asked).isEmpty()
    }
}
