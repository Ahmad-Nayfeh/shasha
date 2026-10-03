package com.ahmadnayfeh.shasha.mobile.ui.watch

import android.util.Log
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
import com.ahmadnayfeh.shasha.mobile.data.LocalPlaylistIds
import com.ahmadnayfeh.shasha.mobile.data.SubscriptionRepository
import com.ahmadnayfeh.shasha.mobile.data.logLine
import com.ahmadnayfeh.shasha.mobile.download.DownloadManager
import com.ahmadnayfeh.shasha.mobile.player.PlayerController
import com.ahmadnayfeh.shasha.mobile.player.PlayerPhase
import com.ahmadnayfeh.shasha.mobile.player.PlayerUiState
import com.ahmadnayfeh.shasha.youtube.ChannelItem
import com.ahmadnayfeh.shasha.youtube.StreamDetails
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
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
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
 *
 * [playlistId] may name one of the viewer's own lists ([LocalPlaylistIds]): its queue is [localVideos], in the order the videos were
 * added, instead of a YouTube playlist's pages. [showShorts] is the «Show Shorts» setting: off, the related videos have no Shorts.
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
    /** The player's state that the screen follows: the controller's own (a test passes one of its own). */
    playerState: StateFlow<PlayerUiState> = controller.state,
    private val showShorts: () -> Boolean = { true },
    /** The videos of one of the viewer's own lists, by its id, in order. */
    private val localVideos: suspend (Long) -> List<VideoItem> = { emptyList() },
) : ViewModel(), WatchDetailsSource {

    val player: StateFlow<PlayerUiState> = playerState

    private val _related = MutableStateFlow<Loadable<List<VideoItem>>>(Loadable.Loading)

    override val related: StateFlow<Loadable<List<VideoItem>>> = _related

    /** The playlist being played, when there is one: shown instead of the related videos. */
    private val queue = PagedLoader<VideoItem, String>(viewModelScope, key = { it.videoId }, minPerLoad = 50, maxRounds = 4) { page ->
        val local = LocalPlaylistIds.parse(playlistId)
        if (local != null) {
            // One of the viewer's own lists: all of it at once, in the order the videos were added.
            localVideos(local) to null
        } else {
            val answer = source.playlist(checkNotNull(playlistId), page)
            answer.videos to answer.next
        }
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
        // The video's own page, once the extraction has read it, says who uploaded it and shows the uploader's picture.
        viewModelScope.launch {
            player.map { it.details }.filterNotNull().distinctUntilChanged().collect { details -> onStreamDetails(details) }
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
                Loadable.Loaded(source.related(videoId).filter { showShorts() || !it.isShortForm })
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
                val page = source.channel(id).channel
                // The video's own page may have given the picture first: YouTube's page for the channel keeps it if it has none.
                _channel.update { known -> page.copy(avatarUrl = page.avatarUrl ?: known?.avatarUrl) }
                subscriptions.updateDetails(page)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "$id: the channel's page failed: ${e.logLine()}")
                channelRequested = false // asked again when the viewer taps subscribe
            }
        }
    }

    /**
     * What the video's own page says about its channel needs no request of its own, so it is there also where YouTube's page for
     * the channel cannot be read (a network that answers that with an error): the channel row shows the picture, and a
     * subscription to the channel that is stored without one gets it (and a changed name) now. Cheap and silent: one UPDATE
     * ([SubscriptionRepository.updateDetails]), a no-op for a channel that is not followed.
     */
    internal suspend fun onStreamDetails(details: StreamDetails) {
        // The player is the app's one: while a newer screen (this video's channel, then another video) has it, its state is not this video's.
        if (details.video.videoId != videoId) return
        val uploader = details.uploader() ?: return
        if (uploader.avatarUrl != null) {
            _channel.update { known ->
                when {
                    known == null -> uploader
                    known.avatarUrl == null -> known.copy(avatarUrl = uploader.avatarUrl)
                    else -> known
                }
            }
        }
        subscriptions.updateDetails(uploader)
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
            subscriptions.subscribe(channelToSubscribe(channelId, video))
            feed.refreshChannel(channelId)
        }
    }

    /**
     * The channel as the subscription stores it. With a picture known (the video's own page gave it, or YouTube's page for the
     * channel did) that is all there is to it: no request, so tapping «Subscribe» does not wait for a page that may fail. Without
     * one, YouTube's page for the channel is asked for; if that fails too, the subscription is saved with what the video says
     * and gets its picture the next time a page of the channel loads ([SubscriptionRepository.updateDetails]).
     */
    private suspend fun channelToSubscribe(channelId: String, video: VideoItem): ChannelItem {
        val known = _channel.value?.takeIf { it.channelId == channelId }
        if (known?.avatarUrl != null) return known
        return try {
            source.channel(channelId).channel.also { page -> _channel.update { page.copy(avatarUrl = page.avatarUrl ?: it?.avatarUrl) } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "$channelId: the channel's page failed: ${e.logLine()}")
            known ?: ChannelItem(channelId = channelId, name = video.channelName.orEmpty(), avatarUrl = null, subscriberCount = null)
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
        private const val TAG = "WatchViewModel"

        /** The player is idle (stopped by a screen that went away) or holds a different video than [videoId]. */
        internal fun playerNeedsReclaiming(current: PlayerUiState, videoId: String): Boolean =
            current.phase == PlayerPhase.IDLE || current.video?.videoId != videoId

        /** The channel that uploaded the video, as its own page names it: the id, the name and the picture (no subscriber count). */
        internal fun StreamDetails.uploader(): ChannelItem? =
            video.channelId?.let { id -> ChannelItem(channelId = id, name = video.channelName.orEmpty(), avatarUrl = uploaderAvatarUrl, subscriberCount = null) }

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
                    showShorts = { container.latestSettings().showShorts },
                    localVideos = container.userPlaylists::videosNow,
                )
            }
        }
    }
}
