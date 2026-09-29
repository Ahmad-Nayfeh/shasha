package com.ahmadnayfeh.shasha.ui.screens.library

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.data.FakeVideoSource
import com.ahmadnayfeh.shasha.data.ProfileRepository
import com.ahmadnayfeh.shasha.data.SubscriptionRepository
import com.ahmadnayfeh.shasha.data.cancelAndWait
import com.ahmadnayfeh.shasha.data.channelId
import com.ahmadnayfeh.shasha.data.db.ShashaDatabase
import com.ahmadnayfeh.shasha.data.inMemoryDatabase
import com.ahmadnayfeh.shasha.data.subscription
import com.ahmadnayfeh.shasha.data.testScope
import com.ahmadnayfeh.shasha.policy.ContentFilter
import com.ahmadnayfeh.shasha.policy.ListedChannel
import com.ahmadnayfeh.shasha.policy.MutableClock
import com.ahmadnayfeh.shasha.policy.PolicyEngine
import com.ahmadnayfeh.shasha.policy.instantAt
import com.ahmadnayfeh.shasha.policy.owner
import com.ahmadnayfeh.shasha.ui.parental.ParentalLists
import com.ahmadnayfeh.shasha.ui.screens.settings.BlocklistViewModel
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
 * A channel the owner follows and then blocks for the household: الاشتراكات asks the rules like every other
 * list and no longer shows it, and قائمة الحظر offers to unsubscribe from it (nothing else could).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class BlockedSubscriptionsTest {
    private lateinit var db: ShashaDatabase
    private lateinit var subscriptions: SubscriptionRepository
    private lateinit var profiles: ProfileRepository
    private val engine = PolicyEngine(MutableClock(instantAt("2026-09-28T12:00")))
    private val viewModels = ViewModelStore()
    private val scope = testScope()

    private val kept = channelId(1)
    private val blocked = channelId(2)

    @Before
    fun setUp() = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        db = inMemoryDatabase()
        subscriptions = SubscriptionRepository(db, FakeVideoSource())
        profiles = ProfileRepository(db)
        profiles.ensureOwner("الكبار")
        db.subscriptions().insertAll(listOf(subscription(kept, name = "مطبخ سلمى"), subscription(blocked, name = "مقالب مخيفة")))
        ParentalLists(profiles).blockChannel(ListedChannel(blocked, "مقالب مخيفة"))
    }

    @After
    fun tearDown() {
        viewModels.clear()
        scope.cancelAndWait()
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun theSubscriptionsTab_listsOnlyWhatTheRulesShow() = runBlocking<Unit> {
        val adult = profiles.all().single()
        val filter = MutableStateFlow(ContentFilter.NOTHING)
        val vm = ViewModelProvider(viewModels, viewModelFactory { initializer { SubscriptionsViewModel(subscriptions, filter) } })[SubscriptionsViewModel::class.java]
        // Nothing before the profile is known.
        assertThat(vm.channels.value).isNull()

        filter.value = ContentFilter.of(engine, adult)
        val shown = withTimeout(5_000) { vm.channels.first { it != null }!! }
        assertThat(shown.map { it.channel.channelId }).containsExactly(kept)

        // Unblocked again: it is back.
        filter.value = ContentFilter.of(engine, owner())
        assertThat(withTimeout(5_000) { vm.channels.first { it?.size == 2 } }!!.map { it.channel.channelId }).containsExactly(kept, blocked)
    }

    @Test
    fun theBlocklist_offersToUnsubscribeFromABlockedChannel() = runBlocking<Unit> {
        val vm = ViewModelProvider(viewModels, viewModelFactory { initializer { BlocklistViewModel(profiles, subscriptions, scope) } })[BlocklistViewModel::class.java]
        assertThat(withTimeout(5_000) { vm.subscribed.first { it.isNotEmpty() } }).containsExactly(kept, blocked)
        assertThat(withTimeout(5_000) { vm.blocklist.first { it != null } }!!.channels.map { it.channelId }).containsExactly(blocked)

        vm.unsubscribe(blocked)
        assertThat(withTimeout(5_000) { vm.subscribed.first { blocked !in it } }).containsExactly(kept)
        // Still blocked: unsubscribing does not lift the block.
        assertThat(vm.blocklist.value!!.channels.map { it.channelId }).containsExactly(blocked)
    }
}
