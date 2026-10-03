package com.ahmadnayfeh.shasha.ui.screens.kids

import android.app.Application
import android.util.Log
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.data.FeedRepository
import com.ahmadnayfeh.shasha.data.HOUR_MS
import com.ahmadnayfeh.shasha.data.MutableProfile
import com.ahmadnayfeh.shasha.data.NOW_MS
import com.ahmadnayfeh.shasha.data.ScriptedChannelSource
import com.ahmadnayfeh.shasha.data.WatchRepository
import com.ahmadnayfeh.shasha.data.channelId
import com.ahmadnayfeh.shasha.data.db.ShashaDatabase
import com.ahmadnayfeh.shasha.data.inMemoryDatabase
import com.ahmadnayfeh.shasha.data.video
import com.ahmadnayfeh.shasha.policy.Allowlist
import com.ahmadnayfeh.shasha.policy.ContentFilter
import com.ahmadnayfeh.shasha.policy.Decision
import com.ahmadnayfeh.shasha.policy.ListedChannel
import com.ahmadnayfeh.shasha.policy.MutableClock
import com.ahmadnayfeh.shasha.policy.PolicyEngine
import com.ahmadnayfeh.shasha.policy.Profile
import com.ahmadnayfeh.shasha.policy.instantAt
import com.ahmadnayfeh.shasha.policy.toCandidate
import com.ahmadnayfeh.shasha.youtube.SourceErrorKind
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
import org.robolectric.shadows.ShadowLog

/**
 * «The child's home must not fail»: a TV on which the channel tab answers every request with an error (the extractor's plain
 * `ContentNotAvailableException`) still gets the child's videos, from the same list as a playlist, with their durations; one
 * channel that fails everywhere leaves the others' videos on the home; only when every channel fails and nothing is known does
 * the home show the unified error. Every failure is in the log (channel id and message). The ways of reading a channel are
 * scripted one by one (ScriptedChannelSource) under the real view model and the real rules.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class KidsHomeFallbackTest {
    private lateinit var db: ShashaDatabase
    private val source = ScriptedChannelSource()
    private val engine = PolicyEngine(MutableClock(instantAt("2026-09-28T15:00")))
    private val viewModels = ViewModelStore()
    private var now = NOW_MS

    private val a = channelId(1)
    private val b = channelId(2)
    private fun longForm(channel: String) = "UULF" + channel.drop(2)

    private val child = Profile.newChild("سارة", id = "kid").copy(
        allowlist = Allowlist(channels = listOf(ListedChannel(a, "قناة أ"), ListedChannel(b, "قناة ب"))),
    )
    private val filter = MutableStateFlow(ContentFilter.of(engine, child))

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

    private fun home(): KidsHomeViewModel {
        val feed = FeedRepository(db, source, clock = { NOW_MS })
        val kidsFeed = KidsFeed(source, clock = { now }, isPlaying = { false }, lowPriority = { block -> block() })
        val watch = WatchRepository(db, MutableProfile())
        return ViewModelProvider(viewModels, viewModelFactory { initializer { KidsHomeViewModel(filter, feed, kidsFeed, watch) } })[KidsHomeViewModel::class.java]
    }

    private fun KidsHomeViewModel.settled(until: (KidsHomeState) -> Boolean = { true }): KidsHomeState =
        runBlocking { withTimeout(5_000) { state.first { it.loaded && !it.fetching && until(it) } } }

    private fun KidsHomeState.ids() = videos.map { it.video.videoId }

    private fun logs(tag: String) = ShadowLog.getLogsForTag(tag).filter { it.type == Log.WARN }.map { it.msg }

    @Test
    fun aVideosTabThatFailsForEveryChannel_isRescuedByThePlaylist_withDurationsKept() {
        source.tabFails(a)
        source.tabFails(b)
        source.playlist(longForm(a), listOf(video("a0000000001", a, NOW_MS - HOUR_MS, durationSeconds = 600), video("a0000000002", a, NOW_MS - 2 * HOUR_MS, durationSeconds = 150)))
        source.playlist(longForm(b), listOf(video("b0000000001", b, NOW_MS - 3 * HOUR_MS, durationSeconds = 540)))
        val state = home().settled { it.videos.size >= 2 }

        // Newest first; the regular video of 150 seconds is a short for this profile (the limit is 3 minutes): never a card.
        assertThat(state.ids()).containsExactly("a0000000001", "b0000000001").inOrder()
        state.videos.forEach { assertThat(engine.canShow(child, it.video.toCandidate())).isEqualTo(Decision.Allow) }
        assertThat(state.failure).isNull()
        assertThat(state.failedChannels).isEqualTo(0)
        assertThat(source.calls).containsAtLeast("tab:$a", "playlist:${longForm(a)}", "tab:$b", "playlist:${longForm(b)}")
        // The tab's failures are in the log, with the channel ids, for adb logcat.
        assertThat(logs("ChannelUploads").filter { "the Videos tab failed" in it }.joinToString()).contains(a)
        assertThat(logs("ChannelUploads").filter { "the Videos tab failed" in it }.joinToString()).contains(b)
    }

    @Test
    fun oneChannelFailingEverywhere_doesNotBlankTheHome() {
        source.tabFails(a)
        source.playlistFails(longForm(a))
        source.tab(b, listOf(video("b0000000001", b, NOW_MS - HOUR_MS, durationSeconds = 600)))
        val state = home().settled { it.failedChannels == 1 && it.videos.isNotEmpty() }

        assertThat(state.ids()).containsExactly("b0000000001")
        assertThat(state.failure).isNull()
        assertThat(state.failedChannels).isEqualTo(1)
        // The channel that failed everywhere is named in the log: the home's own line, with the extractor's message.
        val line = logs("KidsHome").single()
        assertThat(line).contains(a)
        assertThat(line).contains("ContentNotAvailableException")
        assertThat(line).contains("This channel does not exist")
        assertThat(logs("KidsHome").joinToString()).doesNotContain(b)
    }

    @Test
    fun whenEveryChannelFails_andNothingIsKnown_theUnifiedErrorIsKept_andEachFailureIsLogged() {
        // Nothing scripted: every way of every channel answers ContentNotAvailableException, as on the owner's TV.
        val home = home()
        val state = runBlocking { withTimeout(5_000) { home.state.first { it.failure != null } } }

        assertThat(state.videos).isEmpty()
        assertThat(state.failure?.kind).isEqualTo(SourceErrorKind.CONTENT_UNAVAILABLE)
        assertThat(state.failure?.technicalName).isEqualTo("ContentNotAvailableException")
        assertThat(state.failedChannels).isEqualTo(2)
        // The home's own line per channel (id and message), and under it each way that was tried.
        assertThat(logs("KidsHome")).hasSize(2)
        assertThat(logs("KidsHome").joinToString()).contains(a)
        assertThat(logs("KidsHome").joinToString()).contains(b)
        assertThat(logs("ChannelUploads")).hasSize(4)
    }

    @Test
    fun aProfileThatHidesShorts_neverGetsRssRows_theErrorStandsRatherThanCardsThePlayerWouldRefuse() {
        // RSS answers (it works on the owner's TV) but has no durations: the shorts rule cannot judge those videos, the parent's
        // limit makes a regular upload of three minutes or less a short, and the player would refuse it after the card was shown.
        // So RSS is not asked for at all, and nothing is shown that cannot be judged.
        source.tabFails(a)
        source.tabFails(b)
        source.feed(a, listOf(video("rss00000001", a, NOW_MS - HOUR_MS), video("rss00000002", a, NOW_MS - 2 * HOUR_MS)))
        source.feed(b, listOf(video("rss00000003", b, NOW_MS - HOUR_MS)))
        val home = home()
        val state = runBlocking { withTimeout(5_000) { home.state.first { it.failure != null } } }
        assertThat(state.videos).isEmpty()
        assertThat(source.calls.filter { it.startsWith("rss:") }).isEmpty()
    }

    @Test
    fun aProfileThatShowsShorts_usesRssFirst_andFallsBackToThePlaylist() {
        filter.value = ContentFilter.of(engine, child.copy(hideShorts = false))
        source.feed(a, listOf(video("a0000000001", a, NOW_MS - HOUR_MS), video("a000000short", a, NOW_MS - 2 * HOUR_MS, short = true)))
        source.feedFails(b)
        source.playlist(longForm(b), listOf(video("b0000000001", b, NOW_MS - 3 * HOUR_MS, durationSeconds = 540)))
        val state = home().settled { it.videos.size >= 3 }

        assertThat(state.ids()).containsExactly("a0000000001", "a000000short", "b0000000001").inOrder()
        assertThat(state.failure).isNull()
        assertThat(source.calls.filter { it.startsWith("tab:") }).isEmpty()
    }

    @Test
    fun aRetryAfterEveryChannelFailed_asksAgain_andRecovers() {
        val home = home()
        runBlocking { withTimeout(5_000) { home.state.first { it.failure != null } } }

        // The channel tab works again (or the network came back): «إعادة المحاولة».
        source.tab(a, listOf(video("a0000000001", a, NOW_MS - HOUR_MS, durationSeconds = 600)))
        home.retry()
        val state = home.settled { it.videos.isNotEmpty() && it.failedChannels == 1 }
        assertThat(state.ids()).containsExactly("a0000000001")
        assertThat(state.failure).isNull()
        assertThat(state.failedChannels).isEqualTo(1) // b still fails: the home shows a's videos all the same
    }

    @Test
    fun aChannelThatWorkedBefore_keepsItsVideosWhenItFailsLater() {
        source.tab(a, listOf(video("a0000000001", a, NOW_MS - HOUR_MS, durationSeconds = 600)))
        source.tab(b, listOf(video("b0000000001", b, NOW_MS - 2 * HOUR_MS, durationSeconds = 600)))
        val home = home()
        assertThat(home.settled { it.videos.size >= 2 }.ids()).containsExactly("a0000000001", "b0000000001").inOrder()

        // Half an hour later both are asked again, and a's every way fails: its videos stay, and no error shows.
        now += KidsFeed.FRESH_MS + 1
        source.tabFails(a)
        source.playlistFails(longForm(a))
        home.refresh()
        val state = home.settled { it.failedChannels == 1 }
        assertThat(state.ids()).containsExactly("a0000000001", "b0000000001").inOrder()
        assertThat(state.failure).isNull()
    }
}
