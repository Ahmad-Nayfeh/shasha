package com.ahmadnayfeh.shasha.remote

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

    /** Any YouTube link (video, playlist, channel), already trimmed. Must pass the PolicyEngine. */
    suspend fun playLink(url: String): RemoteResult

    /**
     * Refuse STOP ([RemoteResult.error]) while the playback lock of SPEC §4 is on: a locked video is left
     * only with the parent's PIN. The server also refuses STOP by itself whenever [status] reports
     * videoLocked, so keep that flag exact.
     */
    suspend fun control(cmd: PlaybackCommand): RemoteResult

    suspend fun profiles(): List<RemoteProfile>

    /**
     * [pin] is null when the page sent none. When the switch needs a PIN, check it through the same shared
     * PinAttemptLimiter as [parentLogin] and the TV's PIN pad (one limiter per PIN), and refuse a wrong
     * PIN, or any attempt while the limiter is locked, with [RemoteResult.error]. The server brakes
     * guessing on top of that: every non-ok answer to a call with a PIN counts as a wrong PIN, and after
     * a few of them it answers 429 without calling this at all.
     */
    suspend fun switchProfile(id: String, pin: String?): RemoteResult

    /** Checks the parent PIN through the shared PinAttemptLimiter. The server creates the session. */
    suspend fun parentLogin(pin: String): ParentLoginResult

    // Everything below is only called with a valid parent session.

    /** minutes == null: unlimited. */
    suspend fun setQuota(profileId: String, minutes: Int?): RemoteResult

    /** Unlocks "time up" / outside-schedule for the current profile. extraMinutes == null: the rest of today. */
    suspend fun unlockTimeUp(extraMinutes: Int?): RemoteResult

    suspend fun unlockVideo(): RemoteResult

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
}

/** Thrown by a [RemoteController] to refuse a request; the phone shows [userMessage] (HTTP 403). */
class RemoteRejection(val userMessage: String) : Exception(userMessage)
