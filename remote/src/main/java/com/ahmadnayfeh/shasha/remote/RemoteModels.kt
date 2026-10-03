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
    /**
     * «الوضع الجاد» is on for the current profile's kind (v1.1.1: the children's and the adult's switches are separate):
     * videos that start in it are locked until they end (off: nothing it starts is locked).
     */
    val seriousMode: Boolean = true,
    /**
     * Changes whenever something the parent section shows changes on the TV (a setting, a profile's rules, a list, the
     * subscriptions), whoever changed it: this phone, another phone or the TV itself. A page that sees a new value
     * reads again what it shows. Only equality means anything (it starts anywhere).
     */
    val rev: Long = 0,
    /** The server moves to this port in a moment (a new port was chosen): every open page follows it there. */
    val movingTo: Int? = null,
    /** The current profile's id: a switch between two profiles of the same name and kind is still a switch. */
    val profileId: String = "",
    /**
     * v1.1.1: a video waiting on the TV for the parent's «تأكيد» (it would start in Serious Mode while only the phone could
     * end it early); null when none. Answered with `POST /api/parent/confirm`.
     */
    val confirmation: RemoteConfirmation? = null,
    /** v1.1.1: the latest link a phone sent and how the TV answered it (PENDING while «قبول» / «رفض» shows there). */
    val playRequest: RemotePlayRequest? = null,
)

/** A video the TV will only start once the parent confirms it from the phone (v1.1.1). */
@Serializable
data class RemoteConfirmation(
    val id: String,
    val title: String,
    /** Seconds before the TV gives up waiting (the video is then not played). */
    val secondsLeft: Int,
)

/** Where a link sent from the phone stands on the TV (v1.1.1: nothing plays before «قبول» on the TV). */
@Serializable
enum class RemotePlayState {
    /** «طلب تشغيل من الجوال» shows on the TV, waiting for «قبول» or «رفض». */
    PENDING,

    /** Accepted on the TV and opened. */
    ACCEPTED,

    /** «رفض» on the TV (or BACK there). */
    REJECTED,

    /** Nobody answered on the TV in time: rejected by itself. */
    TIMED_OUT,

    /** Accepted, but the TV could not open it any more ([RemotePlayRequest.message] says why). */
    FAILED,
}

/** A link a phone sent: [message] is what the phone shows for [state] (Arabic, FSI…PDI around names). */
@Serializable
data class RemotePlayRequest(
    val id: String,
    val title: String,
    val state: RemotePlayState,
    val message: String,
    /** While [state] is [RemotePlayState.PENDING]: seconds before it is rejected by itself. */
    val secondsLeft: Int = 0,
)

/**
 * Answer to `POST /api/play`: refused at once ([ok] false, the TV's reason; the TV shows nothing), or sent to the TV
 * ([ok] true) where [request] waits for «قبول» (its later states come with [RemoteStatus.playRequest]).
 */
@Serializable
data class RemotePlayResult(val ok: Boolean, val message: String, val request: RemotePlayRequest? = null) {
    companion object {
        fun refused(message: String) = RemotePlayResult(false, message)
    }
}

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
    /** A Short (v1.4): the page shows it in the row of Shorts, as a portrait card, and plays its `/shorts/` link. */
    val isShort: Boolean = false,
)

/**
 * One page of the phone page's search ([RemoteController.searchPage]). [items] are what the profile's rules allow of that page;
 * [shorts] is the row of Shorts the search found (the first page only, and only where the profile shows Shorts: empty otherwise,
 * and when YouTube would not say); [next] is what to send back for the following page, null at the end. A token is only good for
 * the query it came with, and for as long as the TV keeps it (an old one answers an empty last page).
 */
@Serializable
data class RemoteSearchPage(
    val items: List<RemoteItem>,
    val shorts: List<RemoteItem> = emptyList(),
    val next: String? = null,
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
    /** Why this profile cannot be deleted now (the last adult, the profile on the TV), shown beside «حذف»; null: it can. */
    val deleteBlockedReason: String? = null,
    /** «إظهار الفيديوهات المقترحة»; a child's come only from its allowed channels. */
    val showSuggestions: Boolean = false,
)

/** The parent's «الملفات»: every profile, and the TV's own choices for editing them (the phone offers nothing else). */
@Serializable
data class RemoteProfilesPage(
    val profiles: List<RemoteProfileDetails>,
    /** The TV's daily quota choices, in its order; null = no daily limit. */
    val quotaChoices: List<Int?>,
    /** Avatar keys an adult profile may take (the TV's picker, in its order). */
    val avatars: List<String>,
    /** Avatar keys a child profile may take. */
    val childAvatars: List<String>,
    /** The longest name the TV takes. */
    val nameMaxLength: Int,
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
