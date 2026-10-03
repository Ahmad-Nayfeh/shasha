package com.ahmadnayfeh.shasha.mobile.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ahmadnayfeh.shasha.mobile.AppContainer
import com.ahmadnayfeh.shasha.mobile.common.UiError
import com.ahmadnayfeh.shasha.mobile.data.FeedRepository
import com.ahmadnayfeh.shasha.mobile.data.SubscriptionRepository
import com.ahmadnayfeh.shasha.mobile.data.db.SubscriptionEntity
import com.ahmadnayfeh.shasha.youtube.SourceErrorKind
import com.ahmadnayfeh.shasha.youtube.VideoItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the home screen shows: the channels, the newest uploads of them, and whether a refresh is running or failed. */
data class HomeState(
    val subscriptions: List<SubscriptionEntity> = emptyList(),
    val videos: List<VideoItem> = emptyList(),
    /** The stored lists have been read (before that, nothing is known: no empty state yet). */
    val loaded: Boolean = false,
    val refreshing: Boolean = false,
    /** The last refresh reached no channel at all (offline). */
    val error: UiError? = null,
)

/**
 * The home feed. It opens at once with what the last refresh stored, and refreshes gently: only when that was a while
 * ago ([FeedRepository.STALE_AFTER_MS]); pulling down refreshes whenever asked, but not while one is running.
 */
class HomeViewModel(
    subscriptions: SubscriptionRepository,
    private val feed: FeedRepository,
) : ViewModel() {

    private val refreshing = MutableStateFlow(false)
    private val error = MutableStateFlow<UiError?>(null)

    val state: StateFlow<HomeState> = combine(subscriptions.subscriptions, feed.feed, refreshing, error) { subs, videos, isRefreshing, failure ->
        HomeState(subscriptions = subs, videos = videos, loaded = true, refreshing = isRefreshing, error = failure)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeState())

    init {
        refresh(force = false)
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
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { HomeViewModel(container.subscriptions, container.feed) }
        }
    }
}
