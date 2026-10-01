package com.ahmadnayfeh.shasha.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

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
    /** «الوضع الجاد» is on: videos that start are locked until they end (off: nothing is ever locked). */
    val seriousMode: Boolean = true,
    /**
     * Changes whenever something the parent section shows changes on the TV (a setting, a profile's rules, a list, the
     * subscriptions), whoever changed it: this phone, another phone or the TV itself. A page that sees a new value
     * reads again what it shows. Only equality means anything (it starts anywhere).
     */
    val rev: Long = 0,
    /** The server moves to this port in a moment (a new port was chosen): every open page follows it there. */
    val movingTo: Int? = null,
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

// ---- v1.1: the parent's settings, profiles, lists and stats on the phone ------------------------------------------

/** How the page edits a setting. */
@Serializable
enum class RemoteSettingKind {
    /** true / false. */
    SWITCH,

    /** One of [RemoteSetting.choices]. */
    CHOICE,

    /** A set of [RemoteSetting.choices] (a JSON array), [RemoteSetting.minCount]..[RemoteSetting.maxCount] of them. */
    MULTI,

    /** A whole number in [RemoteSetting.min]..[RemoteSetting.max]. */
    NUMBER,

    /** Free text up to [RemoteSetting.maxLength] characters. */
    TEXT,

    /** A list of texts (a JSON array), 1..[RemoteSetting.maxCount] of them, each up to [RemoteSetting.maxLength]. */
    TEXT_LIST,
}

/** [disabledReason]: this choice cannot be taken now, and why (the page shows it dimmed, with the reason). */
@Serializable
data class RemoteChoice(val value: JsonElement, val label: String, val disabledReason: String? = null)

/**
 * One setting as the TV has it now. [value] and [defaultValue] are JSON (a boolean, a number, a string or an array).
 * [visibleWhen]: shown only while each named setting of the page has that value (e.g. the escape's hold only while
 * «الوضع الجاد» and the escape are on). [warning]: shown while [value] differs from [defaultValue].
 */
@Serializable
data class RemoteSetting(
    val key: String,
    val label: String,
    val kind: RemoteSettingKind,
    val value: JsonElement,
    val defaultValue: JsonElement,
    val help: String? = null,
    val choices: List<RemoteChoice> = emptyList(),
    val min: Int? = null,
    val max: Int? = null,
    val minCount: Int? = null,
    val maxCount: Int? = null,
    val maxLength: Int? = null,
    val visibleWhen: Map<String, JsonElement> = emptyMap(),
    val warning: String? = null,
)

@Serializable
data class RemoteSettingGroup(val id: String, val title: String, val items: List<RemoteSetting>, val note: String? = null)

@Serializable
data class RemoteSettingsPage(val groups: List<RemoteSettingGroup>)

/**
 * Answer to a settings change or reset: [settings] is the whole page as the TV has it afterwards (the phone shows exactly
 * that, success or not). [reconnectPort]: the server moves to this port in a moment (the page follows it).
 */
@Serializable
data class RemoteSettingsResult(
    val ok: Boolean,
    val message: String,
    val settings: RemoteSettingsPage? = null,
    val reconnectPort: Int? = null,
)

/** A profile with everything the parent edits from the phone. Schedule minutes since midnight; null = all day. */
@Serializable
data class RemoteProfileDetails(
    val id: String,
    val name: String,
    val kind: RemoteProfileKind,
    val avatar: String,
    val isCurrent: Boolean,
    val dailyQuotaMinutes: Int?,
    val scheduleFrom: Int?,
    val scheduleUntil: Int?,
    val hideShorts: Boolean,
    val hideComments: Boolean,
    /** Minutes of today counted so far (the quota's use). */
    val usedTodayMinutes: Int = 0,
)

@Serializable
data class RemoteBlocklist(val channels: List<RemoteListEntry>, val keywords: List<String>)

@Serializable
data class RemoteChannelUsage(val name: String, val minutes: Int)

/** The last 7 days of one profile (oldest first, today last), like the TV's «الإحصائيات». */
@Serializable
data class RemoteStats(
    val profileId: String,
    val name: String,
    val kind: RemoteProfileKind,
    val days: List<Int>,
    val thisWeekMinutes: Int,
    val previousWeekMinutes: Int,
    val changePercent: Int?,
    val topChannels: List<RemoteChannelUsage>,
    val surrenders: Int,
)
