package com.ahmadnayfeh.shasha.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Wire format of the phone page. Field names are part of the contract with web/index.html:
// rename one here and the page must change with it.

@Serializable
enum class RemoteProfileKind { ADULT, CHILD }

@Serializable
enum class RemoteSessionState { OK, WARNING, TIME_UP, OUTSIDE_SCHEDULE }

@Serializable
data class RemoteStatus(
    val profileName: String,
    val profileKind: RemoteProfileKind,
    val canSearch: Boolean,
    val nowPlaying: RemoteNowPlaying?,
    /** Minutes left today for the current profile; null = no daily limit. */
    val timeLeftMinutes: Int?,
    val sessionState: RemoteSessionState,
    /** The current video is under the playback lock (SPEC §4) and can only be left with a PIN. */
    val videoLocked: Boolean,
)

@Serializable
data class RemoteNowPlaying(
    val videoId: String,
    val title: String,
    val channelName: String?,
    val positionSeconds: Long,
    /** null for live streams or while unknown. */
    val durationSeconds: Long?,
    val isPlaying: Boolean,
    val isLive: Boolean,
    val thumbnailUrl: String?,
)

@Serializable
enum class RemoteItemType {
    @SerialName("video") VIDEO,
    @SerialName("channel") CHANNEL,
    @SerialName("playlist") PLAYLIST,
}

/** A search result. The page plays it by POSTing the matching youtube.com URL to /api/play. */
@Serializable
data class RemoteItem(
    val type: RemoteItemType,
    /** videoId, channelId (UC…) or playlistId. */
    val id: String,
    val title: String,
    /** Channel name for videos/playlists, e.g. subscriber text for channels. */
    val subtitle: String?,
    val thumbnailUrl: String?,
    val durationSeconds: Long?,
    val isLive: Boolean = false,
)

/**
 * Answer to an action. [message] is Arabic and shown to the user as-is, success or not. Wrap a title or
 * name inside it in FSI…PDI (U+2068 … U+2069) so an English title keeps its order in the Arabic sentence.
 */
@Serializable
data class RemoteResult(val ok: Boolean, val message: String) {
    companion object {
        fun ok(message: String) = RemoteResult(true, message)
        fun error(message: String) = RemoteResult(false, message)
    }
}

@Serializable
data class RemoteProfile(
    val id: String,
    val name: String,
    val kind: RemoteProfileKind,
    /** Switching TO this profile needs a PIN (adult profiles, or leaving a child profile). */
    val needsPin: Boolean,
    /** Avatar key, e.g. "person", "star", "rocket". */
    val avatar: String,
    /** The profile the TV is using right now. */
    val isCurrent: Boolean = false,
    /** Daily quota shown in the parent "time" tab; null = unlimited. */
    val dailyQuotaMinutes: Int? = null,
)

@Serializable
enum class PlaybackAction { PLAY, PAUSE, TOGGLE, SEEK_BY, STOP }

/** [seconds] is used by SEEK_BY only (negative = rewind). */
@Serializable
data class PlaybackCommand(val action: PlaybackAction, val seconds: Int = 0)

@Serializable
data class RemoteListEntry(val id: String, val title: String)

@Serializable
data class RemoteAllowlist(
    val channels: List<RemoteListEntry>,
    val playlists: List<RemoteListEntry>,
)

@Serializable
data class RemoteChannel(val channelId: String, val name: String, val avatarUrl: String?)

/** Result of [RemoteController.parentLogin]. The server turns [Success] into a parent session. */
sealed interface ParentLoginResult {
    data object Success : ParentLoginResult

    /** HTTP 401. */
    data class WrongPin(val attemptsLeft: Int) : ParentLoginResult

    /** HTTP 429 with Retry-After. Too many wrong PINs (PinAttemptLimiter). */
    data class LockedOut(val retryAfterSeconds: Long) : ParentLoginResult
}
