package com.ahmadnayfeh.shasha.remote

import androidx.annotation.StringRes
import com.ahmadnayfeh.shasha.R
import com.ahmadnayfeh.shasha.backup.BackupException
import com.ahmadnayfeh.shasha.backup.BackupManager
import com.ahmadnayfeh.shasha.data.AddResult
import com.ahmadnayfeh.shasha.data.PinResult
import com.ahmadnayfeh.shasha.data.ProfileRepository
import com.ahmadnayfeh.shasha.data.SubscriptionRepository
import com.ahmadnayfeh.shasha.data.takeout.TakeoutException
import com.ahmadnayfeh.shasha.lock.LockController
import com.ahmadnayfeh.shasha.player.PlayQueue
import com.ahmadnayfeh.shasha.player.PlayQueues
import com.ahmadnayfeh.shasha.player.PlayerCommand
import com.ahmadnayfeh.shasha.player.PlayerHub
import com.ahmadnayfeh.shasha.player.PlayerRefusal
import com.ahmadnayfeh.shasha.policy.Decision
import com.ahmadnayfeh.shasha.policy.ContentFilter
import com.ahmadnayfeh.shasha.policy.DenyReason
import com.ahmadnayfeh.shasha.policy.ListedChannel
import com.ahmadnayfeh.shasha.policy.ListedPlaylist
import com.ahmadnayfeh.shasha.policy.PlayCheck
import com.ahmadnayfeh.shasha.policy.PlayOrigin
import com.ahmadnayfeh.shasha.policy.PolicyGate
import com.ahmadnayfeh.shasha.policy.Profile
import com.ahmadnayfeh.shasha.policy.SessionController
import com.ahmadnayfeh.shasha.policy.SessionState
import com.ahmadnayfeh.shasha.policy.SwitchResult
import com.ahmadnayfeh.shasha.policy.UnlockMethod
import com.ahmadnayfeh.shasha.ui.format.Thumbnails
import com.ahmadnayfeh.shasha.ui.navigation.Screen
import com.ahmadnayfeh.shasha.ui.parental.ParentalLists
import com.ahmadnayfeh.shasha.youtube.ChannelItem
import com.ahmadnayfeh.shasha.youtube.PlaylistItem
import com.ahmadnayfeh.shasha.youtube.SourceException
import com.ahmadnayfeh.shasha.youtube.SourceItem
import com.ahmadnayfeh.shasha.youtube.VideoItem
import com.ahmadnayfeh.shasha.youtube.VideoSource
import com.ahmadnayfeh.shasha.youtube.YouTubeLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement

/**
 * The TV's side of the phone remote (SPEC §5): every request goes through the same doors as the TV's own
 * screens. What plays is decided by [PolicyGate] (a link: `checkLink`, the video's own channel; a child
 * never gets anything outside its allowlist, and the TV then shows nothing at all), profile switches by
 * `PolicyGate.switchProfile`, time by [SessionController], the playback lock by [LockController], the lists
 * by [ParentalLists], the subscriptions by [SubscriptionRepository], the backup by [BackupManager].
 *
 * Called on the server's threads, several at once: nothing here keeps state. The player and the screens are
 * reached on [main] ([PlayerHub], [RemoteDisplay]), with timeouts, so a busy TV answers "try again" instead
 * of leaving the phone hanging. Refusals are Arabic sentences ([RemoteTexts]).
 *
 * Two rules of phase 5 on top of the TV's: while a video is locked (SPEC §4) STOP, new links, profile
 * switches and restores are refused (the parent unlocks the video first); and PINs from the phone are
 * checked by [PhonePin], never by the TV's attempt limiter (owner decision: a child's wrong guesses on
 * the TV never close the parent's phone route; the phone has its own count, saved like the TV's, behind
 * the server's own brake).
 *
 * What a link opens is bound to the profile it was checked for ([RemoteDisplay.show]): someone switching
 * profile on the TV meanwhile gets nothing. Writes are never cut short ([whole]): a server that stops (the
 * TV's address changed) or a phone that goes away loses the answer, never half a restore.
 */
class TvRemoteController(
    private val gate: PolicyGate,
    private val profiles: ProfileRepository,
    private val session: SessionController,
    private val lock: LockController,
    private val lists: ParentalLists,
    private val subscriptions: SubscriptionRepository,
    private val source: VideoSource,
    private val phonePin: PhonePin,
    private val backup: BackupManager,
    private val player: PlayerHub,
    private val display: RemoteDisplay,
    private val playQueues: PlayQueues,
    private val texts: RemoteTexts,
    private val main: CoroutineDispatcher = Dispatchers.Main.immediate,
    /** «الوضع الجاد» as the rules have it now (the page explains a video that cannot be stopped). */
    private val seriousMode: () -> Boolean = { true },
    /** Where the TV's picker hears which profile the phone chose. */
    private val profileChoices: PhoneProfileChoices = PhoneProfileChoices(),
    /** v1.1: every setting of the TV's Settings (null: the page's settings are refused, e.g. in tests of the rest). */
    private val settings: PhoneSettings? = null,
    /** v1.1: profile rules, the block list and the stats. */
    private val parental: PhoneParental? = null,
    /** v1.1: the mark that moves when anything the parent section shows changes ([RemoteRevision]). */
    private val revision: () -> Long = { 0L },
    /** v1.1: the port the server moves to in a moment ([RemoteRuntime.movingTo]): every open page follows it. */
    private val movingTo: () -> Int? = { null },
) : RemoteController {

    // ---- Everyone ---------------------------------------------------------------------------------------

    override suspend fun status(): RemoteStatus {
        val filter = rules()
        val profile = filter.profile!!
        // Kept current by the session itself (every 15 s and on every change): cheap for a page polling every 2 s.
        val state = session.state.value
        return RemoteStatus(
            profileName = profile.name,
            profileKind = profile.remoteKind(),
            canSearch = filter.features?.search == true,
            nowPlaying = nowPlaying(),
            timeLeftMinutes = state.minutesLeft(),
            sessionState = state.remote(),
            videoLocked = lock.lock.value != null,
            seriousMode = seriousMode(),
            rev = revision(),
            movingTo = movingTo(),
        )
    }

    private suspend fun nowPlaying(): RemoteNowPlaying? {
        if (player.active.value == null) return null
        val now = withTimeoutOrNull(MAIN_TIMEOUT_MS) { withContext(main) { player.active.value?.nowPlaying() } } ?: return null
        return RemoteNowPlaying(
            videoId = now.videoId,
            title = now.title,
            channelName = now.channelName,
            positionSeconds = now.positionMs / 1000,
            durationSeconds = now.durationMs?.let { it / 1000 },
            isPlaying = now.playing,
            isLive = now.live,
            thumbnailUrl = now.thumbnailUrl,
        )
    }

    override suspend fun search(query: String): List<RemoteItem> {
        if (rules().features?.search != true) throw RemoteRejection(texts.get(R.string.remote_search_disabled))
        val page = try {
            withTimeoutOrNull(SEARCH_TIMEOUT_MS) { source.search(query) } ?: throw RemoteRejection(texts.get(R.string.remote_timeout))
        } catch (e: SourceException) {
            throw RemoteRejection(texts.sourceError(e))
        }
        // The rules as they are now: a switch to a child profile meanwhile empties the list.
        return rules().items(page.items).map { it.toRemoteItem() }
    }

    override suspend fun playLink(url: String): RemoteResult {
        val link = source.parseLink(url) ?: return error(R.string.remote_link_not_youtube)
        // SPEC §4: a locked video is the only thing on screen until it ends or the parent unlocks it.
        if (lock.current() != null) return RemoteResult.error(texts.deny(DenyReason.LOCKED))
        // Read before any check: what the checks allow opens only in this profile's screens.
        val profileId = currentProfile().id
        return withTimeoutOrNull(PLAY_TIMEOUT_MS) {
            try {
                when (link) {
                    is YouTubeLink.Video -> playVideo(link, profileId)
                    is YouTubeLink.Playlist -> playPlaylist(link.id, profileId)
                    is YouTubeLink.Channel -> openChannel(link, profileId)
                }
            } catch (e: SourceException) {
                RemoteResult.error(texts.sourceError(e))
            }
        } ?: error(R.string.remote_timeout)
    }

    /**
     * SPEC acceptance: a child's link to a channel outside the allowlist is refused here, and the TV shows nothing.
     * A shared link's start time (`t=`) is where the video starts.
     */
    private suspend fun playVideo(link: YouTubeLink.Video, profileId: String): RemoteResult {
        val allowed = when (val check = gate.checkLink(link)) {
            is PlayCheck.Denied -> return RemoteResult.error(texts.deny(check.reason))
            is PlayCheck.Allowed -> check
        }
        // The player checks again with the link's rule (fromLink): the video's own channel, never its `list=`.
        val destination = Screen.Player(link.id, playlistId = link.playlistId, fromLink = true, startSeconds = link.startSeconds?.takeIf { it > 0 })
        val shown = display.show(destination, profileId)
        if (shown != ShowResult.SHOWN) return RemoteResult.error(texts.show(shown))
        val title = allowed.stream?.video?.title
        return RemoteResult.ok(if (title != null) texts.get(R.string.remote_play_sent, title) else texts.get(R.string.remote_play_sent_plain))
    }

    /**
     * A playlist link plays the playlist from its first video the profile may watch, exactly as its page on the
     * TV would (a child: only an allowlisted playlist). The link's playlist is the page's, so its videos pass as
     * the playlist's own ([PlayOrigin.Playlist]).
     */
    private suspend fun playPlaylist(playlistId: String, profileId: String): RemoteResult {
        blockedBySession()?.let { return it }
        denied(rules().playlist(playlistId, ownerChannelId = null))?.let { return it }
        val page = source.playlist(playlistId)
        val filter = rules()
        denied(filter.playlist(playlistId, ownerChannelId = null, title = page.details.title))?.let { return it }
        val videos = page.videos.distinctBy { it.videoId }
        val index = videos.indexOfFirst { filter.playlistVideos(playlistId, listOf(it)).isNotEmpty() }
        if (index < 0) return error(R.string.remote_playlist_nothing_allowed)
        val first = videos[index]
        val check = gate.checkPlay(first.videoId, PlayOrigin.Playlist(playlistId), known = first)
        if (check is PlayCheck.Denied) return RemoteResult.error(texts.deny(check.reason))
        playQueues.put(PlayQueue(playlistId, videos, page.next))
        val shown = display.show(Screen.Player(first.videoId, playlistId = playlistId, playlistIndex = index), profileId)
        if (shown != ShowResult.SHOWN) return RemoteResult.error(texts.show(shown))
        return RemoteResult.ok(texts.get(R.string.remote_play_playlist, page.details.title))
    }

    private suspend fun openChannel(link: YouTubeLink.Channel, profileId: String): RemoteResult {
        blockedBySession()?.let { return it }
        val channelId = source.resolveChannelId(link)
        denied(rules().channel(channelId))?.let { return it }
        val shown = display.show(Screen.Channel(channelId), profileId)
        if (shown != ShowResult.SHOWN) return RemoteResult.error(texts.show(shown))
        return RemoteResult.ok(texts.get(R.string.remote_play_channel))
    }

    /** "Time up" or outside the schedule: nothing opens (the TV shows its own screen for it). */
    private suspend fun blockedBySession(): RemoteResult? = when (session.current()) {
        SessionState.TimeUp -> RemoteResult.error(texts.deny(DenyReason.TIME_UP))
        is SessionState.OutsideSchedule -> RemoteResult.error(texts.deny(DenyReason.OUTSIDE_SCHEDULE))
        else -> null
    }

    private fun denied(decision: Decision): RemoteResult? = (decision as? Decision.Deny)?.let { RemoteResult.error(texts.deny(it.reason)) }

    override suspend fun control(cmd: PlaybackCommand): RemoteResult {
        // SPEC §4: a locked video is left with the parent's PIN (or the adult's challenge), never from the phone.
        if (cmd.action == PlaybackAction.STOP && lock.lock.value != null) return error(R.string.remote_video_locked)
        if (player.active.value == null) return error(R.string.remote_nothing_playing)
        val outcome = withTimeoutOrNull(MAIN_TIMEOUT_MS) {
            withContext(main) {
                val active = player.active.value ?: return@withContext R.string.remote_nothing_playing
                if (cmd.action == PlaybackAction.STOP) {
                    active.leave()
                    return@withContext R.string.remote_stopped
                }
                when (active.perform(cmd.toPlayerCommand())) {
                    null -> DONE
                    PlayerRefusal.ENDED -> R.string.remote_player_ended
                    PlayerRefusal.LIVE -> R.string.remote_player_live
                    PlayerRefusal.COVERED -> R.string.remote_player_covered
                }
            }
        } ?: return error(R.string.remote_timeout)
        return when (outcome) {
            DONE -> RemoteResult.ok("")
            R.string.remote_stopped -> RemoteResult.ok(texts.get(outcome))
            else -> error(outcome)
        }
    }

    override suspend fun profiles(): List<RemoteProfile> {
        val current = currentProfile()
        return profiles.all().map { p ->
            RemoteProfile(
                id = p.id,
                name = p.name,
                kind = p.remoteKind(),
                needsPin = p.id != current.id && gate.switchNeedsPin(p),
                avatar = p.avatar,
                isCurrent = p.id == current.id,
                dailyQuotaMinutes = p.dailyQuotaMinutes,
            )
        }
    }

    /**
     * «اختيار الملف». The TV follows the choice ([profileChoices]): while it asks «من يشاهد الآن؟», a child profile
     * the phone chose opens there. The adult profile the phone reaches (with the PIN, here) is asked for again on
     * the TV, whoever holds its remote now (RootViewModel): the answer says so.
     */
    override suspend fun switchProfile(id: String, pin: String?): RemoteResult {
        val target = profiles.get(id) ?: return error(R.string.remote_profile_not_found)
        val current = currentProfile()
        if (target.id == current.id) {
            profileChoices.chose(target.id)
            return RemoteResult.ok(texts.get(R.string.remote_profile_current, target.name))
        }
        // A locked video is never ended by a switch from the phone: the parent unlocks it first (parent section).
        if (lock.current() != null) return error(R.string.remote_switch_locked)
        return when (val result = whole { gate.switchProfile(id, pin, verifyPin = phonePin::check) }) {
            SwitchResult.Switched -> {
                profileChoices.chose(target.id)
                RemoteResult.ok(texts.get(if (target.isAdult) R.string.remote_profile_switched_adult else R.string.remote_profile_switched, target.name))
            }
            SwitchResult.PinRequired -> error(R.string.remote_profile_needs_pin)
            is SwitchResult.WrongPin -> RemoteResult.error(texts.wrongPin(result.attemptsLeft))
            is SwitchResult.LockedOut -> RemoteResult.error(texts.get(R.string.remote_pin_locked, texts.minutesAfter(result.remainingMillis.ceilMinutes())))
            SwitchResult.PinNotSet -> error(R.string.remote_pin_not_set)
            SwitchResult.PlaybackLocked -> error(R.string.remote_switch_locked)
            SwitchResult.NotFound -> error(R.string.remote_profile_not_found)
        }
    }

    override suspend fun parentLogin(pin: String): ParentLoginResult = when (val result = phonePin.check(pin)) {
        PinResult.Success -> ParentLoginResult.Success
        is PinResult.Wrong -> ParentLoginResult.WrongPin(result.attemptsLeft)
        is PinResult.LockedOut -> ParentLoginResult.LockedOut((result.remainingMillis + 999) / 1000)
        PinResult.NotSet -> throw RemoteRejection(texts.get(R.string.remote_pin_not_set))
    }

    // ---- The parent section (the server lets only a logged-in parent reach these) -----------------------

    override suspend fun setQuota(profileId: String, minutes: Int?): RemoteResult {
        val updated = try {
            whole { profiles.update(profileId) { it.copy(dailyQuotaMinutes = minutes) } }
        } catch (e: IllegalArgumentException) {
            return error(R.string.remote_time_failed)
        } ?: return error(R.string.remote_profile_not_found)
        return RemoteResult.ok(
            if (minutes == null) {
                texts.get(R.string.remote_quota_unlimited, updated.name)
            } else {
                texts.get(R.string.remote_quota_saved, updated.name, texts.minutes(minutes))
            },
        )
    }

    /**
     * The phone's «+N دقيقة» / «بقية اليوم» for the profile on the TV now: N minutes more than what is left
     * ([SessionController.addTime]: after "time up" the same grants as the TV's time-up screen after the PIN; while
     * time is left, on top of it). Works for the quota and for the schedule (bedtime) alike; «بقية اليوم» lifts the
     * quota and, when the schedule stops the profile now, the schedule until the end of the day. The TV's "time up"
     * screen goes away by itself.
     */
    override suspend fun unlockTimeUp(extraMinutes: Int?): RemoteResult {
        val profile = currentProfile()
        if (profile.dailyQuotaMinutes == null && (profile.schedule == null || profile.schedule!!.isAlwaysAllowed)) {
            return RemoteResult.ok(texts.get(R.string.remote_time_no_limit, profile.name))
        }
        val after = try {
            whole { if (extraMinutes == null) session.unlimitedToday() else session.addTime(extraMinutes) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return error(R.string.remote_time_failed)
        }
        if (!after.canWatch) return error(R.string.remote_time_failed)
        return RemoteResult.ok(
            if (extraMinutes == null) {
                texts.get(R.string.remote_time_rest_of_today, profile.name)
            } else {
                texts.get(R.string.remote_time_added, profile.name, texts.minutes(extraMinutes))
            },
        )
    }

    override suspend fun unlockVideo(): RemoteResult {
        val locked = lock.current() ?: return error(R.string.remote_video_not_locked)
        val unlocked = try {
            // Whole: the adult's surrender is stored first, then the lock ends; never one without the other.
            whole { lock.unlockByParent() }
        } catch (e: IllegalStateException) {
            // The adult's surrender could not be stored: the lock stays (LockController).
            return error(R.string.remote_video_unlock_failed)
        }
        if (!unlocked) return error(R.string.remote_video_not_locked)
        return RemoteResult.ok(
            texts.get(if (locked.unlock == UnlockMethod.EXIT_CHALLENGE) R.string.remote_video_unlocked_adult else R.string.remote_video_unlocked),
        )
    }

    override suspend fun allowlist(profileId: String): RemoteAllowlist {
        val profile = profiles.get(profileId) ?: throw RemoteRejection(texts.get(R.string.remote_profile_not_found))
        if (!profile.isChild) throw RemoteRejection(texts.get(R.string.remote_allowlist_child_only))
        return RemoteAllowlist(
            channels = profile.allowlist.channels.map { RemoteListEntry(it.channelId, it.name) },
            playlists = profile.allowlist.playlists.map { RemoteListEntry(it.playlistId, it.title) },
        )
    }

    /** A channel (`/channel/UC…`, `@handle`, `/c/…`, `/user/…`) or a playlist (also a video link's `list=`). */
    override suspend fun addToAllowlist(profileId: String, link: String): RemoteResult {
        val profile = profiles.get(profileId) ?: return error(R.string.remote_profile_not_found)
        if (!profile.isChild) return error(R.string.remote_allowlist_child_only)
        val parsed = source.parseLink(link)
        val playlistId = (parsed as? YouTubeLink.Playlist)?.id ?: (parsed as? YouTubeLink.Video)?.playlistId
        return try {
            when {
                parsed is YouTubeLink.Channel -> {
                    val channelId = source.resolveChannelId(parsed)
                    profile.allowlist.channels.firstOrNull { it.channelId == channelId }?.let {
                        return RemoteResult.ok(texts.get(R.string.remote_allowlist_exists, it.name, profile.name))
                    }
                    val name = source.channel(channelId).channel.name.trim().ifEmpty { channelId }
                    whole { lists.allowChannel(profileId, ListedChannel(channelId, name)) } ?: return error(R.string.remote_profile_not_found)
                    RemoteResult.ok(texts.get(R.string.remote_allowlist_added, name, profile.name))
                }
                playlistId != null -> {
                    profile.allowlist.playlists.firstOrNull { it.playlistId == playlistId }?.let {
                        return RemoteResult.ok(texts.get(R.string.remote_allowlist_exists, it.title, profile.name))
                    }
                    val title = source.playlist(playlistId).details.title.trim().ifEmpty { playlistId }
                    whole { lists.allowPlaylist(profileId, ListedPlaylist(playlistId, title)) } ?: return error(R.string.remote_profile_not_found)
                    RemoteResult.ok(texts.get(R.string.remote_allowlist_added, title, profile.name))
                }
                else -> error(R.string.remote_allowlist_bad_link)
            }
        } catch (e: SourceException) {
            RemoteResult.error(texts.sourceError(e))
        }
    }

    override suspend fun removeFromAllowlist(profileId: String, id: String): RemoteResult {
        val profile = profiles.get(profileId) ?: return error(R.string.remote_profile_not_found)
        if (!profile.isChild) return error(R.string.remote_allowlist_child_only)
        val title = profile.allowlist.channels.firstOrNull { it.channelId == id }?.name
            ?: profile.allowlist.playlists.firstOrNull { it.playlistId == id }?.title
            ?: return error(R.string.remote_allowlist_not_there)
        whole { lists.disallow(profileId, id) } ?: return error(R.string.remote_profile_not_found)
        return RemoteResult.ok(texts.get(R.string.remote_allowlist_removed, title, profile.name))
    }

    /** The CSV (the server unzipped a Takeout archive already). New channels are synced at once by the repository. */
    override suspend fun importTakeout(fileName: String, bytes: ByteArray): RemoteResult {
        val result = try {
            whole { subscriptions.importTakeout(fileName, bytes) }
        } catch (e: TakeoutException) {
            return RemoteResult.error(texts.takeout(e.reason))
        }
        return when {
            result.added == 0 && result.alreadyPresent == 0 -> error(R.string.import_error_no_channels)
            result.added == 0 -> RemoteResult.ok(texts.get(R.string.remote_takeout_all_present, texts.channels(result.alreadyPresent)))
            result.alreadyPresent == 0 -> RemoteResult.ok(texts.get(R.string.remote_takeout_added, texts.channels(result.added)))
            else -> RemoteResult.ok(
                texts.get(R.string.remote_takeout_added_some_present, texts.channels(result.added), texts.channels(result.alreadyPresent)),
            )
        }
    }

    override suspend fun subscriptions(): List<RemoteChannel> =
        subscriptions.observe().first().map { RemoteChannel(it.channelId, it.name, it.avatarUrl) }

    override suspend fun addSubscription(link: String): RemoteResult = try {
        when (val result = whole { subscriptions.add(link) }) {
            is AddResult.Added -> RemoteResult.ok(texts.get(R.string.remote_subs_added, result.channel.name))
            is AddResult.AlreadySubscribed -> RemoteResult.ok(texts.get(R.string.remote_subs_exists, result.channel.name))
            AddResult.NotAChannel -> error(R.string.remote_subs_not_channel)
        }
    } catch (e: SourceException) {
        RemoteResult.error(texts.sourceError(e))
    }

    override suspend fun removeSubscription(channelId: String): RemoteResult {
        val channel = subscriptions.observe().first().firstOrNull { it.channelId == channelId }
            ?: return error(R.string.remote_subs_not_subscribed)
        whole { subscriptions.remove(channelId) }
        return RemoteResult.ok(texts.get(R.string.remote_subs_removed, channel.name))
    }

    override suspend fun exportBackup(): ByteArray = backup.export()

    override suspend fun importBackup(bytes: ByteArray): RemoteResult {
        // A restore replaces the profiles and their rules: never under a locked video.
        if (lock.current() != null) return error(R.string.remote_backup_locked)
        val report = try {
            // Whole: the tables, then the PIN hash, the grants and the settings, then the feed sync.
            whole { backup.import(bytes) }
        } catch (e: BackupException) {
            return RemoteResult.error(texts.backup(e.problem))
        }
        val restored = texts.get(R.string.backup_restored, texts.profiles(report.profiles), texts.channels(report.subscriptions))
        return RemoteResult.ok(if (report.skipped > 0) texts.get(R.string.backup_restored_skipped, restored, report.skipped.toString()) else restored)
    }

    // ---- v1.1: settings, profile rules, the block list, the stats -------------------------------------------

    override suspend fun settings(): RemoteSettingsPage = settingsOrRefuse().page()

    override suspend fun changeSetting(key: String, value: JsonElement, base: JsonElement?): RemoteSettingsResult =
        settingsOrRefuse().change(key, value, base)

    override suspend fun resetSettings(group: String?): RemoteSettingsResult = settingsOrRefuse().reset(group)

    override suspend fun profileDetails(): List<RemoteProfileDetails> = parentalOrRefuse().details()

    override suspend fun setProfileFlags(profileId: String, hideShorts: Boolean?, hideComments: Boolean?): RemoteResult =
        parentalOrRefuse().setFlags(profileId, hideShorts, hideComments)

    override suspend fun setSchedule(profileId: String, fromMinute: Int?, untilMinute: Int?): RemoteResult =
        parentalOrRefuse().setSchedule(profileId, fromMinute, untilMinute)

    override suspend fun blocklist(): RemoteBlocklist = parentalOrRefuse().blocklist()

    override suspend fun blockKeyword(keyword: String): RemoteResult = parentalOrRefuse().blockKeyword(keyword)

    override suspend fun unblockKeyword(keyword: String): RemoteResult = parentalOrRefuse().unblockKeyword(keyword)

    override suspend fun blockChannel(link: String): RemoteResult = parentalOrRefuse().blockChannel(link)

    override suspend fun unblockChannel(channelId: String): RemoteResult = parentalOrRefuse().unblockChannel(channelId)

    override suspend fun stats(): List<RemoteStats> = parentalOrRefuse().stats()

    private fun settingsOrRefuse() = settings ?: throw RemoteRejection(texts.get(R.string.phone_setting_unknown))

    private fun parentalOrRefuse() = parental ?: throw RemoteRejection(texts.get(R.string.phone_setting_unknown))

    // ---- Helpers ----------------------------------------------------------------------------------------

    /**
     * Runs [write] to its end even when the phone's request goes away meanwhile: the phone closed the page, or
     * the server stopped (the TV's address changed, Wi-Fi dropped, the remote was switched off), which cancels
     * the calls still running after a short grace. Then only the answer is lost, never half a write (a restore
     * without its PIN and settings, a surrender stored for a lock that stays).
     */
    private suspend fun <T> whole(write: suspend () -> T): T = withContext(NonCancellable) { write() }

    private suspend fun currentProfile(): Profile = rules().profile!!

    /** The current profile's rules; loaded at start within moments (a TV that cannot read them answers "try again"). */
    private suspend fun rules(): ContentFilter =
        withTimeoutOrNull(PROFILE_TIMEOUT_MS) { gate.awaitFilter() } ?: throw RemoteRejection(texts.get(R.string.remote_timeout))

    private fun error(@StringRes id: Int): RemoteResult = RemoteResult.error(texts.get(id))

    private fun Profile.remoteKind() = if (isChild) RemoteProfileKind.CHILD else RemoteProfileKind.ADULT

    private fun PlaybackCommand.toPlayerCommand(): PlayerCommand = when (action) {
        PlaybackAction.PLAY -> PlayerCommand.Play
        PlaybackAction.PAUSE -> PlayerCommand.Pause
        PlaybackAction.TOGGLE -> PlayerCommand.Toggle
        PlaybackAction.SEEK_BY -> PlayerCommand.SeekBy(seconds * 1000L)
        PlaybackAction.STOP -> throw IllegalArgumentException("STOP leaves the player: see control()")
    }

    private fun SourceItem.toRemoteItem(): RemoteItem = when (this) {
        is VideoItem -> RemoteItem(
            type = RemoteItemType.VIDEO,
            id = videoId,
            title = title,
            subtitle = channelName,
            thumbnailUrl = Thumbnails.video(videoId, Thumbnails.Size.SMALL),
            durationSeconds = durationSeconds,
            isLive = isLive,
        )
        is ChannelItem -> RemoteItem(
            type = RemoteItemType.CHANNEL,
            id = channelId,
            title = name,
            subtitle = subscriberCount?.let(texts::subscribers),
            thumbnailUrl = avatarUrl,
            durationSeconds = null,
        )
        is PlaylistItem -> RemoteItem(
            type = RemoteItemType.PLAYLIST,
            id = playlistId,
            title = title,
            subtitle = listOfNotNull(channelName, videoCount?.let(texts::videos)).joinToString(" · ").ifEmpty { null },
            thumbnailUrl = thumbnailUrl,
            durationSeconds = null,
        )
    }

    private companion object {
        /** An answer from the main thread (the player) that takes longer than this: the TV is too busy. */
        const val MAIN_TIMEOUT_MS = 2_000L
        const val PROFILE_TIMEOUT_MS = 10_000L
        const val SEARCH_TIMEOUT_MS = 30_000L

        /** Extraction (a few seconds), bringing the app up and opening the player. */
        const val PLAY_TIMEOUT_MS = 45_000L

        /** [control]'s "done" (no string). */
        const val DONE = 0
    }
}

/** Minutes left today (rounded up: 30 s left is "1", not "0", which the page reads as "time up"); null = no limit. */
internal fun SessionState.minutesLeft(): Int? = when (this) {
    is SessionState.Ok -> remainingSeconds?.let { ((it + 59) / 60).toInt() }
    is SessionState.Warning -> ((remainingSeconds + 59) / 60).toInt()
    SessionState.TimeUp, is SessionState.OutsideSchedule -> 0
}

internal fun SessionState.remote(): RemoteSessionState = when (this) {
    is SessionState.Ok -> RemoteSessionState.OK
    is SessionState.Warning -> RemoteSessionState.WARNING
    SessionState.TimeUp -> RemoteSessionState.TIME_UP
    is SessionState.OutsideSchedule -> RemoteSessionState.OUTSIDE_SCHEDULE
}

/** A wait in whole minutes, rounded up (at least 1). */
internal fun Long.ceilMinutes(): Int = ((this + 59_999) / 60_000).coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
