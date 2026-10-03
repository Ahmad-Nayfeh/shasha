package com.ahmadnayfeh.shasha.ui.screens.search

import android.app.Application
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.data.ADULT_PROFILE_ID
import com.ahmadnayfeh.shasha.data.MutableProfile
import com.ahmadnayfeh.shasha.data.SubscriptionRepository
import com.ahmadnayfeh.shasha.data.TestDataStores
import com.ahmadnayfeh.shasha.data.WatchRepository
import com.ahmadnayfeh.shasha.data.db.ShashaDatabase
import com.ahmadnayfeh.shasha.data.inMemoryDatabase
import com.ahmadnayfeh.shasha.data.video
import com.ahmadnayfeh.shasha.policy.ContentFilter
import com.ahmadnayfeh.shasha.policy.PolicyEngine
import com.ahmadnayfeh.shasha.policy.Profile
import com.ahmadnayfeh.shasha.search.SearchHistory
import com.ahmadnayfeh.shasha.ui.FakeUiSource
import com.ahmadnayfeh.shasha.ui.model.VideoUi
import com.ahmadnayfeh.shasha.youtube.ItemsPage
import com.ahmadnayfeh.shasha.youtube.PageKey
import com.ahmadnayfeh.shasha.youtube.safety.SafeVideoSource
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Clock

/**
 * The built-in filter as the TV's search screen meets it, for the adult profile too: the screen is given the filtered source
 * ([SafeVideoSource], what AppContainer's `videoSource` is). A search it holds back is a search without results, the screen's own
 * «لا نتائج» state: no error, no message about why, and YouTube is never asked.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class ExplicitSearchTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var db: ShashaDatabase
    private lateinit var stores: TestDataStores
    private lateinit var history: SearchHistory
    private val extractor = FakeUiSource()
    private val engine = PolicyEngine(Clock.systemUTC())
    private val owner = Profile.newAdult("الكبار", id = ADULT_PROFILE_ID)
    private val rules = MutableStateFlow(ContentFilter.of(engine, owner))
    private lateinit var vm: SearchViewModel
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        db = inMemoryDatabase()
        stores = TestDataStores(tmp.root)
        history = SearchHistory(stores.open(SearchHistory.FILE_NAME))
        val source = SafeVideoSource(extractor)
        vm = SearchViewModel(source, SubscriptionRepository(db, source), WatchRepository(db, MutableProfile()), rules, history, debounceMs = 60)
        scope.launch { vm.suggestions.collect {} }
        scope.launch { vm.recent.collect {} }
    }

    @After
    fun tearDown() {
        stores.closeAll()
        db.close()
        Dispatchers.resetMain()
    }

    private fun <T> wait(block: suspend () -> T): T = runBlocking { withTimeout(5_000) { block() } }

    private fun titles() = vm.results.value.items.mapNotNull { (it as? VideoUi)?.video?.title }

    @Test
    fun anExplicitQuery_isASearchWithoutResults_notAnError_andYouTubeIsNeverAsked() {
        extractor.searchResult = { ItemsPage(listOf(video("a1", "UCa", null, title = "Cooking")), PageKey("2")) }

        vm.submit("Sexy twerking")

        val state = vm.results.value
        assertThat(state.items).isEmpty()
        assertThat(state.error).isNull()
        assertThat(state.loadingFirst).isFalse()
        assertThat(state.loadingMore).isFalse()
        assertThat(state.endReached).isTrue() // the screen's «لا نتائج» state, not a spinner that never ends
        assertThat(vm.query.value).isEqualTo("Sexy twerking")
        assertThat(extractor.searches).isEmpty()
    }

    @Test
    fun theSameQueryHidingBehindSymbolsAndOtherLanguages_isAskedOfNobody() {
        for (query in listOf("s e x y   t w e r k i n g", "S3XY TW3RK", "سكس", "رقص شرقي", "p0rn", "🔥sexy dance🔥", "xxx", "belly dance")) {
            vm.submit(query)
            assertThat(vm.results.value.items).isEmpty()
            assertThat(vm.results.value.error).isNull()
        }
        assertThat(extractor.searches).isEmpty()
    }

    @Test
    fun anOrdinaryQuery_isSearched_andOnlyTheExplicitResultsAreLeftOut() {
        val keep = video("k1", "UCa", null, title = "تعلم الرياضيات للمبتدئين")
        extractor.searchResult = {
            ItemsPage(
                listOf(
                    video("x1", "UCa", null, title = "Hot girls dancing"),
                    keep,
                    video("x2", "UCa", null, title = "Documentary", channelName = "Nude Hub"),
                    video("k2", "UCa", null, title = "Essex football highlights"),
                ),
                next = null,
            )
        }

        vm.submit("dance")

        assertThat(extractor.searches.map { it.query }).containsExactly("dance")
        assertThat(titles()).containsExactly("تعلم الرياضيات للمبتدئين", "Essex football highlights").inOrder()
    }

    @Test
    fun aPageThatIsAllExplicit_keepsItsNextPage_soTheRestOfTheResultsStillCome() {
        extractor.searchResult = { call ->
            when (call.page) {
                null -> ItemsPage(listOf(video("x1", "UCa", null, title = "Sexy dance"), video("x2", "UCa", null, title = "Porn star")), PageKey("2"))
                else -> ItemsPage(listOf(video("k1", "UCa", null, title = "Rocket science")), null)
            }
        }

        vm.submit("space")

        assertThat(extractor.searches.map { it.page }).containsExactly(null, PageKey("2")).inOrder()
        assertThat(titles()).containsExactly("Rocket science")
        assertThat(vm.results.value.endReached).isTrue()
    }

    @Test
    fun anExplicitQuery_isNotKeptAsARecentSearch_anOrdinaryOneIs() {
        vm.submit("الخط العربي")
        wait { vm.recent.first { it == listOf("الخط العربي") } }
        vm.submit("sexy twerking")
        vm.submit("وثائقي") // the edits are applied in order: when this one is there, the explicit one before it was dealt with
        wait { vm.recent.first { "وثائقي" in it } }
        assertThat(vm.recent.value).containsExactly("وثائقي", "الخط العربي").inOrder()
    }

    @Test
    fun aRecentSearchKeptByAnOlderVersion_isNotOfferedAgain() {
        val store = stores.open("older_search")
        val older = SearchHistory(store)
        runBlocking { store.edit { it[stringPreferencesKey("recent_$ADULT_PROFILE_ID")] = "porn videos\nقرآن كريم\nsexy twerking\nminecraft" } }
        assertThat(wait { older.observe(ADULT_PROFILE_ID).first() }).containsExactly("قرآن كريم", "minecraft").inOrder()
    }

    @Test
    fun suggestionsForAnExplicitStart_areNeverAskedFor_andOrdinaryOnesLoseTheirExplicitLines() {
        vm.onTextChanged("porn")
        runBlocking { delay(300) }
        assertThat(extractor.suggestionCalls).isEmpty()
        assertThat(vm.suggestions.value).isEmpty()

        extractor.suggest = { _, _ -> listOf("dance tutorial", "sexy dance", "dance for kids", "belly dance", "رقص شرقي") }
        vm.onTextChanged("dance")
        wait { vm.suggestions.first { it.isNotEmpty() } }
        assertThat(extractor.suggestionCalls).containsExactly("dance" to "en")
        assertThat(vm.suggestions.value).containsExactly("dance tutorial", "dance for kids").inOrder()
    }

    @Test
    fun aVoiceSearchIsJudgedLikeATypedOne() {
        assertThat(vm.onVoiceResults(listOf("sexy twerking"))).isEqualTo("sexy twerking")
        assertThat(vm.results.value.items).isEmpty()
        assertThat(vm.results.value.error).isNull()
        assertThat(extractor.searches).isEmpty()
    }
}
