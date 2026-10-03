package com.ahmadnayfeh.shasha.mobile.ui.home

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ahmadnayfeh.shasha.mobile.AppContainer
import com.ahmadnayfeh.shasha.mobile.common.UiError
import com.ahmadnayfeh.shasha.mobile.data.FeedRepository
import com.ahmadnayfeh.shasha.mobile.data.HomeSuggestions
import com.ahmadnayfeh.shasha.mobile.data.SubscriptionRepository
import com.ahmadnayfeh.shasha.mobile.data.db.SubscriptionEntity
import com.ahmadnayfeh.shasha.mobile.data.logLine
import com.ahmadnayfeh.shasha.youtube.SourceErrorKind
import com.ahmadnayfeh.shasha.youtube.VideoItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * What the home screen shows: the channels, their newest uploads, «مقترحة لك» (what YouTube suggests beside what was watched,
 * newer viewings counting more), the Shorts row, and whether a refresh is running or failed.
 */
data class HomeState(
    val subscriptions: List<SubscriptionEntity> = emptyList(),
    /** The newest uploads of the channels, without their Shorts (those are in [shorts]). */
    val videos: List<VideoItem> = emptyList(),
    /** «مقترحة لك»: suggested videos, best first; none that the uploads below already show. */
    val suggestions: List<VideoItem> = emptyList(),
    /** The Shorts row: the channels' Shorts and the suggested ones, taken in turns. Empty when the viewer turned Shorts off. */
    val shorts: List<VideoItem> = emptyList(),
    /** The stored lists have been read (before that, nothing is known: no empty state yet). */
    val loaded: Boolean = false,
    val refreshing: Boolean = false,
    /** Nothing is suggested yet, but the watched videos' suggestions are being fetched: a Home without channels waits for them. */
    val suggesting: Boolean = false,
    /** The last refresh reached no channel at all (offline). */
    val error: UiError? = null,
) {
    /** Something beside the channels' uploads to show: the home is not empty even when nobody is followed. */
    val hasMore: Boolean get() = suggestions.isNotEmpty() || shorts.isNotEmpty()
}

/**
 * The home feed. It opens at once with what the last refresh stored, and refreshes gently: only when that was a while
 * ago ([FeedRepository.STALE_AFTER_MS]); pulling down refreshes whenever asked, but not while one is running.
 *
 * «مقترحة لك» and the suggested Shorts come from [HomeSuggestions], which fetches beside the screen and keeps what it found for
 * hours: they appear when they are known and never hold the feed back. [showShorts] off takes the Shorts away everywhere on the
 * home, the channels' own included.
 */
class HomeViewModel(
    subscriptions: SubscriptionRepository,
    private val feed: FeedRepository,
    private val suggestions: HomeSuggestions,
    showShorts: Flow<Boolean> = flowOf(true),
) : ViewModel() {

    private val refreshing = MutableStateFlow(false)
    private val error = MutableStateFlow<UiError?>(null)

    /** The channels' uploads, told apart into videos and Shorts (a Short only when Shorts are on). */
    private val uploads = combine(feed.feed, showShorts) { all, shortsOn ->
        Uploads(videos = all.filterNot { it.isShortForm }, shorts = if (shortsOn) all.filter { it.isShortForm } else emptyList())
    }

    val state: StateFlow<HomeState> = combine(
        subscriptions.subscriptions,
        uploads,
        suggestions.suggested(showShorts),
        refreshing,
        error,
    ) { subs, own, suggested, isRefreshing, failure ->
        val shown = own.videos.mapTo(HashSet()) { it.videoId }
        HomeState(
            subscriptions = subs,
            videos = own.videos,
            suggestions = suggested.videos.filterNot { it.videoId in shown },
            shorts = interleave(own.shorts, suggested.shorts, SHORTS_ROW_LIMIT),
            loaded = true,
            refreshing = isRefreshing,
            suggesting = suggested.pending,
            error = failure,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeState())

    init {
        refresh(force = false)
        // The suggestions' lists are looked up beside everything else, now and whenever the viewer watches something new.
        viewModelScope.launch { guarded("keeping the suggestions fresh") { suggestions.keepFresh() } }
    }

    /** [force]: the viewer asked; otherwise only when the feed is stale. */
    fun refresh(force: Boolean = true) {
        // The flag is claimed before the coroutine starts: two quick calls (the start's own refresh, then a pull-down)
        // must not both get through, and the screen shows the spinner from the first state on.
        if (!refreshing.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                val outcome = if (force) feed.refresh() else feed.refreshIfStale()
                error.value = if (outcome != null && outcome.allFailed) UiError(SourceErrorKind.NETWORK, "NoChannelReached") else null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error.value = UiError.of(e)
            } finally {
                refreshing.value = false
            }
        }
        // The pull-down also asks again for the suggestions that are missing (a failed lookup); it does not wait for them.
        if (force) viewModelScope.launch { guarded("refreshing the suggestions") { suggestions.refreshNow() } }
    }

    /** A failure of the suggestions is not the home's: it is logged, and the home keeps what it shows. */
    private suspend fun guarded(what: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "$what failed: ${e.logLine()}")
        }
    }

    private class Uploads(val videos: List<VideoItem>, val shorts: List<VideoItem>)

    companion object {
        private const val TAG = "HomeViewModel"

        /** At most this many Shorts in the row. */
        const val SHORTS_ROW_LIMIT = 20

        /**
         * [first] and [second] taken in turns (the first of each, then the second of each…), without a video twice, at most [limit]:
         * a row of the channels' Shorts and of the suggested ones where neither crowds the other out.
         */
        internal fun interleave(first: List<VideoItem>, second: List<VideoItem>, limit: Int): List<VideoItem> {
            val row = LinkedHashMap<String, VideoItem>()
            var i = 0
            while (row.size < limit && (i < first.size || i < second.size)) {
                first.getOrNull(i)?.let { row.putIfAbsent(it.videoId, it) }
                if (row.size < limit) second.getOrNull(i)?.let { row.putIfAbsent(it.videoId, it) }
                i++
            }
            return row.values.toList()
        }

        fun factory(container: AppContainer) = viewModelFactory {
            initializer { HomeViewModel(container.subscriptions, container.feed, container.suggestions, container.showShorts) }
        }
    }
}
