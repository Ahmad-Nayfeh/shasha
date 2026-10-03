package com.ahmadnayfeh.shasha.ui.screens.playlist

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.data.MutableProfile
import com.ahmadnayfeh.shasha.data.WatchRepository
import com.ahmadnayfeh.shasha.data.db.ShashaDatabase
import com.ahmadnayfeh.shasha.data.inMemoryDatabase
import com.ahmadnayfeh.shasha.player.PlayQueues
import com.ahmadnayfeh.shasha.policy.Allowlist
import com.ahmadnayfeh.shasha.policy.ContentFilter
import com.ahmadnayfeh.shasha.policy.Decision
import com.ahmadnayfeh.shasha.policy.DenyReason
import com.ahmadnayfeh.shasha.policy.ListedChannel
import com.ahmadnayfeh.shasha.policy.PolicyEngine
import com.ahmadnayfeh.shasha.policy.Profile
import com.ahmadnayfeh.shasha.ui.paging.ScriptedSource
import com.ahmadnayfeh.shasha.ui.paging.ScriptedSource.Companion.CHANNEL
import com.google.common.truth.Truth.assertThat
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
 * A playlist opened from a channel's page (its Playlists section) knows whose it is: a child who may open that channel may
 * open its playlists, though they are not allowlisted themselves ([PolicyEngine.canShowPlaylist]); opened from anywhere else
 * (no owner) only an allowlisted playlist opens, as it always did.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class PlaylistOwnerAccessTest {
    private lateinit var db: ShashaDatabase
    private val engine = PolicyEngine(Clock.systemUTC())
    private val playlistId = "PLscripted0000000000000000000000001"

    /** Sara may open the channel, and no playlist of its own. */
    private val child = Profile.newChild("سارة", id = "kid").copy(allowlist = Allowlist(channels = listOf(ListedChannel(CHANNEL, "Channel"))))
    private val rules = MutableStateFlow(ContentFilter.of(engine, child))

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        db = inMemoryDatabase()
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private fun model(owner: String?, source: ScriptedSource = ScriptedSource()) =
        PlaylistViewModel(playlistId, source, WatchRepository(db, MutableProfile()), PlayQueues(), rules, owner)

    @Test
    fun aPlaylistOfTheChannelItWasOpenedFrom_opensForAChild_andIsRead() {
        val source = ScriptedSource().apply { playlistPages = ScriptedSource.pagesOf(5) }
        val vm = model(owner = CHANNEL, source = source)

        assertThat(vm.access.value!!.isAllowed).isTrue()
        assertThat(source.requests).containsExactly("playlist:0")
    }

    @Test
    fun withoutAnOwner_orWithAnotherChannelsOwner_aChildMayNotOpenIt_andNothingIsRead() {
        for (owner in listOf(null, "UCother000000000000000a1")) {
            val source = ScriptedSource().apply { playlistPages = ScriptedSource.pagesOf(5) }
            val vm = model(owner, source)

            val decision = vm.access.value!!
            assertThat(decision.isAllowed).isFalse()
            assertThat((decision as Decision.Deny).reason).isEqualTo(DenyReason.NOT_IN_ALLOWLIST)
            assertThat(source.requests).isEmpty()
        }
    }

    @Test
    fun anAdultOpensAnyPlaylist_withOrWithoutAnOwner() {
        rules.value = ContentFilter.of(engine, Profile.newAdult("الكبار", id = "adult"))
        assertThat(model(owner = null).access.value!!.isAllowed).isTrue()
        assertThat(model(owner = CHANNEL).access.value!!.isAllowed).isTrue()
    }
}
