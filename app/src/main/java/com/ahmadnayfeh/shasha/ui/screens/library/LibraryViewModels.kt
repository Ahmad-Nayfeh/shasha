package com.ahmadnayfeh.shasha.ui.screens.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ahmadnayfeh.shasha.data.SubscriptionRepository
import com.ahmadnayfeh.shasha.data.WatchRepository
import com.ahmadnayfeh.shasha.ui.model.ChannelUi
import com.ahmadnayfeh.shasha.ui.model.VideoUi
import com.ahmadnayfeh.shasha.ui.model.toUi
import com.ahmadnayfeh.shasha.ui.screens.watchedFractions
import com.ahmadnayfeh.shasha.youtube.ChannelItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A saved list of videos (watch later, history): null until the database answered. */
abstract class VideoListViewModel(watch: WatchRepository, source: Flow<List<VideoUi>>) : ViewModel() {
    val items: StateFlow<List<VideoUi>?> = source
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val watched: StateFlow<Map<String, Float>> =
        watchedFractions(watch, items.filterNotNull().map { list -> list.map { it.video.videoId } })
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())
}

/** شاهد لاحقاً: most recently added first. */
class WatchLaterViewModel(watch: WatchRepository) :
    VideoListViewModel(watch, watch.observeWatchLater().map { rows -> rows.map { it.toUi() } })

/** السجل: most recently watched first; «مسح السجل» empties it. */
class HistoryViewModel(private val watch: WatchRepository) :
    VideoListViewModel(watch, watch.observeHistory().map { rows -> rows.map { it.toUi() } }) {
    fun clear() {
        viewModelScope.launch { watch.clearHistory() }
    }
}

/** الاشتراكات: the subscribed channels, by name. */
class SubscriptionsViewModel(subscriptions: SubscriptionRepository) : ViewModel() {
    val channels: StateFlow<List<ChannelUi>?> = subscriptions.observe()
        .map { rows -> rows.map { ChannelUi(ChannelItem(it.channelId, it.name, it.avatarUrl, subscriberCount = null)) } }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}
