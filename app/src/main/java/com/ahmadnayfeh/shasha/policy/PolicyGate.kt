package com.ahmadnayfeh.shasha.policy

import android.util.Log
import androidx.annotation.StringRes
import com.ahmadnayfeh.shasha.data.ActiveProfile
import com.ahmadnayfeh.shasha.data.ParentPinRepository
import com.ahmadnayfeh.shasha.data.PinResult
import com.ahmadnayfeh.shasha.data.PlaybackLockStore
import com.ahmadnayfeh.shasha.data.ProfileRepository
import com.ahmadnayfeh.shasha.lock.SurrenderLog
import com.ahmadnayfeh.shasha.youtube.SourceItem
import com.ahmadnayfeh.shasha.youtube.StreamDetails
import com.ahmadnayfeh.shasha.youtube.VideoItem
import com.ahmadnayfeh.shasha.youtube.YouTubeLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** How a video came to be played; it decides which of the engine's checks applies. */
sealed interface PlayOrigin {
    /** Chosen on the TV: a card of any list (home, search, channel, history, watch later) or a video id. */
    data object Navigation : PlayOrigin

    /**
     * Taken from [playlistId]'s own pages: the playlist screen, or the next video inside it. Only the
     * app's own code says so. A link's `list=` parameter never makes this origin (use [Link]).
     */
    data class Playlist(val playlistId: String) : PlayOrigin

    /** A link from the phone or from another app: whatever its `list=`, the video's own channel must be allowed. */
    data object Link : PlayOrigin
}

sealed interface PlayCheck {
    /** [stream] is the extraction the check needed (null when the list item said enough): the player may use it. */
    data class Allowed(val stream: StreamDetails?) : PlayCheck

    data class Denied(val reason: DenyReason) : PlayCheck {
        /** The Arabic sentence for the viewer or the phone. */
        @get:StringRes
        val message: Int get() = reason.messageRes()
    }
}

sealed interface SwitchResult {
    data object Switched : SwitchResult

    /** This switch needs the parent PIN ([PolicyGate.switchNeedsPin]) and none was given. */
    data object PinRequired : SwitchResult

    data class WrongPin(val attemptsLeft: Int) : SwitchResult

    data class LockedOut(val remainingMillis: Long) : SwitchResult

    /**
     * No parent PIN exists yet, and this switch needs one (entering a child profile does too: nobody
     * could let the child out). The owner sets the PIN up first, from the adult profile.
     */
    data object PinNotSet : SwitchResult

    /** A video is locked (SPEC §4) and this switch cannot end the lock (the adult's exit challenge, or no PIN involved). */
    data object PlaybackLocked : SwitchResult

    data object NotFound : SwitchResult
}

/** The extraction the check needs when a list item does not say enough (the player needs it anyway). */
fun interface VideoLookup {
    /** @throws com.ahmadnayfeh.shasha.youtube.SourceException like VideoSource.stream. */
    suspend fun lookup(videoId: String): LookedUpVideo
}

/** [stream] is null only in tests (StreamDetails cannot be built outside :youtube). */
class LookedUpVideo(val video: VideoItem, val stream: StreamDetails?)

/**
 * The one door every screen (and, from phase 5, the phone) goes through before showing or playing
 * anything, switching profile or accepting the parent PIN (SPEC §3-5). Every rule is the engine's;
 * this only feeds it the current profile, session, lock and video.
 *
 * - Lists: [filter] (a [ContentFilter] for the current profile, renewed on every switch or edit), and
 *   [canOpenChannel] / [canOpenPlaylist] before opening a page.
 * - Playback: [checkPlay] before extracting (the player screen), [checkLink] for links from the phone.
 * - Profiles: [switchNeedsPin], [switchProfile]; the PIN: [verifyParentPin].
 * - The guard: [lockState].
 */
class PolicyGate(
    private val engine: PolicyEngine,
    private val active: ActiveProfile,
    private val profiles: ProfileRepository,
    private val session: SessionController,
    private val locks: PlaybackLockStore,
    private val parentPin: ParentPinRepository,
    private val lookup: VideoLookup,
    scope: CoroutineScope,
    /** Where an adult lock left behind at the start is recorded as a surrender (null: nowhere, in tests). */
    private val surrenders: SurrenderLog? = null,
) {
    private val switching = Mutex()

    /** The current profile; null only while it is loaded at start. */
    val profile: StateFlow<Profile?> get() = active.profile

    /**
     * The list rules of the current profile ([ContentFilter.NOTHING] until it is loaded). Screens combine
     * their items with it, so a profile switch or an allowlist edit from the phone applies at once.
     */
    val filter: StateFlow<ContentFilter> = active.profile
        .map { ContentFilter.of(engine, it) }
        .stateIn(scope, SharingStarted.Eagerly, ContentFilter.of(engine, active.profile.value))

    /** Playback lock + session, for the guard: `engine.guardShouldEnforce(lockState.value)`. */
    val lockState: StateFlow<LockState> = combine(locks.lock, session.state) { lock, state -> LockState.of(lock, state) }
        .stateIn(scope, SharingStarted.Eagerly, LockState.of(locks.lock.value, session.state.value))

    /** False on a first start: the owner sets up the parent PIN before anything needs it. */
    val pinIsSet: Flow<Boolean> get() = parentPin.isSet

    /** The list rules once the current profile is loaded (for code that runs at start, e.g. a page fetch). */
    suspend fun awaitFilter(): ContentFilter = ContentFilter.of(engine, active.awaitProfile())

    // The current rules applied once (nothing before the profile is loaded); see ContentFilter for each shape.

    fun visibleItems(items: List<SourceItem>): List<SourceItem> = filter.value.items(items)

    fun visibleVideos(videos: List<VideoItem>): List<VideoItem> = filter.value.videos(videos)

    fun visibleChannelVideos(channelId: String, items: List<SourceItem>): List<VideoItem> = filter.value.channelVideos(channelId, items)

    fun visiblePlaylistVideos(playlistId: String, videos: List<VideoItem>): List<VideoItem> = filter.value.playlistVideos(playlistId, videos)

    fun canOpenChannel(channelId: String): Decision = filter.value.channel(channelId)

    fun canOpenPlaylist(playlistId: String, ownerChannelId: String?, title: String? = null): Decision =
        filter.value.playlist(playlistId, ownerChannelId, title)

    /**
     * May [videoId] start now? Content first (a forbidden video never even shows "time up"), then the
     * schedule and the quota, then the playback lock: while a video is locked nothing else starts,
     * except what the lock holds itself, whatever [origin] the player has: the locked video (back after
     * Home, standby, "time up" or a restart; a player that came from a link too), and, for a player of
     * the lock's own playlist ([PlayOrigin.Playlist]), that playlist's videos (a child's lock moves on to
     * the next video while the ended one still shows its countdown, and «شغّل الآن» may come before the
     * move is stored).
     *
     * [known] is the list item that was chosen, when there is one: with its channel (and, where shorts
     * are hidden, its duration) no extraction is needed. Otherwise, and always for a [PlayOrigin.Link],
     * the video is extracted first (the player's own extraction then comes from the cache).
     *
     * @throws com.ahmadnayfeh.shasha.youtube.SourceException when the video had to be extracted and that
     *   failed: show the unified error screen, like the player does.
     */
    suspend fun checkPlay(videoId: String, origin: PlayOrigin, known: VideoItem? = null): PlayCheck =
        check(videoId, origin, known, lockedPasses = true)

    /**
     * A video link from the phone (or shared from another app), before anything navigates to it. Its
     * playlist (`&list=`) is ignored on purpose: in a child profile the video's own channel must be
     * allowlisted (SPEC acceptance: a channel outside the allowlist never plays, even through a link). To
     * play a playlist, open the playlist. While a video is locked no link starts (LOCKED), the locked
     * video's own link included: it is already on screen.
     */
    suspend fun checkLink(link: YouTubeLink.Video): PlayCheck = check(link.id, PlayOrigin.Link, known = null, lockedPasses = false)

    private suspend fun check(videoId: String, origin: PlayOrigin, known: VideoItem?, lockedPasses: Boolean): PlayCheck {
        val current = active.awaitProfile()
        val trusted = known?.takeIf { origin != PlayOrigin.Link && it.videoId == videoId && it.channelId != null && !needsDuration(current, it) }
        val found = if (trusted == null) lookup.lookup(videoId) else null
        val video = trusted ?: found!!.video
        val candidate = video.toCandidate(viaPlaylistId = (origin as? PlayOrigin.Playlist)?.playlistId)
        val state = session.current()
        val lock = locks.current()
        val held = lockedPasses && lock != null && lock.profileId == current.id &&
            (lock.videoId == videoId || (lock.playlistId != null && (origin as? PlayOrigin.Playlist)?.playlistId == lock.playlistId))
        // What the lock holds is judged without it (the content and time rules still apply to it).
        val lockState = LockState.of(lock.takeUnless { held }, state)
        val decision = if (origin == PlayOrigin.Link) engine.canPlayLink(current, candidate, lockState) else engine.canPlay(current, candidate, lockState)
        return when (decision) {
            Decision.Allow -> PlayCheck.Allowed(found?.stream)
            is Decision.Deny -> PlayCheck.Denied(decision.reason)
        }
    }

    /** The parent PIN, through the one shared attempt limiter (TV and phone alike). */
    suspend fun verifyParentPin(pin: String): PinResult = parentPin.verify(pin)

    /** Whether switching from the current profile to [target] needs the parent PIN: a child's exit, the adult's entry. */
    fun switchNeedsPin(target: Profile): Boolean {
        val current = active.profile.value ?: return true
        return current.id != target.id && needsPin(current, target)
    }

    /**
     * Makes [profileId] current when the engine's rules allow it: the parent PIN to leave a child
     * profile or to enter the adult one ([pin] null when none was typed). A child's playback lock ends
     * with the PIN (the parent is there); the adult's needs its exit challenge first.
     *
     * [fromStartPicker]: the choice is made on the picker the TV shows when it starts or comes back after
     * a while ([com.ahmadnayfeh.shasha.ui.app.PickerMode.START]). An adult's locked video left there (the
     * TV was switched off mid-video) must not keep the others from the TV: choosing another profile ends
     * it, recorded as a surrender ([PolicyEngine.surrenderOf]). Choosing the same adult keeps it.
     */
    suspend fun switchProfile(profileId: String, pin: String?, fromStartPicker: Boolean = false): SwitchResult = switching.withLock {
        val target = profiles.get(profileId) ?: return@withLock SwitchResult.NotFound
        val current = active.awaitProfile()
        if (target.id == current.id) return@withLock SwitchResult.Switched
        val needsPin = needsPin(current, target)
        if ((needsPin || target.isChild) && !parentPin.isSet()) return@withLock SwitchResult.PinNotSet
        val lock = locks.current()
        val leftBehind = lock?.takeIf { fromStartPicker && it.unlock == UnlockMethod.EXIT_CHALLENGE && it.profileId == current.id }
        if (lock != null && leftBehind == null && (!needsPin || engine.unlockWithPin(lock) != null)) return@withLock SwitchResult.PlaybackLocked
        if (needsPin) {
            if (pin == null) return@withLock SwitchResult.PinRequired
            when (val result = parentPin.verify(pin)) {
                PinResult.Success -> Unit
                is PinResult.Wrong -> return@withLock SwitchResult.WrongPin(result.attemptsLeft)
                is PinResult.LockedOut -> return@withLock SwitchResult.LockedOut(result.remainingMillis)
                PinResult.NotSet -> return@withLock SwitchResult.PinNotSet
            }
        }
        if (leftBehind != null) recordLeftBehind(leftBehind)
        // The verified PIN ended a child's lock (engine.unlockWithPin), or the adult's was left behind.
        if (lock != null) locks.set(null)
        if (active.switchTo(target.id)) SwitchResult.Switched else SwitchResult.NotFound
    }

    /**
     * The surrender of an adult lock left behind at the start. A failure to store it is logged and the
     * switch goes on: whoever sits at the TV must never be kept out by it.
     */
    private suspend fun recordLeftBehind(lock: PlaybackLock) {
        val surrender = engine.surrenderOf(lock) ?: return
        try {
            surrenders?.record(surrender)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not record the surrender of a lock left behind", e)
        }
    }

    private fun needsPin(from: Profile, to: Profile): Boolean =
        engine.features(from).exitNeedsPin || engine.features(to).enterNeedsPin

    /** The shorts rule needs a duration the item lacks (RSS items): the extraction has it. */
    private fun needsDuration(profile: Profile, video: VideoItem): Boolean = engine.needsDuration(profile, video.toCandidate())

    private companion object {
        const val TAG = "PolicyGate"
    }
}
