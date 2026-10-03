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
import com.google.common.truth.Truth.assertThat
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

/**
 * The TV's search over the real YouTube, driven as the screen drives it: the first page, then «load more» until the results end
 * (what holding DOWN on the remote does), and the row of Shorts beside a «الكل» search. «The search shows only a fixed amount» was
 * measured here: YouTube's pages are small (2 to 14 items in «الكل») but there are dozens of them, so a search that stops after a
 * few is a bug of the app, not of YouTube. Skipped unless run with:
 *   ./gradlew :app:testDebugUnitTest --tests SearchRealNetworkTest -Dyoutube.realNetwork=true
 * It prints "REAL-RESULT" lines. YouTube answers a burst of requests with 403: the queries run one after the other, with a pause.
 */
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

    private fun source() = YouTube.create(ApplicationProvider.getApplicationContext(), OkHttpClient())

    private fun rules() = MutableStateFlow(ContentFilter.of(PolicyEngine(Clock.systemUTC()), Profile.newAdult("الكبار", id = ADULT_PROFILE_ID)))

    @Test
    fun scrollingToTheEnd_reachesWhatYouTubeHas() {
        val source = source()
        val rules = rules()
        for ((query, filter) in listOf("minecraft" to SearchFilter.ALL, "قرآن" to SearchFilter.ALL, "cats" to SearchFilter.VIDEOS)) {
            val vm = SearchViewModel(source, SubscriptionRepository(db, source), WatchRepository(db, MutableProfile()), rules)
            vm.setFilter(filter)
            vm.submit(query)
            var s = settled(vm)
            val first = s.items.size
            var loads = 0
            while (!s.endReached && s.moreError == null && s.error == null && loads < 80) {
                vm.loadMore()
                s = settled(vm)
                loads++
            }
            println("REAL-RESULT | TV search $query/$filter | first screen $first, then $loads loads: ${s.items.size} items, endReached=${s.endReached}, error=${s.error?.technicalName}, moreError=${s.moreError?.technicalName}")
            assertThat(s.error).isNull()
            assertThat(s.moreError).isNull()
            assertThat(s.endReached).isTrue()
            // Far more than the few a screen holds: pages of a few items each, many of them.
            assertThat(s.items.size).isAtLeast(60)
            Thread.sleep(PAUSE_MS)
        }
    }

    @Test
    fun aSearchOfEverything_hasItsShortsRow() {
        val source = source()
        val vm = SearchViewModel(source, SubscriptionRepository(db, source), WatchRepository(db, MutableProfile()), rules())
        vm.submit("minecraft")
        val shorts = runBlocking { withTimeout(60_000) { vm.shorts.first { it.isNotEmpty() } } }
        println("REAL-RESULT | TV search shorts row | ${shorts.size} Shorts: ${shorts.take(3).map { it.video.title }}")
        assertThat(shorts.size).isAtLeast(5)
        assertThat(shorts.all { it.video.isShortForm }).isTrue()
    }

    private companion object {
        const val PAUSE_MS = 5_000L
    }
}
