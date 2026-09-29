package com.ahmadnayfeh.shasha.ui.screens.home

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ahmadnayfeh.shasha.data.FeedRepository
import com.ahmadnayfeh.shasha.data.SubscriptionRepository
import com.ahmadnayfeh.shasha.data.SyncHealth
import com.ahmadnayfeh.shasha.data.WatchRepository
import com.ahmadnayfeh.shasha.ui.model.VideoUi
import com.ahmadnayfeh.shasha.ui.model.toUi
import com.ahmadnayfeh.shasha.ui.screens.watchedFractions
import com.ahmadnayfeh.shasha.youtube.SourceErrorKind
import com.ahmadnayfeh.shasha.youtube.SourceException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@Immutable
data class HomeState(
    val loaded: Boolean = false,
    /** Newest first (SPEC §2): the subscribed channels' uploads, nothing else. */
    val videos: List<VideoUi> = emptyList(),
    val subscriptionCount: Int = 0,
)

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
 */
class HomeViewModel(
    feed: FeedRepository,
    subscriptions: SubscriptionRepository,
    watch: WatchRepository,
    online: Flow<Boolean>,
    private val syncNow: () -> Unit,
) : ViewModel() {

    val state: StateFlow<HomeState> =
        combine(feed.observeHome(), subscriptions.observe()) { items, channels ->
            HomeState(loaded = true, videos = items.map { it.toUi() }, subscriptionCount = channels.size)
        }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), HomeState())

    /** Watched fractions of the videos on screen, for the bars under their thumbnails. */
    val watched: StateFlow<Map<String, Float>> =
        watchedFractions(watch, state.map { s -> s.videos.map { it.video.videoId } })
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyMap())

    val sync: StateFlow<HomeSync> =
        combine(feed.isSyncing, online, feed.observeSyncHealth()) { running, isOnline, health -> HomeSync(running, isOnline, health) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), HomeSync())

    fun refresh() = syncNow()

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
