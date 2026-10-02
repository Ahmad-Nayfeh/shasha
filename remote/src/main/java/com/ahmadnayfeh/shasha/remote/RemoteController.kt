package com.ahmadnayfeh.shasha.remote

import kotlinx.serialization.json.JsonElement

/**
 * What the phone can ask the TV to do. Implemented by :app.
 *
 * Calls arrive on Ktor's worker threads, possibly several at once (the page polls [status] every
 * 2 s while it sends commands), so implementations must be thread-safe and switch to the main
 * thread themselves for anything that touches the player or the UI.
 *
 * To refuse a request with an Arabic explanation, return [RemoteResult.error] or, from methods that
 * return data, throw [RemoteRejection]. Any other exception becomes a generic HTTP 500. In those Arabic
 * texts, wrap every title or name in FSI…PDI (U+2068 … U+2069), e.g. "«\u2068$title\u2069»": an
 * English title inside an Arabic sentence otherwise comes out in the wrong order on the phone.
 */
interface RemoteController {
    suspend fun status(): RemoteStatus

    /** Only called when [status] says canSearch; still reject (throw [RemoteRejection]) if not allowed. */
    suspend fun search(query: String): List<RemoteItem>

    /**
     * Any YouTube link (video, playlist, channel), already trimmed. Must pass the PolicyEngine first: a refused link
     * never reaches the TV's screen. An allowed one never opens by itself (v1.2): the TV asks «قبول» / «رفض» and the
     * answer carries the waiting [RemotePlayResult.request]; how it ends shows in [RemoteStatus.playRequest].
     */
    suspend fun playLink(url: String): RemotePlayResult

    /**
     * Refuse STOP ([RemoteResult.error]) while the playback lock of SPEC §4 is on: a locked video is left
     * only with the parent's PIN. The server also refuses STOP by itself whenever [status] reports
     * videoLocked, so keep that flag exact.
     */
    suspend fun control(cmd: PlaybackCommand): RemoteResult

    suspend fun profiles(): List<RemoteProfile>

    /**
     * [pin] is null when the page sent none. When the switch needs a PIN, check it through the same attempt
     * limiter as [parentLogin], one whose count is kept on disk (the server's own brake lives in memory: a
     * restart must not grant new guesses), and refuse a wrong PIN, or any attempt while the limiter is
     * locked, with [RemoteResult.error]. The server brakes guessing on top of that: every non-ok answer to a
     * call with a PIN counts as a wrong PIN, and after a few of them it answers 429 without calling this at all.
     */
    suspend fun switchProfile(id: String, pin: String?): RemoteResult

    /** Checks the parent PIN through the same persisted attempt limiter as [switchProfile]. The server creates the session. */
    suspend fun parentLogin(pin: String): ParentLoginResult

    // Everything below is only called with a valid parent session.

    /** minutes == null: unlimited. */
    suspend fun setQuota(profileId: String, minutes: Int?): RemoteResult

    /** Unlocks "time up" / outside-schedule for the current profile. extraMinutes == null: the rest of today. */
    suspend fun unlockTimeUp(extraMinutes: Int?): RemoteResult

    suspend fun unlockVideo(): RemoteResult

    /**
     * v1.2: the parent's «تأكيد» ([approve] true: the video starts) or «رفض» (it does not) for [RemoteStatus.confirmation]
     * [id]; refused ([RemoteResult.error]) when that confirmation no longer waits.
     */
    suspend fun answerConfirmation(id: String, approve: Boolean): RemoteResult

    suspend fun allowlist(profileId: String): RemoteAllowlist

    suspend fun addToAllowlist(profileId: String, link: String): RemoteResult

    /** [id] is a channelId or playlistId from [allowlist]. */
    suspend fun removeFromAllowlist(profileId: String, id: String): RemoteResult

    /**
     * Always CSV bytes (Takeout's subscriptions.csv, columns: channel id, url, title; header may be
     * localized). A Takeout .zip is unpacked by :remote before this is called.
     */
    suspend fun importTakeout(fileName: String, bytes: ByteArray): RemoteResult

    suspend fun subscriptions(): List<RemoteChannel>

    suspend fun addSubscription(link: String): RemoteResult

    suspend fun removeSubscription(channelId: String): RemoteResult

    /** UTF-8 JSON. */
    suspend fun exportBackup(): ByteArray

    suspend fun importBackup(bytes: ByteArray): RemoteResult

    // v1.1: every setting of the TV's Settings, with the TV's own choices and checks.

    suspend fun settings(): RemoteSettingsPage

    /**
     * [value] as [RemoteSetting.value] has it; refused (ok = false, the page as it is) when the TV would not offer it,
     * or when [base] (the value the page showed when the change began; null: not given) is no longer what the TV has:
     * another phone or the TV changed it meanwhile, and a whole list sent over it would undo that change unseen.
     */
    suspend fun changeSetting(key: String, value: JsonElement, base: JsonElement? = null): RemoteSettingsResult

    /** [group]: one [RemoteSettingGroup.id] back to its defaults; null: every setting («إرجاع كل الإعدادات»). */
    suspend fun resetSettings(group: String?): RemoteSettingsResult

    suspend fun profilesPage(): RemoteProfilesPage

    /**
     * A new child profile, as the TV's «إضافة ملف طفل» makes it (its default rules and the household's blocks). [name] is
     * trimmed; refused ([RemoteResult.error]) when it is empty or too long, or [avatar] is not a child's.
     */
    suspend fun createChild(name: String, avatar: String): RemoteResult

    /** A new [name] and/or [avatar] (null leaves it as it is), with the TV's checks. */
    suspend fun editProfile(profileId: String, name: String?, avatar: String?): RemoteResult

    /** Refused for the last adult profile and for the profile on the TV now ([RemoteProfileDetails.deleteBlockedReason]). */
    suspend fun deleteProfile(profileId: String): RemoteResult

    /** null leaves that switch as it is. */
    suspend fun setProfileFlags(profileId: String, hideShorts: Boolean?, hideComments: Boolean?, showSuggestions: Boolean?): RemoteResult

    /** Minutes since midnight (0..1439); both null = all day. The same time twice also means all day. */
    suspend fun setSchedule(profileId: String, fromMinute: Int?, untilMinute: Int?): RemoteResult

    /** The household's blocklist (it applies to every profile). */
    suspend fun blocklist(): RemoteBlocklist

    suspend fun blockKeyword(keyword: String): RemoteResult

    suspend fun unblockKeyword(keyword: String): RemoteResult

    /** Any channel link. */
    suspend fun blockChannel(link: String): RemoteResult

    suspend fun unblockChannel(channelId: String): RemoteResult

    suspend fun stats(): List<RemoteStats>
}

/** Thrown by a [RemoteController] to refuse a request; the phone shows [userMessage] (HTTP 403). */
class RemoteRejection(val userMessage: String) : Exception(userMessage)
