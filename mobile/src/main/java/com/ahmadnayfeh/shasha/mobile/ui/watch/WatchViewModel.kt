package com.ahmadnayfeh.shasha.mobile.ui.watch

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ahmadnayfeh.shasha.mobile.AppContainer
import com.ahmadnayfeh.shasha.mobile.common.Loadable
import com.ahmadnayfeh.shasha.mobile.common.PagedLoader
import com.ahmadnayfeh.shasha.mobile.common.PagedState
import com.ahmadnayfeh.shasha.mobile.common.UiError
import com.ahmadnayfeh.shasha.mobile.data.FeedRepository
import com.ahmadnayfeh.shasha.mobile.data.LibraryRepository
import com.ahmadnayfeh.shasha.mobile.data.SubscriptionRepository
import com.ahmadnayfeh.shasha.mobile.download.DownloadManager
import com.ahmadnayfeh.shasha.mobile.player.PlayerController
import com.ahmadnayfeh.shasha.mobile.player.PlayerPhase
import com.ahmadnayfeh.shasha.mobile.player.PlayerUiState
import com.ahmadnayfeh.shasha.youtube.ChannelItem
import com.ahmadnayfeh.shasha.youtube.VideoItem
import com.ahmadnayfeh.shasha.youtube.VideoSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the player screen is told to do once. */
sealed interface WatchEvent {
    /** The video ended and a playlist has a next one: the screen opens it (replacing this one). */
    data class PlayNext(val video: VideoItem) : WatchEvent
}

/** What the player screen reads for the part under the video: the real source is [WatchViewModel]. */
internal interface WatchDetailsSource {
    val videoId: String
    val playlistId: String?

    /** The videos YouTube suggests beside this one. */
    val related: StateFlow<Loadable<List<VideoItem>>>

    /** The playlist being played, when there is one: shown instead of the related videos. */
    val queueState: StateFlow<PagedState<VideoItem>>
    val subscribed: StateFlow<Boolean>
    val savedForLater: StateFlow<Boolean>

    /** The channel of the video, with its avatar, once known. */
    val channel: StateFlow<ChannelItem?>

    fun retryRelated()

    fun loadMoreQueue()

    fun toggleSubscribe()
}

/**
 * One player screen. The player itself is the app's ([PlayerController]); this holds what belongs to the screen: the
 * related videos (or the playlist being played), the channel and its subscribe button, «watch later», and the start of
 * playback. Leaving the screen stops the player, unless a newer screen already took it over.
 *
 * [downloadId] set: a downloaded file plays (no streams, no related videos).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WatchViewModel(
    private val controller: PlayerController,
    private val source: VideoSource,
    private val subscriptions: SubscriptionRepository,
    private val feed: FeedRepository,
    private val library: LibraryRepository,
    private val downloads: DownloadManager,
    override val videoId: String,
    override val playlistId: String?,
    val downloadId: Long?,
    startMs: Long?,
) : ViewModel(), WatchDetailsSource {

    val player: StateFlow<PlayerUiState> = controller.state

    private val _related = MutableStateFlow<Loadable<List<VideoItem>>>(Loadable.Loading)

    override val related: StateFlow<Loadable<List<VideoItem>>> = _related

    /** The playlist being played, when there is one: shown instead of the related videos. */
    private val queue = PagedLoader<VideoItem, String>(viewModelScope, key = { it.videoId }, minPerLoad = 50, maxRounds = 4) { page ->
        val answer = source.playlist(checkNotNull(playlistId), page)
        answer.videos to answer.next
    }
    override val queueState: StateFlow<PagedState<VideoItem>> = queue.state

    private val _channel = MutableStateFlow<ChannelItem?>(null)

    /** The channel of the video, with its avatar, once known (a subscription stores it). */
    override val channel: StateFlow<ChannelItem?> = _channel

    override val subscribed: StateFlow<Boolean> = player
        .map { it.video?.channelId }
        .distinctUntilChanged()
        .flatMapLatest { id -> if (id == null) flowOf(false) else subscriptions.isSubscribed(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    override val savedForLater: StateFlow<Boolean> = library.isInWatchLater(videoId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val events = Channel<WatchEvent>(Channel.BUFFERED)
    val eventFlow: Flow<WatchEvent> = events.receiveAsFlow()

    private var channelRequested = false
    private var relatedRequested = false

    init {
        startPlayback(startMs)
        if (playlistId != null) queue.loadMore()
        // The channel and the suggestions are looked up once the extraction has said whose video this is.
        viewModelScope.launch {
            player.map { it.video?.channelId }.filter { it != null }.distinctUntilChanged().collect { id -> loadChannel(id!!) }
        }
        // A playlist goes on by itself.
        viewModelScope.launch {
            player.map { it.phase }.distinctUntilChanged().filter { it == PlayerPhase.ENDED }.collect { playNext() }
        }
    }

    private fun startPlayback(startMs: Long?) {
        if (downloadId != null) {
            viewModelScope.launch { downloads.get(downloadId)?.let(controller::playOffline) }
        } else {
            controller.play(videoId, startMs = startMs)
            loadRelated()
        }
    }

    private var shownBefore = false

    /**
     * The screen is in front (again). If the player was let go meanwhile, or another video has it, this screen's video
     * plays again from where it stopped. That happens after a detour: video A, its channel, video B, back, back.
     */
    fun onShown() {
        if (!shownBefore) {
            shownBefore = true // the start in `init` covers the first time
            return
        }
        if (playerNeedsReclaiming(controller.state.value, videoId)) startPlayback(startMs = null)
    }

    private fun loadRelated() {
        if (relatedRequested) return
        relatedRequested = true
        viewModelScope.launch {
            _related.value = try {
                Loadable.Loaded(source.related(videoId))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Loadable.Failed(UiError.of(e))
            }
        }
    }

    override fun retryRelated() {
        relatedRequested = false
        _related.value = Loadable.Loading
        loadRelated()
    }

    private fun loadChannel(id: String) {
        if (channelRequested) return
        channelRequested = true
        viewModelScope.launch {
            try {
                _channel.value = source.channel(id).channel
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                channelRequested = false // asked again when the viewer taps subscribe
            }
        }
    }

    private suspend fun playNext() {
        if (playlistId == null) return
        val items = queue.state.value.items
        val index = items.indexOfFirst { it.videoId == videoId }
        val next = items.getOrNull(index + 1) ?: return
        events.send(WatchEvent.PlayNext(next))
    }

    override fun loadMoreQueue() = queue.loadMore()

    fun retry() = controller.retry()

    override fun toggleSubscribe() {
        val video = player.value.video ?: return
        val channelId = video.channelId ?: return
        viewModelScope.launch {
            if (subscribed.value) {
                subscriptions.unsubscribe(channelId)
                return@launch
            }
            val item = _channel.value ?: try {
                source.channel(channelId).channel.also { _channel.value = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ChannelItem(channelId = channelId, name = video.channelName.orEmpty(), avatarUrl = null, subscriberCount = null)
            }
            subscriptions.subscribe(item)
            feed.refreshChannel(channelId)
        }
    }

    fun toggleWatchLater() {
        val video = player.value.video ?: return
        viewModelScope.launch { library.toggleWatchLater(video) }
    }

    override fun onCleared() {
        // A newer player screen (the next video) may already own the player: only stop what is still ours.
        val current = controller.state.value
        if (current.video?.videoId == videoId || current.phase == PlayerPhase.IDLE) controller.stop()
    }

    companion object {
        /** The player is idle (stopped by a screen that went away) or holds a different video than [videoId]. */
        internal fun playerNeedsReclaiming(current: PlayerUiState, videoId: String): Boolean =
            current.phase == PlayerPhase.IDLE || current.video?.videoId != videoId

        fun factory(container: AppContainer, videoId: String, playlistId: String?, downloadId: Long?, startMs: Long?) = viewModelFactory {
            initializer {
                WatchViewModel(
                    controller = container.player,
                    source = container.videoSource,
                    subscriptions = container.subscriptions,
                    feed = container.feed,
                    library = container.library,
                    downloads = container.downloads,
                    videoId = videoId,
                    playlistId = playlistId,
                    downloadId = downloadId,
                    startMs = startMs?.takeIf { it > 0 },
                )
            }
        }
    }
}
