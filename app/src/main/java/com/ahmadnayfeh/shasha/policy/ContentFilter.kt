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
 * Whether this video is a Short under the parent's limit («حدّ الفيديو القصير»): the «Shorts» badge on its tile. The
 * engine's own rule ([PolicyEngine.isShort]), so what is badged where shorts show is what is hidden where they do not.
 */
fun VideoItem.isShort(shortsMaxSeconds: Long): Boolean = PolicyEngine.isShort(toCandidate(), shortsMaxSeconds)

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

    /** «إظهار مقاطع Shorts»: the profile shows shorts (false before a profile is loaded). */
    val showsShorts: Boolean get() = profile?.hideShorts == false

    /** «إظهار الفيديوهات المقترحة»: the profile shows suggestions at all (false before a profile is loaded). */
    val showsSuggestions: Boolean get() = features?.suggestions == true

    /** A Short under the parent's limit (the engine's rule: the same that hides it where shorts are hidden). */
    fun isShort(video: VideoItem): Boolean = engine?.isShort(video.toCandidate()) == true

    /**
     * What a suggestion row may show of [videos] (VideoSource.related, in their order): nothing where suggestions are
     * off; otherwise only what the engine allows as a suggestion ([PolicyEngine.canSuggest]: blocks, shorts, and for
     * a child its allowlisted channels only), without repeats and without [exclude] (the videos the row is beside).
     */
    fun suggestions(videos: List<VideoItem>, exclude: Set<String> = emptySet()): List<VideoItem> {
        val e = engine ?: return emptyList()
        val p = profile!!
        if (!p.showSuggestions) return emptyList()
        return videos.asSequence()
            .filter { it.videoId !in exclude && e.canSuggest(p, it.toCandidate()) }
            .distinctBy { it.videoId }
            .toList()
    }

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
     *
     * Decided, and kept: such a video is **never** shown. The parent's limit counts a regular upload of three minutes or
     * less as a short, kids' channels are full of them, and the player looks a video up before it plays and refuses
     * what it finds short, so a card for an unknown duration would be one the player then refuses. That is also why
     * RSS (no durations) is not a way to fill a child's home when the Videos tab and the long-form playlist fail
     * ([com.ahmadnayfeh.shasha.data.ChannelUploads]): its rows would all be left out here. Where the profile shows Shorts
     * nothing needs a duration (this is false) and RSS is the last way.
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
