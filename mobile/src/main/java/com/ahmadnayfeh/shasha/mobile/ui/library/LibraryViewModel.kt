package com.ahmadnayfeh.shasha.mobile.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ahmadnayfeh.shasha.mobile.AppContainer
import com.ahmadnayfeh.shasha.mobile.data.LibraryRepository
import com.ahmadnayfeh.shasha.mobile.data.SubscriptionRepository
import com.ahmadnayfeh.shasha.mobile.data.db.SubscriptionEntity
import com.ahmadnayfeh.shasha.youtube.VideoItem
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** History, watch later and the subscriptions list: what the viewer keeps, with the actions on it. */
class LibraryViewModel(
    private val library: LibraryRepository,
    private val subscriptions: SubscriptionRepository,
) : ViewModel() {

    /** Null until the stored list has been read (before that, no "empty" message). */
    val history: StateFlow<List<VideoItem>?> = library.historyItems.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val watchLater: StateFlow<List<VideoItem>?> = library.watchLaterItems.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val channels: StateFlow<List<SubscriptionEntity>?> = subscriptions.subscriptions.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun removeFromHistory(videoId: String) {
        viewModelScope.launch { library.removeFromHistory(videoId) }
    }

    fun clearHistory() {
        viewModelScope.launch { library.clearHistory() }
    }

    fun removeFromWatchLater(videoId: String) {
        viewModelScope.launch { library.removeFromWatchLater(videoId) }
    }

    fun clearWatchLater() {
        viewModelScope.launch { library.clearWatchLater() }
    }

    fun unsubscribe(channelId: String) {
        viewModelScope.launch { subscriptions.unsubscribe(channelId) }
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { LibraryViewModel(container.library, container.subscriptions) }
        }
    }
}
