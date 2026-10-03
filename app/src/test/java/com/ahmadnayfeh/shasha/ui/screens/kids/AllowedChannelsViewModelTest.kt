package com.ahmadnayfeh.shasha.ui.screens.kids

import android.app.Application
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.data.ScriptedChannelSource
import com.ahmadnayfeh.shasha.data.SubscriptionRepository
import com.ahmadnayfeh.shasha.data.channelId
import com.ahmadnayfeh.shasha.data.db.ShashaDatabase
import com.ahmadnayfeh.shasha.data.inMemoryDatabase
import com.ahmadnayfeh.shasha.policy.Allowlist
import com.ahmadnayfeh.shasha.policy.ContentFilter
import com.ahmadnayfeh.shasha.policy.ListedChannel
import com.ahmadnayfeh.shasha.policy.ListedPlaylist
import com.ahmadnayfeh.shasha.policy.MutableClock
import com.ahmadnayfeh.shasha.policy.PolicyEngine
import com.ahmadnayfeh.shasha.policy.Profile
import com.ahmadnayfeh.shasha.policy.instantAt
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
 * The child's «القنوات» page lists what the profile allows, with the names the parent saw when allowing them: the pictures are
 * an extra read from YouTube (each channel's own page, a playlist's details). Nothing that fails there can blank the page: a card
 * keeps its name and shows the first letter. The failures are only logged.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class AllowedChannelsViewModelTest {
    private lateinit var db: ShashaDatabase
    private val source = ScriptedChannelSource()
    private val engine = PolicyEngine(MutableClock(instantAt("2026-09-28T15:00")))
    private val a = channelId(1)
    private val b = channelId(2)
    private val playlist = "PLkids000000000000000001"
    private val child = Profile.newChild("سارة", id = "kid").copy(
        allowlist = Allowlist(
            channels = listOf(ListedChannel(a, "طيور الجنة"), ListedChannel(b, "سبيستون")),
            playlists = listOf(ListedPlaylist(playlist, "تعلم الحروف")),
        ),
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        db = inMemoryDatabase()
        ShadowLog.clear()
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun whenEveryChannelsDetailsFail_theListKeepsEveryChannelAndItsName_andTheFailuresAreLogged() {
        source.headerFails(a)
        source.headerFails(b)
        val kidsFeed = KidsFeed(source, isPlaying = { false }, lowPriority = { block -> block() })
        val vm = AllowedChannelsViewModel(MutableStateFlow(ContentFilter.of(engine, child)), SubscriptionRepository(db, source), kidsFeed)

        val state = runBlocking { withTimeout(5_000) { vm.state.first { it.loaded } } }
        assertThat(state.channels.map { it.channel.channelId to it.channel.name }).containsExactly(a to "طيور الجنة", b to "سبيستون").inOrder()
        // The playlist keeps the name the parent saw too (its details are not scripted: they fail as well).
        assertThat(state.playlists.map { it.playlist.title }).containsExactly("تعلم الحروف")

        val warnings = ShadowLog.getLogsForTag("AllowedChannels").filter { it.type == Log.WARN }.map { it.msg }
        assertThat(warnings).hasSize(3)
        assertThat(warnings.joinToString()).contains(a)
        assertThat(warnings.joinToString()).contains(b)
        assertThat(warnings.joinToString()).contains("ContentNotAvailableException")
    }
}
