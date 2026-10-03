package com.ahmadnayfeh.shasha.ui.paging

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.TV_1080P
import com.ahmadnayfeh.shasha.data.ADULT_PROFILE_ID
import com.ahmadnayfeh.shasha.data.MutableProfile
import com.ahmadnayfeh.shasha.data.SubscriptionRepository
import com.ahmadnayfeh.shasha.data.WatchRepository
import com.ahmadnayfeh.shasha.data.db.ShashaDatabase
import com.ahmadnayfeh.shasha.data.inMemoryDatabase
import com.ahmadnayfeh.shasha.policy.ContentFilter
import com.ahmadnayfeh.shasha.policy.PolicyEngine
import com.ahmadnayfeh.shasha.policy.Profile
import com.ahmadnayfeh.shasha.ui.UiFixtures.NOW
import com.ahmadnayfeh.shasha.ui.components.ContentFocus
import com.ahmadnayfeh.shasha.ui.paging.ScriptedSource.Companion.pagesOf
import com.ahmadnayfeh.shasha.ui.screens.search.SearchCallbacks
import com.ahmadnayfeh.shasha.ui.screens.search.SearchScreen
import com.ahmadnayfeh.shasha.ui.screens.search.SearchViewModel
import com.ahmadnayfeh.shasha.ui.theme.ShashaTheme
import com.ahmadnayfeh.shasha.youtube.ItemsPage
import com.ahmadnayfeh.shasha.youtube.PageKey
import com.ahmadnayfeh.shasha.youtube.SearchFilter
import com.ahmadnayfeh.shasha.youtube.VideoSource
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Clock

/**
 * «The search shows only a fixed amount»: the whole screen, as a viewer uses it. A search whose pages come slowly (a real
 * network's latency) is scrolled to its end with the remote's DOWN key, and what the viewer can reach must be everything the
 * source has.
 *
 * What this found: the screen asks for the next page when the focus nears the end of the list, once per list size, and the
 * loader ignores a request that comes while a page is loading. On a TV the focus stays on the last row through the whole load
 * (there is nothing below to scroll to), so a request made while loading was lost and nothing asked again: the list stood still,
 * with pages left at YouTube, until the viewer moved up and down again. The request is now made again when the load is over.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = TV_1080P, application = Application::class)
class SearchScrollTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var db: ShashaDatabase

    /** Requests for the next page that came while a page was loading: the loader drops those, so each one is a page the viewer waits for in vain. */
    private var lostRequests = 0
    private val scripted = ScriptedSource()
    private val rules = MutableStateFlow(ContentFilter.of(PolicyEngine(Clock.systemUTC()), Profile.newAdult("الكبار", id = ADULT_PROFILE_ID)))

    /** [ScriptedSource] with a network's delay on every page. */
    private class Slow(private val source: ScriptedSource, private val latencyMs: Long) : VideoSource by source {
        override suspend fun search(query: String, filter: SearchFilter, page: PageKey?): ItemsPage {
            delay(latencyMs)
            return source.search(query, filter, page)
        }
    }

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

    @Composable
    private fun Screen(vm: SearchViewModel) {
        val query by vm.query.collectAsStateWithLifecycle()
        val filter by vm.filter.collectAsStateWithLifecycle()
        val results by vm.results.collectAsStateWithLifecycle()
        ShashaTheme {
            SearchScreen(
                query = query,
                filter = filter,
                results = results,
                subscribed = vm.subscribed.collectAsStateWithLifecycle(),
                watched = remember { mutableStateOf(emptyMap()) },
                nowMs = NOW,
                focus = remember { ContentFocus() },
                extractorVersion = "test",
                callbacks = SearchCallbacks(
                    {},
                    {},
                    onLoadMore = {
                        if (vm.results.value.loadingMore) lostRequests++
                        vm.loadMore()
                    },
                    onRetryMore = vm::retryMore,
                    onDismissError = {},
                    onToggleSubscription = {},
                    onOpen = {},
                    onCheckUpdates = {},
                ),
            )
        }
    }

    /** One press of DOWN, then the pause of a hand holding the key (a remote repeats a held key every 50 to 100 ms). */
    private fun down() {
        compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        compose.waitForIdle()
        Thread.sleep(PRESS_INTERVAL_MS)
    }

    /** Searches, puts the focus on the first card and holds DOWN for up to [presses], stopping at the end of the results: what the viewer reached. */
    private fun scrollThrough(sizes: IntArray, latencyMs: Long, presses: Int): Int {
        scripted.searchPages = pagesOf(*sizes)
        val vm = SearchViewModel(Slow(scripted, latencyMs), SubscriptionRepository(db, scripted), WatchRepository(db, MutableProfile()), rules)
        compose.setContent { Screen(vm) }
        vm.submit("minecraft")
        compose.waitUntil(30_000) { vm.results.value.items.isNotEmpty() }
        compose.waitForIdle()
        compose.onAllNodes(hasClickAction() and hasAnyAncestor(hasTestTag("search_results")))[0].performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        repeat(presses) {
            down()
            if (vm.results.value.endReached) return vm.results.value.items.size
        }
        // The last page asked for arrives.
        runCatching { compose.waitUntil(SETTLE_MS) { !vm.results.value.loadingMore } }
        return vm.results.value.items.size
    }

    @Test
    fun aSlowSearchOfSmallPages_canBeScrolledToItsEnd() {
        // The sizes YouTube's «all» search really gave through the extractor: 2 to 14 items.
        val sizes = intArrayOf(7, 6, 4, 5, 9, 5, 5, 10, 13, 5, 5, 14, 9, 14, 8, 12, 11, 13, 12, 11, 12, 14, 12, 12)
        assertThat(scrollThrough(sizes, latencyMs = 60, presses = 400)).isEqualTo(sizes.sum())
        assertThat(lostRequests).isEqualTo(0)
    }

    @Test
    fun aSlowSearchOfPagesOfTwenty_canBeScrolledToItsEnd() {
        val sizes = IntArray(14) { 20 }
        assertThat(scrollThrough(sizes, latencyMs = 150, presses = 400)).isEqualTo(sizes.sum())
        assertThat(lostRequests).isEqualTo(0)
    }

    private companion object {
        const val PRESS_INTERVAL_MS = 50L
        const val SETTLE_MS = 10_000L
    }
}
