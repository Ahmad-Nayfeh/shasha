package com.ahmadnayfeh.shasha.ui.screens.kids

import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ahmadnayfeh.shasha.data.ChannelUploads
import com.ahmadnayfeh.shasha.data.FeedRepository
import com.ahmadnayfeh.shasha.data.HomeSuggestions
import com.ahmadnayfeh.shasha.data.SubscriptionRepository
import com.ahmadnayfeh.shasha.data.WatchRepository
import com.ahmadnayfeh.shasha.data.asSourceException
import com.ahmadnayfeh.shasha.policy.Allowlist
import com.ahmadnayfeh.shasha.policy.ContentFilter
import com.ahmadnayfeh.shasha.ui.model.ChannelUi
import com.ahmadnayfeh.shasha.ui.model.PlaylistUi
import com.ahmadnayfeh.shasha.ui.model.VideoUi
import com.ahmadnayfeh.shasha.ui.model.toUi
import com.ahmadnayfeh.shasha.ui.screens.home.suggestedVideos
import com.ahmadnayfeh.shasha.ui.screens.watchedFractions
import com.ahmadnayfeh.shasha.youtube.ChannelItem
import com.ahmadnayfeh.shasha.youtube.PlaylistItem
import com.ahmadnayfeh.shasha.youtube.SourceException
import com.ahmadnayfeh.shasha.youtube.VideoItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class KidsHomeState(
    /** The profile (and its allowlist) is known. */
    val loaded: Boolean = false,
    val name: String = "",
    val avatar: String = "",
    /** Newest first: the allowed channels' uploads, through the engine's rules (shorts, blocks). */
    val videos: List<VideoUi> = emptyList(),
    /** The allowlisted playlists, opened from here. */
    val playlists: List<PlaylistUi> = emptyList(),
    val channelCount: Int = 0,
    /** Uploads are being fetched and none is known yet. */
    val fetching: Boolean = false,
    /** Every allowed channel failed and nothing is known: the unified error (SPEC §1, never silent). */
    val failure: SourceException? = null,
    /**
     * How many allowed channels could not be fetched in the last run, although [ChannelUploads] tried its ways. Fewer than
     * all of them: the home shows the others' videos and no error, because one channel failing must never blank it. Each failure
     * is in the log (`adb logcat -s KidsHome ChannelUploads`).
     */
    val failedChannels: Int = 0,
    /**
     * «مقترحة لك», only where the parent turned suggestions on for this child: what is suggested beside its last
     * videos, from its allowed channels only (PolicyEngine.canSuggest).
     */
    val suggested: List<VideoUi> = emptyList(),
) {
    val allowlistEmpty: Boolean get() = loaded && channelCount == 0 && playlists.isEmpty()
}

/**
 * الرئيسية of a child profile (SPEC §3): the newest videos of the allowlisted channels, newest first, and
 * the allowlisted playlists. Everything shown passes the profile's [ContentFilter].
 *
 * Uploads come from [KidsFeed], and from the local feed when the adult also subscribes to the channel. A
 * profile that hides shorts (the default for children) needs every video's duration: the feed's RSS rows
 * have none, and a regular upload of 3 minutes or less is a short there, which the player would refuse
 * after the card was shown. So for it [KidsFeed] reads each channel's Videos tab (durations known, shorts
 * not listed), the feed's rows borrow those durations, and a video whose duration is still unknown is
 * left out ([ContentFilter.needsDuration]): never a card that the player then refuses.
 *
 * **A failing way does not fail the home.** A channel's Videos tab that answers with an error is followed by the same list
 * as a playlist, with the same durations (see [ChannelUploads]); only a channel for which no way answered counts as failed,
 * is logged with `Log.w` (channel id and message) and leaves the others' videos on the home ([KidsHomeState.failedChannels]).
 * The unified error ([KidsHomeState.failure]) shows only when every channel failed and nothing is known.
 */
class KidsHomeViewModel(
    private val filter: StateFlow<ContentFilter>,
    feed: FeedRepository,
    private val kidsFeed: KidsFeed,
    watch: WatchRepository,
    suggestions: HomeSuggestions? = null,
) : ViewModel() {
    private val local = feed.observeHome()

    /** Channel id → uploads fetched by this screen (the local feed covers subscribed channels). */
    private val fetched = MutableStateFlow<Map<String, List<VideoItem>>>(emptyMap())
    private val playlistDetails = MutableStateFlow<Map<String, PlaylistItem>>(emptyMap())
    private val progress = MutableStateFlow(Progress())
    private var refreshing: Job? = null

    private data class Progress(val running: Boolean = false, val failed: Int = 0, val attempted: Int = 0, val lastError: SourceException? = null)

    /** What the home fetches for: the allowlist, and whether it needs the videos' durations (shorts hidden). */
    private data class Wanted(val allowlist: Allowlist, val withDurations: Boolean)

    private fun ContentFilter.wanted(): Wanted? = profile?.let { Wanted(it.allowlist, withDurations = it.hideShorts) }

    private val base = combine(filter, local, fetched, playlistDetails, progress) { f, local, remote, details, p ->
        val profile = f.profile ?: return@combine KidsHomeState()
        val allowlist = profile.allowlist
        val fetchedVideos = remote.values.flatten()
        val durations = fetchedVideos.mapNotNull { v -> v.durationSeconds?.let { v.videoId to it } }.toMap()
        val fromFeed = local.asSequence().filter { allowlist.hasChannel(it.channelId) }.map { row ->
            val video = row.toVideoItem()
            if (video.durationSeconds == null) durations[video.videoId]?.let { video.copy(durationSeconds = it) } ?: video else video
        }
        val videos = (fromFeed + fetchedVideos.asSequence())
            .distinctBy { it.videoId }
            // Never a card the shorts rule would refuse once the video's duration is known.
            .filterNot(f::needsDuration)
            .toList()
            .let(f::videos)
            .sortedByDescending { it.publishedAtEpochMs ?: Long.MIN_VALUE }
            .take(MAX_VIDEOS)
        KidsHomeState(
            loaded = true,
            name = profile.name,
            avatar = profile.avatar,
            videos = videos.map { it.toUi() },
            playlists = allowlist.playlists
                .filter { f.playlist(it.playlistId, ownerChannelId = null, title = it.title).isAllowed }
                .map { PlaylistUi(details[it.playlistId] ?: PlaylistItem(it.playlistId, it.title, null, null, null)) },
            channelCount = allowlist.channels.size,
            fetching = videos.isEmpty() && p.running,
            failure = p.lastError.takeIf { videos.isEmpty() && !p.running && p.attempted > 0 && p.failed == p.attempted },
            failedChannels = p.failed,
        )
    }

    /** «مقترحة لك» before the child's rules (none unless the parent turned suggestions on: the flow stays empty then). */
    private val suggestedVideos = suggestedVideos(watch, filter, suggestions)

    val state: StateFlow<KidsHomeState> = combine(base, suggestedVideos, filter) { s, suggested, rules ->
        if (!s.loaded) s else s.copy(suggested = rules.suggestions(suggested).take(HomeSuggestions.ROW_LIMIT).map { it.toUi() })
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), KidsHomeState())

    val watched: StateFlow<Map<String, Float>> =
        watchedFractions(watch, state.map { s -> (s.videos + s.suggested).map { it.video.videoId } })
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    init {
        // A channel or playlist the parent adds (on the TV or from the phone) is fetched right away.
        viewModelScope.launch {
            filter.map { it.wanted() }.distinctUntilChanged().collect { if (it != null) refresh(it, restart = true) }
        }
    }

    /** Fetches what is missing or older than half an hour (the screen asks each time it is shown). */
    fun refresh() {
        filter.value.wanted()?.let { refresh(it, restart = false) }
    }

    /** «إعادة المحاولة» after every channel failed. */
    fun retry() = refresh()

    /** [restart]: the allowlist (or the shorts setting) changed, so a run for the old one is replaced. */
    private fun refresh(wanted: Wanted, restart: Boolean) {
        if (refreshing?.isActive == true) {
            if (!restart) return
            refreshing?.cancel()
        }
        val allowlist = wanted.allowlist
        val withDurations = wanted.withDurations
        refreshing = viewModelScope.launch {
            // Cached answers show at once; only stale channels are asked again.
            fetched.value = allowlist.channels.mapNotNull { c -> kidsFeed.cachedUploads(c.channelId, withDurations)?.let { c.channelId to it } }.toMap()
            playlistDetails.value = allowlist.playlists.mapNotNull { p -> kidsFeed.cachedPlaylist(p.playlistId)?.let { p.playlistId to it } }.toMap()
            // A channel the adult also follows is already in the local feed: YouTube is not asked twice. Unless
            // the durations are needed: the feed's rows (RSS) have none.
            val following = if (withDurations) {
                emptySet()
            } else {
                try {
                    local.first().mapTo(HashSet()) { it.channelId }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    emptySet()
                }
            }
            val due = allowlist.channels.filter { it.channelId !in following && kidsFeed.isStale(it.channelId, withDurations) }
            progress.value = Progress(running = due.isNotEmpty() || allowlist.playlists.isNotEmpty(), attempted = due.size)
            coroutineScope {
                due.forEach { channel ->
                    launch {
                        try {
                            kidsFeed.uploads(channel.channelId, withDurations)?.let { videos -> fetched.update { it + (channel.channelId to videos) } }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // Whatever it is, it is this channel's failure: it must not cancel the others' (one scope) nor crash the app.
                            val error = e.asSourceException()
                            Log.w(TAG, "No uploads for ${channel.channelId} (${channel.name}): ${error.technicalName}: ${error.message}")
                            progress.update { it.copy(failed = it.failed + 1, lastError = error) }
                        }
                    }
                }
                allowlist.playlists.forEach { playlist ->
                    launch {
                        try {
                            kidsFeed.playlist(playlist.playlistId)?.let { item -> playlistDetails.update { it + (playlist.playlistId to item) } }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: SourceException) {
                            // The card keeps the name the parent saw when allowing it.
                        }
                    }
                }
            }
            progress.update { it.copy(running = false) }
        }
    }

    private companion object {
        const val MAX_VIDEOS = 120
        const val TAG = "KidsHome"
    }
}

@Immutable
data class AllowedState(
    val loaded: Boolean = false,
    val channels: List<ChannelUi> = emptyList(),
    val playlists: List<PlaylistUi> = emptyList(),
)

/** القنوات المسموحة: the allowlisted channels (with their pictures) and playlists of the child profile. */
class AllowedChannelsViewModel(
    private val filter: StateFlow<ContentFilter>,
    subscriptions: SubscriptionRepository,
    private val kidsFeed: KidsFeed,
) : ViewModel() {
    private val details = MutableStateFlow<Map<String, ChannelItem>>(emptyMap())
    private val playlistDetails = MutableStateFlow<Map<String, PlaylistItem>>(emptyMap())

    private val avatars: Flow<Map<String, String?>> = subscriptions.observe().map { rows -> rows.associate { it.channelId to it.avatarUrl } }

    val state: StateFlow<AllowedState> = combine(filter, avatars, details, playlistDetails) { f, known, fetched, playlists ->
        val profile = f.profile ?: return@combine AllowedState()
        AllowedState(
            loaded = true,
            channels = profile.allowlist.channels
                .filter { f.channel(it.channelId).isAllowed }
                .map { listed ->
                    val item = fetched[listed.channelId]
                    ChannelUi(ChannelItem(listed.channelId, listed.name, item?.avatarUrl ?: known[listed.channelId], item?.subscriberCount))
                },
            playlists = profile.allowlist.playlists
                .filter { f.playlist(it.playlistId, ownerChannelId = null, title = it.title).isAllowed }
                .map { PlaylistUi(playlists[it.playlistId] ?: PlaylistItem(it.playlistId, it.title, null, null, null)) },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AllowedState())

    init {
        viewModelScope.launch {
            filter.map { it.profile?.allowlist }.distinctUntilChanged().collect { allowlist ->
                if (allowlist != null) load(allowlist)
            }
        }
    }

    private suspend fun load(allowlist: Allowlist) = coroutineScope {
        allowlist.channels.forEach { c ->
            kidsFeed.cachedChannel(c.channelId)?.let { item -> details.update { it + (c.channelId to item) } }
                ?: launch { quietly("channel ${c.channelId}") { kidsFeed.channel(c.channelId) }?.let { item -> details.update { it + (c.channelId to item) } } }
        }
        allowlist.playlists.forEach { p ->
            launch { quietly("playlist ${p.playlistId}") { kidsFeed.playlist(p.playlistId) }?.let { item -> playlistDetails.update { it + (p.playlistId to item) } } }
        }
    }

    /**
     * A missing picture is not an error: the card shows the name's first letter (the names and the list itself come from the
     * profile, so no failure here can blank the page). The failure is only logged, for `adb logcat`.
     */
    private suspend fun <T> quietly(what: String, block: suspend () -> T?): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: SourceException) {
        Log.w(TAG, "No details for $what: ${e.technicalName}: ${e.message}")
        null
    }

    private companion object {
        const val TAG = "AllowedChannels"
    }
}
