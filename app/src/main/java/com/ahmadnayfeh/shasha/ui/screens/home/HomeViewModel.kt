package com.ahmadnayfeh.shasha.ui.screens.home

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ahmadnayfeh.shasha.data.FeedRepository
import com.ahmadnayfeh.shasha.data.HomeSuggestions
import com.ahmadnayfeh.shasha.data.SubscriptionRepository
import com.ahmadnayfeh.shasha.data.SyncHealth
import com.ahmadnayfeh.shasha.data.WatchRepository
import com.ahmadnayfeh.shasha.policy.ContentFilter
import com.ahmadnayfeh.shasha.ui.model.VideoUi
import com.ahmadnayfeh.shasha.ui.model.toUi
import com.ahmadnayfeh.shasha.ui.screens.watchedFractions
import com.ahmadnayfeh.shasha.youtube.SourceErrorKind
import com.ahmadnayfeh.shasha.youtube.SourceException
import com.ahmadnayfeh.shasha.youtube.VideoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Immutable
data class HomeState(
    val loaded: Boolean = false,
    /**
     * Newest first (SPEC §2): the subscribed channels' uploads, nothing else. Where the profile shows Shorts they are in
     * [shorts] instead (the home's own row), never twice.
     */
    val videos: List<VideoUi> = emptyList(),
    val subscriptionCount: Int = 0,
    /** «Shorts»: the subscribed channels' newest Shorts (empty where the profile hides them). */
    val shorts: List<VideoUi> = emptyList(),
    /** «مقترحة لك»: suggestions beside the last videos watched, as the profile's rules allow (empty when off). */
    val suggested: List<VideoUi> = emptyList(),
) {
    /** Something to show in the feed's form: videos, or Shorts only. */
    val hasFeed: Boolean get() = videos.isNotEmpty() || shorts.isNotEmpty()
}

/** The feed sync as the home screen tells it: running, waiting, or failing (and why). */
@Immutable
data class HomeSync(
    val running: Boolean = false,
    val online: Boolean = true,
    /** Null until read from the database. */
    val health: SyncHealth? = null,
) {
    /** The first videos are on their way: a run is going, or channels never synced wait for one that can start. */
    val busy: Boolean get() = running || health == null || (health.neverSynced > 0 && online)

    /**
     * Why the feed does not update (SPEC §1: shown, never silent): no network while channels wait for their
     * first sync or the last run failed; else the newest failure when most channels failed. Null while a
     * run is going: it may well succeed.
     */
    val failure: SourceException? get() = when {
        running || health == null -> null
        !online && (health.neverSynced > 0 || health.isFailing) -> OFFLINE
        health.isFailing -> health.lastError
        else -> null
    }

    private companion object {
        val OFFLINE = SourceException(SourceErrorKind.NETWORK, "NoConnection", "The TV has no validated network", null)
    }
}

/**
 * The home feed. Everything comes from the local database, which the feed sync fills; the screen
 * never fetches from YouTube itself. [refresh] is the header's «تحديث» (a sync run in WorkManager).
 * Only what the current profile's rules allow is shown ([filter]: blocked channels and keywords, shorts);
 * nothing before the profile is known.
 *
 * Two rows go with the feed: «Shorts» (the feed's own Shorts, where the profile shows them) and «مقترحة لك»
 * ([suggestions]: what is suggested beside the last videos watched, where the profile shows suggestions). The second
 * never waits for YouTube: it starts with what [HomeSuggestions] keeps (often nothing) and fills in from the background.
 */
class HomeViewModel(
    feed: FeedRepository,
    subscriptions: SubscriptionRepository,
    watch: WatchRepository,
    online: Flow<Boolean>,
    private val syncNow: () -> Unit,
    filter: StateFlow<ContentFilter>,
    suggestions: HomeSuggestions? = null,
) : ViewModel() {

    /** What the row is made of, before the profile's rules (none without [suggestions]). */
    private val suggestedVideos: Flow<List<VideoItem>> = suggestedVideos(watch, filter, suggestions)

    val state: StateFlow<HomeState> =
        combine(feed.observeHome(), subscriptions.observe(), filter, suggestedVideos) { items, channels, rules, suggested ->
            val visible = rules.videos(items.map { it.toVideoItem() })
            val shorts = if (rules.showsShorts) visible.filter(rules::isShort) else emptyList()
            HomeState(
                loaded = rules.profile != null,
                videos = (if (shorts.isEmpty()) visible else visible.filterNot(rules::isShort)).map { it.toUi() },
                subscriptionCount = channels.size,
                shorts = shorts.take(SHORTS_LIMIT).map { it.toUi() },
                suggested = rules.suggestions(suggested).take(HomeSuggestions.ROW_LIMIT).map { it.toUi() },
            )
        }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), HomeState())

    /** Watched fractions of the videos on screen (the feed and both rows), for the red bars under their thumbnails. */
    val watched: StateFlow<Map<String, Float>> =
        watchedFractions(watch, state.map { s -> (s.videos + s.shorts + s.suggested).map { it.video.videoId } })
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyMap())

    val sync: StateFlow<HomeSync> =
        combine(feed.isSyncing, online, feed.observeSyncHealth()) { running, isOnline, health -> HomeSync(running, isOnline, health) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), HomeSync())

    fun refresh() = syncNow()

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L

        /** The «Shorts» row's length: the newest of the feed's Shorts. */
        const val SHORTS_LIMIT = 24
    }
}

/**
 * «مقترحة لك» for a home screen (the adult's and a child's): the suggestions kept beside the profile's last
 * [HomeSuggestions.SEEDS] watched videos, mixed ([HomeSuggestions.merge]), without what it already watched. A home never
 * waits for YouTube: the first list comes once the history is read (what is kept, often nothing yet). While it is
 * collected (the home on screen) and the profile shows suggestions, it asks [suggestions] for what is missing, beside
 * the row. The profile's rules are applied by the caller (ContentFilter.suggestions).
 */
internal fun suggestedVideos(
    watch: WatchRepository,
    filter: StateFlow<ContentFilter>,
    suggestions: HomeSuggestions?,
): Flow<List<VideoItem>> {
    if (suggestions == null) return flowOf(emptyList())
    val on = filter.map { it.showsSuggestions }.distinctUntilChanged()
    return channelFlow {
        // Read once for both (newest first): the row's seeds, and what is never suggested again.
        val watched = watch.observeHistory(WATCHED_KEPT_OUT).map { rows -> rows.map { it.videoId } }.stateIn(this)
        launch {
            combine(watched, on) { history, show -> history.take(HomeSuggestions.SEEDS).takeIf { show }.orEmpty() }
                .distinctUntilChanged()
                .collectLatest { seeds -> if (seeds.isNotEmpty()) suggestions.refresh(seeds) }
        }
        combine(watched, suggestions.kept, on) { history, kept, show ->
            if (!show) emptyList() else HomeSuggestions.merge(history.take(HomeSuggestions.SEEDS), kept, exclude = history.toSet())
        }.collect { send(it) }
    }
}

/** How much of the history is never suggested again (newest watched first). */
private const val WATCHED_KEPT_OUT = 200
