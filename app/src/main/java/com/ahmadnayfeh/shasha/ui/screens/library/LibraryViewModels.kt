package com.ahmadnayfeh.shasha.ui.screens.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ahmadnayfeh.shasha.data.SubscriptionRepository
import com.ahmadnayfeh.shasha.data.WatchRepository
import com.ahmadnayfeh.shasha.policy.ContentFilter
import com.ahmadnayfeh.shasha.ui.model.ChannelUi
import com.ahmadnayfeh.shasha.ui.model.VideoUi
import com.ahmadnayfeh.shasha.ui.model.toUi
import com.ahmadnayfeh.shasha.ui.screens.watchedFractions
import com.ahmadnayfeh.shasha.youtube.ChannelItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * A saved list of videos (watch later, history): null until the database answered and the profile is known.
 * What the profile's rules hide now (a channel blocked since) is not shown.
 */
abstract class VideoListViewModel(watch: WatchRepository, source: Flow<List<VideoUi>>, filter: StateFlow<ContentFilter>) : ViewModel() {
    val items: StateFlow<List<VideoUi>?> = combine(source, filter) { list, rules ->
        if (rules.profile == null) return@combine null
        val shown = rules.videos(list.map { it.video }).mapTo(HashSet()) { it.videoId }
        list.filter { it.video.videoId in shown }
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val watched: StateFlow<Map<String, Float>> =
        watchedFractions(watch, items.filterNotNull().map { list -> list.map { it.video.videoId } })
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())
}

/** شاهد لاحقاً: most recently added first. */
class WatchLaterViewModel(watch: WatchRepository, filter: StateFlow<ContentFilter>) :
    VideoListViewModel(watch, watch.observeWatchLater().map { rows -> rows.map { it.toUi() } }, filter)

/** السجل: most recently watched first; «مسح السجل» empties it. */
class HistoryViewModel(private val watch: WatchRepository, filter: StateFlow<ContentFilter>) :
    VideoListViewModel(watch, watch.observeHistory().map { rows -> rows.map { it.toUi() } }, filter) {
    fun clear() {
        viewModelScope.launch { watch.clearHistory() }
    }
}

/**
 * الاشتراكات: the subscribed channels, by name; null until the profile is known. A channel the profile's
 * rules hide (blocked for the household since) is not listed: it is unsubscribed from قائمة الحظر.
 */
class SubscriptionsViewModel(subscriptions: SubscriptionRepository, filter: StateFlow<ContentFilter>) : ViewModel() {
    val channels: StateFlow<List<ChannelUi>?> = combine(subscriptions.observe(), filter) { rows, rules ->
        if (rules.profile == null) return@combine null
        rows.filter { rules.channel(it.channelId).isAllowed }
            .map { ChannelUi(ChannelItem(it.channelId, it.name, it.avatarUrl, subscriberCount = null)) }
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}
