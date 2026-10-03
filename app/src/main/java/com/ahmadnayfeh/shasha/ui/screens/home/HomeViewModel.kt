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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
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
    /**
     * «Shorts» of the same ranking as [suggested], from the channels the viewer watched lately (empty where the profile hides
     * Shorts, and what the feed's own Shorts row already has).
     */
    val suggestedShorts: List<VideoUi> = emptyList(),
    /**
     * «شاهدتَها مؤخراً»: the newest of the history, for a home whose channels give it no videos of its own (no subscriptions,
     * or none synced yet): with a history the home is never empty, whatever YouTube answers.
     */
    val recent: List<VideoUi> = emptyList(),
    /** The suggestions are being fetched and none came yet (the header says so, instead of the rows just being absent). */
    val suggesting: Boolean = false,
) {
    /** Something to show in the feed's form from the subscriptions: videos, or Shorts only. */
    val hasFeed: Boolean get() = videos.isNotEmpty() || shorts.isNotEmpty()

    /** Something to show in the home's rows at all: the feed, the suggestions, or what was watched lately. */
    val hasRows: Boolean get() = hasFeed || suggested.isNotEmpty() || suggestedShorts.isNotEmpty() || recent.isNotEmpty()
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
 * Rows go with the feed: «Shorts» (the feed's own Shorts, where the profile shows them), «مقترحة لك» and «Shorts» again
 * ([suggestions]: the ranking of what is suggested beside the last videos watched, where the profile shows suggestions, and
 * Shorts) and, for a home with no videos from the channels, «شاهدتَها مؤخراً». The suggestions never wait for YouTube: they start
 * with what [HomeSuggestions] keeps (often nothing) and fill in from the background.
 *
 * **Why the home looked empty.** It was the subscriptions' feed and nothing else: the suggestions were shelves under a
 * non-empty feed, never a home of their own. A viewer with a history but no subscriptions (or channels not synced yet, or a sync
 * that failed) got the «اشتراكاتك تظهر هنا» page, and the few suggestions there were (the three last videos' lists, filled
 * in one by one) were computed and never shown. Now the history alone makes a home: its suggestions, its Shorts and what was watched
 * lately.
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

    /** What the rows are made of, before the profile's rules (none without [suggestions]). */
    private val suggestedVideos: Flow<HomeSuggestions.Suggested> = suggestedVideos(watch, filter, suggestions)

    /** The newest of the history (as the History page has it), for the row of what was watched lately. */
    private val recentlyWatched = watch.observeHistory(RECENT_LIMIT)

    val state: StateFlow<HomeState> =
        combine(feed.observeHome(), subscriptions.observe(), filter, suggestedVideos, recentlyWatched) { items, channels, rules, suggested, history ->
            val visible = rules.videos(items.map { it.toVideoItem() })
            val shorts = if (rules.showsShorts) visible.filter(rules::isShort) else emptyList()
            val videos = if (shorts.isEmpty()) visible else visible.filterNot(rules::isShort)
            // A suggestion the feed already shows is not shown twice.
            val inFeed = visible.mapTo(HashSet()) { it.videoId }
            val suggestedVideos = rules.suggestions(suggested.videos, exclude = inFeed).take(HomeSuggestions.ROW_LIMIT)
            val suggestedShorts = rules.suggestions(suggested.shorts, exclude = inFeed).take(HomeSuggestions.SHORTS_ROW_LIMIT)
            // Only where the channels give the home no videos: it is what keeps a home with a history from being empty.
            val recent = if (visible.isEmpty()) {
                val shown = rules.videos(history.map { it.toUi().video }).mapTo(HashSet()) { it.videoId }
                history.filter { it.videoId in shown }.take(RECENT_ROW_LIMIT).map { it.toUi() }
            } else {
                emptyList()
            }
            HomeState(
                loaded = rules.profile != null,
                videos = videos.map { it.toUi() },
                subscriptionCount = channels.size,
                shorts = shorts.take(SHORTS_LIMIT).map { it.toUi() },
                suggested = suggestedVideos.map { it.toUi() },
                suggestedShorts = suggestedShorts.map { it.toUi() },
                recent = recent,
                suggesting = suggested.working && suggestedVideos.isEmpty() && suggestedShorts.isEmpty(),
            )
        }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), HomeState())

    /** Watched fractions of the videos on screen (the feed and every row), for the red bars under their thumbnails. */
    val watched: StateFlow<Map<String, Float>> =
        watchedFractions(watch, state.map { s -> (s.videos + s.shorts + s.suggested + s.suggestedShorts + s.recent).map { it.video.videoId } })
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyMap())

    val sync: StateFlow<HomeSync> =
        combine(feed.isSyncing, online, feed.observeSyncHealth()) { running, isOnline, health -> HomeSync(running, isOnline, health) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), HomeSync())

    fun refresh() = syncNow()

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L

        /** The «Shorts» row's length: the newest of the feed's Shorts. */
        const val SHORTS_LIMIT = 24

        /** How much of the history is read for «شاهدتَها مؤخراً» (some of it the rules may hide), and how much of it the row shows. */
        const val RECENT_LIMIT = 40
        const val RECENT_ROW_LIMIT = 20
    }
}

/**
 * The suggestions for a home screen (the adult's and a child's): [HomeSuggestions.suggested] of the profile's history, the videos
 * and, when [withShorts] and the profile shows them, the Shorts. A home never waits for YouTube: the first list comes once the
 * history is read (what is kept, often nothing yet). While it is collected (the home on screen) and the profile shows
 * suggestions, it keeps the lists fresh ([HomeSuggestions.keepFresh]) beside the rows; where suggestions are off nothing is asked and
 * nothing shows. The profile's rules are applied by the caller (ContentFilter.suggestions).
 */
internal fun suggestedVideos(
    watch: WatchRepository,
    filter: StateFlow<ContentFilter>,
    suggestions: HomeSuggestions?,
    withShorts: Boolean = true,
): Flow<HomeSuggestions.Suggested> {
    if (suggestions == null) return flowOf(HomeSuggestions.Suggested())
    val on = filter.map { it.showsSuggestions }.distinctUntilChanged()
    val shorts = filter.map { withShorts && it.showsShorts }.distinctUntilChanged()
    // Newest first, read once for both: the seeds, and what is never suggested again.
    val history = combine(watch.observeHistory(WATCHED_KEPT_OUT), on) { rows, show ->
        if (show) rows.map { HomeSuggestions.Watched(it.videoId, it.watchedAtMs, it.channelId) } else emptyList()
    }
    return channelFlow {
        val watched = history.stateIn(this)
        launch { suggestions.keepFresh(watched, shorts) }
        suggestions.suggested(watched, shorts).collect { send(it) }
    }
}

/** How much of the history is never suggested again (newest watched first). */
private const val WATCHED_KEPT_OUT = 200
