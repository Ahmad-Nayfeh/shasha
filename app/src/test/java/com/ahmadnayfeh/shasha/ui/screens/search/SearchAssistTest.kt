package com.ahmadnayfeh.shasha.ui.screens.search

import android.app.Application
import android.speech.SpeechRecognizer
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.data.ADULT_PROFILE_ID
import com.ahmadnayfeh.shasha.data.MutableProfile
import com.ahmadnayfeh.shasha.data.SubscriptionRepository
import com.ahmadnayfeh.shasha.data.TestDataStores
import com.ahmadnayfeh.shasha.data.WatchRepository
import com.ahmadnayfeh.shasha.data.db.ShashaDatabase
import com.ahmadnayfeh.shasha.data.inMemoryDatabase
import com.ahmadnayfeh.shasha.policy.Blocklist
import com.ahmadnayfeh.shasha.policy.ContentFilter
import com.ahmadnayfeh.shasha.policy.PolicyEngine
import com.ahmadnayfeh.shasha.policy.Profile
import com.ahmadnayfeh.shasha.search.SearchHistory
import com.ahmadnayfeh.shasha.search.VoiceError
import com.ahmadnayfeh.shasha.search.VoiceSearch
import com.ahmadnayfeh.shasha.search.VoiceSearchRequests
import com.ahmadnayfeh.shasha.ui.FakeUiSource
import com.ahmadnayfeh.shasha.youtube.ItemsPage
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

/** v1.1: suggestions while typing, recent searches, the microphone's readings, and who gets none of it. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class SearchAssistTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var db: ShashaDatabase
    private lateinit var stores: TestDataStores
    private lateinit var history: SearchHistory
    private val source = FakeUiSource()
    private val engine = PolicyEngine(Clock.systemUTC())
    private val owner = Profile.newAdult("الكبار", id = ADULT_PROFILE_ID)
    private val child = Profile.newChild("سارة", id = "kid")
    private val rules = MutableStateFlow(ContentFilter.of(engine, owner))
    private lateinit var vm: SearchViewModel
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        db = inMemoryDatabase()
        stores = TestDataStores(tmp.root)
        history = SearchHistory(stores.open(SearchHistory.FILE_NAME))
        source.searchResult = { ItemsPage(emptyList(), null) }
        vm = SearchViewModel(source, SubscriptionRepository(db, source), WatchRepository(db, MutableProfile()), rules, history, debounceMs = 120)
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

    @Test
    fun typing_asksOnceAfterThePause_forTheLastTextOnly() {
        "وثائ".forEachIndexed { i, _ -> vm.onTextChanged("وثائ".take(i + 1)); runBlocking { delay(30) } }
        wait { vm.suggestions.first { it.isNotEmpty() } }
        // Only the last text, in Arabic; the typed text itself is not suggested back.
        assertThat(source.suggestionCalls).containsExactly("وثائ" to "ar")
        assertThat(vm.suggestions.value).containsExactly("وثائ 1", "وثائ 2").inOrder()
        vm.onTextChanged("mine")
        wait { vm.suggestions.first { it.firstOrNull()?.startsWith("mine") == true } }
        assertThat(source.suggestionCalls.last()).isEqualTo("mine" to "en")
    }

    @Test
    fun recentAnswersAreCached_andAnEmptyBoxAsksNothing() {
        vm.onTextChanged("قرآن")
        wait { vm.suggestions.first { it.isNotEmpty() } }
        vm.onTextChanged("")
        wait { vm.suggestions.first { it.isEmpty() } }
        vm.onTextChanged("قرآن")
        wait { vm.suggestions.first { it.isNotEmpty() } }
        assertThat(source.suggestionCalls).hasSize(1)
    }

    @Test
    fun aFailureShowsNoSuggestions_neverAnError() {
        source.suggest = { _, _ -> throw com.ahmadnayfeh.shasha.youtube.SourceException(com.ahmadnayfeh.shasha.youtube.SourceErrorKind.NETWORK, "x", null, null) }
        vm.onTextChanged("abc")
        runBlocking { delay(400) }
        assertThat(vm.suggestions.value).isEmpty()
        assertThat(vm.results.value.error).isNull()
    }

    @Test
    fun blockedWords_neverAppear_inSuggestionsRecentOrVoice() {
        rules.value = ContentFilter.of(engine, owner.copy(blocklist = Blocklist(titleKeywords = listOf("رعب"))))
        source.suggest = { _, _ -> listOf("فيلم رعب", "فيلم كرتون") }
        vm.onTextChanged("فيلم")
        wait { vm.suggestions.first { it.isNotEmpty() } }
        assertThat(vm.suggestions.value).containsExactly("فيلم كرتون")
        assertThat(vm.onVoiceResults(listOf("قصص رعب", "قصص قصيرة"))).isEqualTo("قصص قصيرة")
    }

    @Test
    fun aChildProfile_getsNoSuggestionsNoRecentAndNoVoice() {
        rules.value = ContentFilter.of(engine, child)
        vm.onTextChanged("كرتون")
        runBlocking { delay(400) }
        assertThat(source.suggestionCalls).isEmpty()
        assertThat(vm.suggestions.value).isEmpty()
        assertThat(vm.onVoiceResults(listOf("كرتون"))).isNull()
        assertThat(source.searches).isEmpty()
        vm.submit("كرتون")
        runBlocking { delay(200) }
        assertThat(wait { history.observe("kid").first() }).isEmpty()
    }

    @Test
    fun submittedSearches_becomeRecent_newestFirst_perProfile() {
        vm.submit("أول")
        vm.submit("ثاني")
        vm.submit(" أول ")
        wait { vm.recent.first { it == listOf("أول", "ثاني") } }
        vm.clearRecent()
        wait { vm.recent.first { it.isEmpty() } }
    }

    @Test
    fun voice_searchesTheBest_offersTheOthers_andPickingOneKeepsTheRest() {
        assertThat(vm.onVoiceResults(listOf(" وثائقي المحيط ", "وثائقي المحيطات", "وثائق المحيط"))).isEqualTo("وثائقي المحيط")
        assertThat(vm.query.value).isEqualTo("وثائقي المحيط")
        assertThat(vm.voiceAlternatives.value).containsExactly("وثائقي المحيطات", "وثائق المحيط").inOrder()
        vm.pick("وثائقي المحيطات", alternative = true)
        assertThat(vm.query.value).isEqualTo("وثائقي المحيطات")
        assertThat(vm.voiceAlternatives.value).containsExactly("وثائقي المحيط", "وثائق المحيط").inOrder()
        // Typing something else forgets them.
        vm.onTextChanged("شيء آخر")
        assertThat(vm.voiceAlternatives.value).isEmpty()
        assertThat(vm.onVoiceResults(emptyList())).isNull()
    }

    @Test
    fun theInAppRecognizer_errorsMapToCalmMessages() {
        assertThat(VoiceSearch.errorOf(SpeechRecognizer.ERROR_NO_MATCH)).isEqualTo(VoiceError.NO_MATCH)
        assertThat(VoiceSearch.errorOf(SpeechRecognizer.ERROR_SPEECH_TIMEOUT)).isEqualTo(VoiceError.TIMEOUT)
        assertThat(VoiceSearch.errorOf(SpeechRecognizer.ERROR_NETWORK)).isEqualTo(VoiceError.NETWORK)
        assertThat(VoiceSearch.errorOf(SpeechRecognizer.ERROR_SERVER)).isEqualTo(VoiceError.NETWORK)
        assertThat(VoiceSearch.errorOf(SpeechRecognizer.ERROR_RECOGNIZER_BUSY)).isEqualTo(VoiceError.BUSY)
        assertThat(VoiceSearch.errorOf(SpeechRecognizer.ERROR_AUDIO)).isEqualTo(VoiceError.AUDIO)
        assertThat(VoiceSearch.errorOf(12)).isEqualTo(VoiceError.LANGUAGE)
        // The in-app recognizer is the fallback now (the system's voice screen was tried first): its refusals are shown.
        assertThat(VoiceSearch.errorOf(SpeechRecognizer.ERROR_CLIENT)).isEqualTo(VoiceError.OTHER)
        assertThat(VoiceSearch.errorOf(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)).isEqualTo(VoiceError.PERMISSION)
        assertThat(VoiceSearch.levelOf(-2f)).isEqualTo(0f)
        assertThat(VoiceSearch.levelOf(10f)).isEqualTo(1f)
        assertThat(VoiceSearch.cleanResults(listOf(" a ", "A", "", null, "b", "c", "d"))).containsExactly("a", "b", "c").inOrder()
        val intent = VoiceSearch.recognizerIntent("ar-SA", "pkg")
        assertThat(intent.getStringExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE)).isEqualTo("ar-SA")
        assertThat(intent.getStringExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE)).isEqualTo("ar-SA")
        assertThat(intent.getStringExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL)).isEqualTo(android.speech.RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
        assertThat(intent.getBooleanExtra(android.speech.RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)).isTrue()
        assertThat(intent.getIntExtra(android.speech.RecognizerIntent.EXTRA_MAX_RESULTS, 0)).isEqualTo(3)
    }

    @Test
    fun theSearchKey_requestExpiresUnanswered() {
        var now = 0L
        val requests = VoiceSearchRequests { now }
        assertThat(requests.consume()).isFalse()
        requests.request()
        assertThat(requests.consume()).isTrue()
        assertThat(requests.consume()).isFalse()
        requests.request()
        val at = requests.pending.value!!
        assertThat(requests.isFresh(at)).isTrue()
        now += 60_000
        // Left over (pressed in a child profile, say): neither the tab nor the microphone opens for it any more.
        assertThat(requests.isFresh(at)).isFalse()
        assertThat(requests.consume()).isFalse()
        assertThat(requests.pending.value).isNull()
        assertThat(SearchViewModel.voiceTag("en-US")).isEqualTo("en-US")
        assertThat(SearchViewModel.voiceTag("xx")).isEqualTo("ar-SA")
    }
}
