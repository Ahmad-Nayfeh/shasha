package com.ahmadnayfeh.shasha.ui.screens.kids

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.data.DAY_MS
import com.ahmadnayfeh.shasha.data.FakeVideoSource
import com.ahmadnayfeh.shasha.data.FeedRepository
import com.ahmadnayfeh.shasha.data.HOUR_MS
import com.ahmadnayfeh.shasha.data.MutableProfile
import com.ahmadnayfeh.shasha.data.NOW_MS
import com.ahmadnayfeh.shasha.data.WatchRepository
import com.ahmadnayfeh.shasha.data.channelId
import com.ahmadnayfeh.shasha.data.db.ShashaDatabase
import com.ahmadnayfeh.shasha.data.inMemoryDatabase
import com.ahmadnayfeh.shasha.data.subscription
import com.ahmadnayfeh.shasha.data.video
import com.ahmadnayfeh.shasha.policy.Allowlist
import com.ahmadnayfeh.shasha.policy.Blocklist
import com.ahmadnayfeh.shasha.policy.ContentFilter
import com.ahmadnayfeh.shasha.policy.Decision
import com.ahmadnayfeh.shasha.policy.ListedChannel
import com.ahmadnayfeh.shasha.policy.ListedPlaylist
import com.ahmadnayfeh.shasha.policy.MutableClock
import com.ahmadnayfeh.shasha.policy.PolicyEngine
import com.ahmadnayfeh.shasha.policy.Profile
import com.ahmadnayfeh.shasha.policy.instantAt
import com.ahmadnayfeh.shasha.policy.toCandidate
import com.ahmadnayfeh.shasha.youtube.SourceErrorKind
import com.ahmadnayfeh.shasha.youtube.SourceException
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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

/**
 * A child's الرئيسية (SPEC §3): the newest videos of the allowlisted channels only (from the adult's feed
 * when the adult follows the channel too, fetched otherwise), through the profile's rules, and the
 * allowlisted playlists. An allowlist edit (from the phone too) shows at once. A child who hides shorts
 * (the default) only sees videos whose duration is known: a regular upload of 3 minutes or less is a short
 * there, and RSS says no durations.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class KidsHomeViewModelTest {
    private lateinit var db: ShashaDatabase
    private val source = FakeVideoSource()
    private val engine = PolicyEngine(MutableClock(instantAt("2026-09-28T15:00")))
    private val viewModels = ViewModelStore()

    /** Allowlisted, and the adult follows it: its videos are already in the local feed. */
    private val followed = channelId(1)

    /** The adult's own channel: never on the child's home. */
    private val adultsOnly = channelId(2)

    /** Allowlisted, not followed: fetched for the child. */
    private val childsOnly = channelId(3)

    private val playlist = "PLkids000000000000000001"
    private val child = Profile.newChild("سارة", id = "kid").copy(
        allowlist = Allowlist(
            channels = listOf(ListedChannel(followed, "عالم الطبيعة"), ListedChannel(childsOnly, "حكايات")),
            playlists = listOf(ListedPlaylist(playlist, "تعلم الحروف")),
        ),
        blocklist = Blocklist(titleKeywords = listOf("رعب")),
    )
    private val filter = MutableStateFlow(ContentFilter.of(engine, child))

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        db = inMemoryDatabase()
    }

    @After
    fun tearDown() {
        viewModels.clear()
        db.close()
        Dispatchers.resetMain()
    }

    private fun home(): KidsHomeViewModel {
        val feed = FeedRepository(db, source, clock = { NOW_MS })
        val kidsFeed = KidsFeed(source, clock = { NOW_MS }, isPlaying = { false }, lowPriority = { block -> block() })
        val watch = WatchRepository(db, MutableProfile())
        return ViewModelProvider(viewModels, viewModelFactory { initializer { KidsHomeViewModel(filter, feed, kidsFeed, watch) } })[KidsHomeViewModel::class.java]
    }

    /**
     * The adult's feed, synced from RSS (no durations): the followed channel (a video and a short) and the
     * adult's own channel. The followed channel's Videos tab knows the video's 10 minutes.
     */
    private fun adultFeed() = runBlocking<Unit> {
        db.subscriptions().insertAll(listOf(subscription(followed), subscription(adultsOnly)))
        source.uploads[followed] = listOf(
            video("followed01", followed, NOW_MS - 2 * HOUR_MS),
            video("followedSh", followed, NOW_MS - HOUR_MS, short = true),
        )
        source.videosTab[followed] = listOf(video("followed01", followed, NOW_MS - 2 * HOUR_MS, durationSeconds = 600))
        source.uploads[adultsOnly] = listOf(video("adultsonly", adultsOnly, NOW_MS - 30 * 60_000L))
        FeedRepository(db, source, clock = { NOW_MS }).syncAll()
        source.fetched.clear()
    }

    private fun KidsHomeViewModel.ids(until: (KidsHomeState) -> Boolean): List<String> =
        runBlocking { withTimeout(5_000) { state.first { it.loaded && !it.fetching && until(it) } }.videos.map { it.video.videoId } }

    @Test
    fun onlyTheAllowedChannels_newestFirst_withoutBlockedTitlesOrShorts() {
        adultFeed()
        source.videosTab[childsOnly] = listOf(
            video("childs0001", childsOnly, NOW_MS - 3 * HOUR_MS, durationSeconds = 540),
            video("scarystory", childsOnly, NOW_MS - 10 * 60_000L, title = "قصة رعب قبل النوم", durationSeconds = 600),
            video("childs0002", childsOnly, NOW_MS - DAY_MS, durationSeconds = 720),
        )
        val home = home()

        assertThat(home.ids { it.videos.size >= 3 }).containsExactly("followed01", "childs0001", "childs0002").inOrder()
        // The durations come from each allowed channel's Videos tab (the followed one's too: RSS has none).
        assertThat(source.tabFetched).containsExactly(followed, childsOnly)
        assertThat(source.fetched).isEmpty()
        val state = home.state.value
        assertThat(state.name).isEqualTo("سارة")
        assertThat(state.channelCount).isEqualTo(2)
        // The playlist keeps the name the parent saw when its details cannot be fetched.
        assertThat(state.playlists.map { it.playlist.playlistId to it.playlist.title }).containsExactly(playlist to "تعلم الحروف")
    }

    @Test
    fun aRegularVideoOfThreeMinutesOrLess_neverShows_evenWhenRssGivesNoDuration() {
        // The nursery-song channel: its uploads are regular 2–3 minute videos, which RSS lists without a duration.
        adultFeed()
        runBlocking {
            source.uploads[followed] = listOf(
                video("song000001", followed, NOW_MS - 20 * 60_000L), // 150 s says the Videos tab
                video("rssonly001", followed, NOW_MS - 40 * 60_000L), // not on the Videos tab (a live, a premiere…)
                video("followed01", followed, NOW_MS - 2 * HOUR_MS),
            )
            FeedRepository(db, source, clock = { NOW_MS }).syncAll(freshForMs = 0)
            source.fetched.clear()
        }
        source.videosTab[followed] = listOf(
            video("song000001", followed, NOW_MS - 20 * 60_000L, durationSeconds = 150),
            video("followed01", followed, NOW_MS - 2 * HOUR_MS, durationSeconds = 600),
        )
        source.videosTab[childsOnly] = listOf(
            video("song000002", childsOnly, NOW_MS - HOUR_MS, durationSeconds = 150),
            video("story00001", childsOnly, NOW_MS - 3 * HOUR_MS, durationSeconds = 600),
        )
        val home = home()
        assertThat(home.ids { it.videos.size >= 2 }).containsExactly("followed01", "story00001").inOrder()
        // Everything shown would play: the shorts rule has nothing left to refuse.
        home.state.value.videos.forEach { assertThat(engine.canShow(child, it.video.toCandidate())).isEqualTo(Decision.Allow) }
    }

    @Test
    fun aChildWhoSeesShorts_getsTheFeedAndRss_withoutAskingTwice() {
        adultFeed()
        filter.value = ContentFilter.of(engine, child.copy(hideShorts = false))
        source.uploads[childsOnly] = listOf(video("childs0001", childsOnly, NOW_MS - 3 * HOUR_MS))
        val home = home()
        assertThat(home.ids { it.videos.size >= 3 }).containsExactly("followedSh", "followed01", "childs0001").inOrder()
        // Only the channel the adult does not follow was asked for, and from RSS.
        assertThat(source.fetched).containsExactly(childsOnly)
        assertThat(source.tabFetched).isEmpty()
    }

    @Test
    fun anAllowlistEdit_showsAtOnce() {
        adultFeed()
        source.videosTab[childsOnly] = listOf(video("childs0001", childsOnly, NOW_MS - 3 * HOUR_MS, durationSeconds = 600))
        val home = home()
        assertThat(home.ids { it.videos.size >= 2 }).containsExactly("followed01", "childs0001").inOrder()

        // The parent removes a channel: its videos leave the home.
        filter.value = ContentFilter.of(engine, child.copy(allowlist = child.allowlist.without(childsOnly)))
        assertThat(home.ids { s -> s.videos.none { it.video.channelId == childsOnly } }).containsExactly("followed01")

        // And adds one: it is fetched right away.
        val added = channelId(4)
        source.videosTab[added] = listOf(video("added00001", added, NOW_MS - 5 * 60_000L, durationSeconds = 400))
        filter.value = ContentFilter.of(engine, child.copy(allowlist = child.allowlist.without(childsOnly).withChannel(ListedChannel(added, "جديدة"))))
        assertThat(home.ids { s -> s.videos.any { it.video.channelId == added } }).containsExactly("added00001", "followed01").inOrder()
    }

    @Test
    fun whenEveryChannelFails_andNothingIsKnown_theErrorIsShown() {
        val onlyFetched = child.copy(allowlist = Allowlist(channels = listOf(ListedChannel(childsOnly, "حكايات"))))
        filter.value = ContentFilter.of(engine, onlyFetched)
        source.failures[childsOnly] = SourceException(SourceErrorKind.NETWORK, "UnknownHostException", null, null)
        val home = home()
        val state = runBlocking { withTimeout(5_000) { home.state.first { it.failure != null } } }
        assertThat(state.videos).isEmpty()
        assertThat(state.failure?.kind).isEqualTo(SourceErrorKind.NETWORK)
    }
}
