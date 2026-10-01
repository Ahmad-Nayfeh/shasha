package com.ahmadnayfeh.shasha.policy

import com.ahmadnayfeh.shasha.youtube.ChannelItem
import com.ahmadnayfeh.shasha.youtube.PlaylistItem
import com.ahmadnayfeh.shasha.youtube.SourceItem
import com.ahmadnayfeh.shasha.youtube.VideoItem

/**
 * A video as the engine sees it. Give [viaPlaylistId] ONLY for videos taken from that playlist's own
 * pages (the engine then admits them in a child profile whatever their channel); never from a link.
 */
fun VideoItem.toCandidate(viaPlaylistId: String? = null) = Candidate(
    videoId = videoId,
    title = title,
    channelId = channelId,
    durationSeconds = durationSeconds,
    url = url,
    isShortFormFlag = isShortForm,
    viaPlaylistId = viaPlaylistId,
    isLive = isLive,
)

/**
 * The engine's list rules (SPEC §3: nothing is shown before the engine allowed it) for one profile, in
 * the shapes the screens have: search results, a channel's videos, a playlist's videos, saved lists.
 *
 * A snapshot of one profile: [PolicyGate.filter] hands out a new one whenever the current profile
 * changes or is edited, so a screen that combines its items with it follows at once. [NOTHING] (no
 * profile loaded yet) shows nothing.
 */
class ContentFilter private constructor(private val engine: PolicyEngine?, val profile: Profile?) {

    /** Which parts of the UI exist; null until the profile is loaded (show none of the optional parts). */
    val features: Features? = profile?.let { engine?.features(it) }

    /**
     * Home, history, watch later, a channel's videos: anything that is not a playlist's own page.
     * [viaPlaylistId]: see [toCandidate].
     */
    fun allows(video: VideoItem, viaPlaylistId: String? = null): Boolean {
        val e = engine ?: return false
        return e.canShow(profile!!, video.toCandidate(viaPlaylistId)).isAllowed
    }

    fun videos(videos: List<VideoItem>): List<VideoItem> = videos.filter { allows(it) }

    /**
     * A text the screen would show as it is (a search suggestion, a recent search, words heard by the microphone):
     * none of the profile's blocked title words in it, matched as titles are. Nothing before a profile is loaded.
     */
    fun allowsText(text: String): Boolean {
        val p = profile ?: return false
        return TextNormalizer.firstMatch(text, p.blocklist.titleKeywords) == null
    }

    /**
     * The profile hides shorts and [video]'s duration is unknown (RSS): the shorts rule cannot judge it
     * yet ([PolicyEngine.needsDuration]). Lists that can learn durations leave such videos out until then.
     */
    fun needsDuration(video: VideoItem): Boolean {
        val e = engine ?: return false
        return e.needsDuration(profile!!, video.toCandidate())
    }

    /**
     * The videos of [playlistId]'s own pages (its screen, the queue the player continues with): a
     * child sees all of them when the playlist itself is allowlisted, whatever their channel.
     */
    fun playlistVideos(playlistId: String, videos: List<VideoItem>): List<VideoItem> =
        videos.filter { allows(it, viaPlaylistId = playlistId) }

    /**
     * Items of [channelId]'s own videos page. They are that channel's uploads, so an item without a
     * channel id counts as the channel's; none shows when the channel itself may not be opened.
     */
    fun channelVideos(channelId: String, items: List<SourceItem>): List<VideoItem> {
        if (!channel(channelId).isAllowed) return emptyList()
        return items.filterIsInstance<VideoItem>()
            .map { if (it.channelId == null) it.copy(channelId = channelId) else it }
            .filter { allows(it) }
    }

    /** A mixed list (search results): videos, channels and playlists, each by its own rule. */
    fun items(items: List<SourceItem>): List<SourceItem> = items.filter { item ->
        when (item) {
            is VideoItem -> allows(item)
            is ChannelItem -> channel(item.channelId).isAllowed
            // Search results do not say who owns a playlist: a child sees only allowlisted playlists.
            is PlaylistItem -> playlist(item.playlistId, ownerChannelId = null, title = item.title).isAllowed
        }
    }

    fun channel(channelId: String): Decision {
        val e = engine ?: return NOT_LOADED
        return e.canShowChannel(profile!!, channelId)
    }

    /** [title] whenever the playlist is shown in a list: blocked title keywords apply to it too. */
    fun playlist(playlistId: String, ownerChannelId: String?, title: String? = null): Decision {
        val e = engine ?: return NOT_LOADED
        return e.canShowPlaylist(profile!!, playlistId, ownerChannelId, title)
    }

    companion object {
        /** Before the current profile is known: nothing may be shown. */
        val NOTHING = ContentFilter(null, null)

        private val NOT_LOADED = Decision.Deny(DenyReason.NOT_IN_ALLOWLIST)

        fun of(engine: PolicyEngine, profile: Profile?): ContentFilter = if (profile == null) NOTHING else ContentFilter(engine, profile)
    }
}
