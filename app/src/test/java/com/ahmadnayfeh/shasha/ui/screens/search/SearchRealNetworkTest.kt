package com.ahmadnayfeh.shasha.ui.screens.search

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.data.ADULT_PROFILE_ID
import com.ahmadnayfeh.shasha.data.MutableProfile
import com.ahmadnayfeh.shasha.data.SubscriptionRepository
import com.ahmadnayfeh.shasha.data.WatchRepository
import com.ahmadnayfeh.shasha.data.db.ShashaDatabase
import com.ahmadnayfeh.shasha.data.inMemoryDatabase
import com.ahmadnayfeh.shasha.policy.ContentFilter
import com.ahmadnayfeh.shasha.policy.PolicyEngine
import com.ahmadnayfeh.shasha.policy.Profile
import com.ahmadnayfeh.shasha.ui.paging.PagedState
import com.ahmadnayfeh.shasha.youtube.SearchFilter
import com.ahmadnayfeh.shasha.youtube.YouTube
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Clock

/** TEMPORARY probe: how far the TV's search can be scrolled against the real YouTube. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class SearchRealNetworkTest {
    private lateinit var db: ShashaDatabase

    @Before
    fun setUp() {
        assumeTrue("real-network tests disabled", System.getProperty("youtube.realNetwork") == "true")
        Dispatchers.setMain(Dispatchers.Unconfined)
        db = inMemoryDatabase()
    }

    @After
    fun tearDown() {
        if (::db.isInitialized) db.close()
        Dispatchers.resetMain()
    }

    private fun settled(vm: SearchViewModel): PagedState<*> = runBlocking {
        withTimeout(120_000) { vm.results.first { !it.loadingFirst && !it.loadingMore } }
    }

    @Test
    fun scrollingToTheEnd() {
        val source = YouTube.create(ApplicationProvider.getApplicationContext(), OkHttpClient())
        val rules = MutableStateFlow(ContentFilter.of(PolicyEngine(Clock.systemUTC()), Profile.newAdult("الكبار", id = ADULT_PROFILE_ID)))
        for ((query, filter) in listOf("minecraft" to SearchFilter.ALL, "cats" to SearchFilter.ALL, "قرآن" to SearchFilter.ALL, "cats" to SearchFilter.VIDEOS)) {
            val vm = SearchViewModel(source, SubscriptionRepository(db, source), WatchRepository(db, MutableProfile()), rules)
            vm.setFilter(filter)
            vm.submit(query)
            var s = settled(vm)
            val first = s.items.size
            var loads = 0
            val sizes = StringBuilder()
            while (!s.endReached && s.moreError == null && s.error == null && loads < 80) {
                vm.loadMore()
                s = settled(vm)
                loads++
                sizes.append(s.items.size).append(' ')
            }
            println("REAL-RESULT | TV search $query/$filter | first screen $first, then $loads loadMore calls → ${s.items.size} items, endReached=${s.endReached}, error=${s.error?.technicalName}, moreError=${s.moreError?.technicalName} :: $sizes")
        }
    }
}
