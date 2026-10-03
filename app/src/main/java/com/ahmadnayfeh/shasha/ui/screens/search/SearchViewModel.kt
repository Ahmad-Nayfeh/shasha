package com.ahmadnayfeh.shasha.ui.screens.search

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ahmadnayfeh.shasha.data.BehaviorKeys
import com.ahmadnayfeh.shasha.data.asSourceException
import com.ahmadnayfeh.shasha.data.SubscriptionRepository
import com.ahmadnayfeh.shasha.data.WatchRepository
import com.ahmadnayfeh.shasha.policy.ContentFilter
import com.ahmadnayfeh.shasha.search.RecentSearches
import com.ahmadnayfeh.shasha.ui.model.ItemUi
import com.ahmadnayfeh.shasha.ui.model.VideoUi
import com.ahmadnayfeh.shasha.ui.model.source
import com.ahmadnayfeh.shasha.ui.model.toUi
import com.ahmadnayfeh.shasha.ui.paging.Page
import com.ahmadnayfeh.shasha.ui.paging.PagedLoader
import com.ahmadnayfeh.shasha.ui.paging.PagedState
import com.ahmadnayfeh.shasha.ui.screens.watchedFractions
import com.ahmadnayfeh.shasha.youtube.ChannelItem
import com.ahmadnayfeh.shasha.youtube.SearchFilter
import com.ahmadnayfeh.shasha.youtube.VideoItem
import com.ahmadnayfeh.shasha.youtube.VideoSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * البحث (adult profile only: a child's navigation graph has no search). A search runs when the user
 * submits it or changes the filter, never while typing; results come page by page as focus nears their
 * end. Results the profile's rules hide (blocked channels and keywords, shorts) are never shown, and one
 * blocked from a card's menu disappears at once.
 *
 * v1.1 (typing with a remote is slow): YouTube's suggestions while typing ([onTextChanged], [debounceMs] after the
 * last key, the newest request only, recent answers cached), the profile's recent searches while the box is empty
 * ([history]), and the microphone's other readings as choices ([voiceAlternatives]). All of it exists only for a
 * profile with search ([ContentFilter.features]), and every text passes the profile's blocked words first.
 *
 * v1.4: a «الكل» search of a profile that shows Shorts has a row of Shorts beside its results ([shorts]): YouTube's search finds
 * Shorts too, which its plain results leave out (VideoSource.searchShorts). They arrive beside the results, never before them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModel(
    private val source: VideoSource,
    private val subscriptions: SubscriptionRepository,
    watch: WatchRepository,
    private val contentRules: StateFlow<ContentFilter>,
    private val history: RecentSearches? = null,
    private val debounceMs: Long = SUGGEST_DEBOUNCE_MS,
) : ViewModel() {

    private val _query = MutableStateFlow("")

    /** The last submitted query ("" = nothing searched yet). */
    val query: StateFlow<String> = _query.asStateFlow()

    private val _filter = MutableStateFlow(SearchFilter.ALL)
    val filter: StateFlow<SearchFilter> = _filter.asStateFlow()

    // A page is not a screenful (see PagedLoader): what the profile's rules hide counts for nothing, so pages keep
    // coming until the screen is full or the results end.
    private val loader = PagedLoader(viewModelScope, ItemUi::key, shown = { contentRules.value.items(listOf(it.source())).isNotEmpty() }) { page ->
        val result = source.search(_query.value, _filter.value, page)
        Page(result.items.map { it.toUi() }, result.next)
    }

    val results: StateFlow<PagedState<ItemUi>> = combine(loader.state, contentRules) { state, rules ->
        state.copy(items = state.items.filter { rules.items(listOf(it.source())).isNotEmpty() })
    }.stateIn(viewModelScope, SharingStarted.Eagerly, loader.state.value)

    /** The Shorts the search found, as YouTube gave them (before the profile's rules); empty when none were asked for or found. */
    private val foundShorts = MutableStateFlow<List<VideoItem>>(emptyList())
    private var shortsJob: Job? = null

    /**
     * «Shorts»: the row beside the results of a «الكل» search, as the profile's rules allow (none where it hides Shorts, a blocked
     * word's title, a blocked channel). Empty while they are on their way, when YouTube found none, and when asking failed: the
     * results never wait for them and never fail for them.
     */
    val shorts: StateFlow<List<VideoUi>> = combine(foundShorts, contentRules) { found, rules ->
        if (!rules.showsShorts || rules.features?.search != true) emptyList() else rules.videos(found).take(MAX_SHORTS).map { it.toUi() }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Ids of subscribed channels, for the subscribe buttons on channel results. */
    val subscribed: StateFlow<Set<String>> = subscriptions.observe()
        .map { channels -> channels.mapTo(HashSet()) { it.channelId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val watched: StateFlow<Map<String, Float>> =
        watchedFractions(watch, results.map { s -> s.items.mapNotNull { (it as? VideoUi)?.video?.videoId } })
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val typed = MutableStateFlow("")

    /** YouTube's recent answers, by language and text (the same letters typed again cost nothing). */
    private val suggestionCache = object : LinkedHashMap<String, List<String>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<String>>?) = size > CACHE_SIZE
    }

    private val searchAllowed: Boolean get() = contentRules.value.features?.search == true

    /** Up to 8 suggestions for the text in the box; none for an empty box, a profile without search, or offline. */
    val suggestions: StateFlow<List<String>> = combine(typed, contentRules) { text, rules -> text.trim() to rules }
        .distinctUntilChanged { a, b -> a.first == b.first && a.second === b.second }
        .mapLatest { (text, rules) ->
            if (text.isEmpty() || rules.features?.search != true) return@mapLatest emptyList()
            val language = languageOf(text)
            val key = "$language|${text.lowercase()}"
            val cached = synchronized(suggestionCache) { suggestionCache[key] }
            val words = cached ?: run {
                delay(debounceMs) // a newer key press cancels this one (mapLatest): only the last text is asked for
                try {
                    source.suggestions(text, language).also { synchronized(suggestionCache) { suggestionCache[key] = it } }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    emptyList() // no suggestions is fine: the search itself shows any real failure
                }
            }
            words.filter { rules.allowsText(it) && !it.equals(text, ignoreCase = true) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The profile's recent searches (newest first), shown while the box is empty. */
    val recent: StateFlow<List<String>> = contentRules
        .map { it.profile?.id?.takeIf { _ -> it.features?.search == true } }
        .distinctUntilChanged()
        .flatMapLatest { id -> if (id == null || history == null) flowOf(emptyList()) else history.observe(id) }
        .combine(contentRules) { list, rules -> list.filter(rules::allowsText) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _voiceAlternatives = MutableStateFlow<List<String>>(emptyList())

    /** The microphone's other readings after a voice search ran with its best one; cleared when the text changes. */
    val voiceAlternatives: StateFlow<List<String>> = _voiceAlternatives.asStateFlow()

    /** Every change of the text in the box (typing, a suggestion, the microphone). */
    fun onTextChanged(text: String) {
        if (text.trim() != typed.value.trim()) _voiceAlternatives.value = emptyList()
        typed.value = text
    }

    /**
     * The microphone heard [words] (best first): search the best allowed one at once and offer the others. Returns the
     * text searched (the box shows it), or null when nothing usable was heard.
     */
    fun onVoiceResults(words: List<String>): String? {
        val rules = contentRules.value
        if (rules.features?.search != true) return null
        val allowed = words.map { it.trim() }.filter { it.isNotEmpty() && rules.allowsText(it) }.distinct()
        val best = allowed.firstOrNull() ?: return null
        typed.value = best
        submit(best)
        _voiceAlternatives.value = allowed.drop(1)
        return best
    }

    /**
     * A suggestion, a recent search or one of the microphone's other readings was chosen: search it at once. Choosing a
     * reading keeps the others offered, the one searched before among them.
     */
    fun pick(text: String, alternative: Boolean) {
        val chosen = text.trim()
        if (chosen.isEmpty()) return
        val before = _query.value
        typed.value = chosen
        submit(chosen)
        _voiceAlternatives.value = if (alternative) {
            (listOfNotNull(before.takeIf { it.isNotEmpty() }) + _voiceAlternatives.value).filterNot { it == chosen }.distinct()
        } else {
            emptyList()
        }
    }

    fun clearRecent() {
        val id = contentRules.value.profile?.id ?: return
        viewModelScope.launch { history?.clear(id) }
    }

    fun submit(text: String) {
        val q = text.trim()
        if (q.isEmpty()) return
        if (searchAllowed) contentRules.value.profile?.id?.let { id -> viewModelScope.launch { history?.add(id, q) } }
        if (q == _query.value && results.value.error == null && !results.value.loadingFirst && results.value.items.isNotEmpty()) return
        _query.value = q
        loader.start()
        startShorts()
    }

    fun setFilter(filter: SearchFilter) {
        if (filter == _filter.value) return
        _filter.value = filter
        if (_query.value.isNotEmpty()) loader.start()
        startShorts()
    }

    /**
     * Asks for the Shorts of the query that is searched, beside its results: only for «الكل» (the other filters name what they
     * want), only where the profile shows Shorts and searches. What an earlier search was asking for is dropped.
     */
    private fun startShorts() {
        shortsJob?.cancel()
        foundShorts.value = emptyList()
        val query = _query.value
        if (query.isEmpty() || _filter.value != SearchFilter.ALL || !searchAllowed || !contentRules.value.showsShorts) return
        shortsJob = viewModelScope.launch {
            try {
                foundShorts.value = source.searchShorts(query).shorts.distinctBy { it.videoId }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // An extra beside the results: no row, never the error screen.
                Log.w(TAG, "No Shorts for the search: ${e.asSourceException().technicalName}")
            }
        }
    }

    fun loadMore() = loader.loadMore()

    fun retryMore() = loader.retryMore()

    /** The error screen's «رجوع»: back to an empty search, the typed text stays to be edited. */
    fun clearResults() {
        _query.value = ""
        loader.reset()
        startShorts()
    }

    companion object {
        /** Wait this long after the last key before asking YouTube (typing on a remote is slow; this is one pause). */
        const val SUGGEST_DEBOUNCE_MS = 250L
        private const val CACHE_SIZE = 64

        /** The Shorts row's length: YouTube's first page of Shorts is about 25. */
        const val MAX_SHORTS = 24
        private const val TAG = "SearchViewModel"

        /** Arabic letters ask for Arabic suggestions, anything else for English ones. */
        fun languageOf(text: String): String = if (text.any { it in '\u0600'..'\u06FF' }) "ar" else "en"

        /** The recognizer's tag for a stored choice («لغة البحث الصوتي»). */
        fun voiceTag(stored: String?): String = stored?.takeIf { it in BehaviorKeys.VOICE_LANGUAGES } ?: BehaviorKeys.VOICE_LANGUAGES.first()
    }

    fun toggleSubscription(channel: ChannelItem) {
        val subscribedNow = channel.channelId in subscribed.value
        viewModelScope.launch {
            if (subscribedNow) subscriptions.remove(channel.channelId) else subscriptions.add(channel)
        }
    }
}
