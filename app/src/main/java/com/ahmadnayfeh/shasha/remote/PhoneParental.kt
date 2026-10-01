package com.ahmadnayfeh.shasha.remote

import android.content.res.Resources
import androidx.annotation.StringRes
import com.ahmadnayfeh.shasha.R
import com.ahmadnayfeh.shasha.data.DeleteProfileResult
import com.ahmadnayfeh.shasha.data.ProfileRepository
import com.ahmadnayfeh.shasha.data.UsageRepository
import com.ahmadnayfeh.shasha.policy.ListedChannel
import com.ahmadnayfeh.shasha.policy.PolicyEngine
import com.ahmadnayfeh.shasha.policy.Profile
import com.ahmadnayfeh.shasha.policy.Schedule
import com.ahmadnayfeh.shasha.policy.TextNormalizer
import com.ahmadnayfeh.shasha.policy.UsageSummary
import com.ahmadnayfeh.shasha.ui.components.Avatars
import com.ahmadnayfeh.shasha.ui.format.Formats
import com.ahmadnayfeh.shasha.ui.format.TimeTexts
import com.ahmadnayfeh.shasha.ui.parental.ParentalLists
import com.ahmadnayfeh.shasha.ui.screens.settings.PROFILE_NAME_MAX
import com.ahmadnayfeh.shasha.ui.screens.settings.QuotaOptions
import com.ahmadnayfeh.shasha.youtube.SourceException
import com.ahmadnayfeh.shasha.youtube.VideoSource
import com.ahmadnayfeh.shasha.youtube.YouTubeLink
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.time.ZoneId

/**
 * The parent's profiles, household block list and stats on the phone (v1.1), through the TV's own doors: one
 * [ProfileRepository.update] per edit (the TV's screens follow at once), [ParentalLists] for the block list (it applies
 * to every profile, as on the TV), [Schedule.fromPicker] for the time window, [UsageRepository.summary] for the stats.
 * The TV's rules stay the TV's: a child never gets comments, whatever the phone sends; a new profile is a child with the
 * defaults of the TV's «إضافة ملف طفل»; names, avatars and quotas come from the TV's own pickers ([page]); the last
 * adult profile is never deleted, and neither is the profile on the TV now (it would be pulled out from under the
 * person watching: the TV switches first).
 */
class PhoneParental(
    private val profiles: ProfileRepository,
    private val lists: ParentalLists,
    private val usage: UsageRepository,
    private val engine: PolicyEngine,
    private val zone: () -> ZoneId,
    private val source: VideoSource,
    private val resources: Resources,
    private val errors: (SourceException) -> String,
    private val currentId: suspend () -> String,
) {
    private val times = TimeTexts(resources, Formats(resources))

    /** Every profile, and the TV's own choices for them: its quota list, its avatars, its longest name. */
    suspend fun page(): RemoteProfilesPage = RemoteProfilesPage(
        profiles = details(),
        quotaChoices = QuotaOptions,
        avatars = Avatars.keys,
        childAvatars = Avatars.childKeys,
        nameMaxLength = PROFILE_NAME_MAX,
    )

    suspend fun details(): List<RemoteProfileDetails> {
        val today = engine.today()
        val current = currentId()
        val all = profiles.all()
        val adults = all.count { it.isAdult }
        return all.map { p ->
            RemoteProfileDetails(
                id = p.id,
                name = p.name,
                kind = if (p.isChild) RemoteProfileKind.CHILD else RemoteProfileKind.ADULT,
                avatar = p.avatar,
                isCurrent = p.id == current,
                dailyQuotaMinutes = p.dailyQuotaMinutes,
                scheduleFrom = p.schedule?.takeUnless { it.isAlwaysAllowed }?.allowedFromMinute,
                scheduleUntil = p.schedule?.takeUnless { it.isAlwaysAllowed }?.allowedUntilMinute?.rem(MINUTES_PER_DAY),
                hideShorts = p.hideShorts,
                hideComments = p.hideComments || p.isChild,
                usedTodayMinutes = (usage.foregroundSeconds(p.id, today) / 60).toInt(),
                deleteBlockedReason = when {
                    p.id == current -> get(R.string.phone_profile_delete_current)
                    p.isAdult && adults <= 1 -> get(R.string.phone_profile_delete_last_adult)
                    else -> null
                },
            )
        }
    }

    /** The TV's «إضافة ملف طفل»: 1 hour a day, 07:00–22:00, shorts and comments hidden, the household's blocks. */
    suspend fun createChild(name: String, avatar: String): RemoteResult {
        val clean = cleanName(name) ?: return RemoteResult.error(get(R.string.phone_profile_name_invalid, PROFILE_NAME_MAX.toString()))
        if (avatar !in Avatars.childKeys) return error(R.string.phone_profile_avatar_invalid)
        whole {
            val child = Profile.newChild(clean, avatar = avatar).copy(blocklist = ParentalLists.householdBlocklist(profiles.all()))
            profiles.upsert(child)
        }
        return RemoteResult.ok(get(R.string.phone_profile_created, clean))
    }

    /** A new name and/or avatar (null keeps it), checked as the TV's dialogs check them. */
    suspend fun editProfile(profileId: String, name: String?, avatar: String?): RemoteResult {
        val profile = profiles.get(profileId) ?: return error(R.string.phone_profile_not_found)
        val clean = name?.let { cleanName(it) ?: return RemoteResult.error(get(R.string.phone_profile_name_invalid, PROFILE_NAME_MAX.toString())) }
        if (avatar != null && avatar !in avatarsOf(profile)) return error(R.string.phone_profile_avatar_invalid)
        val updated = whole {
            profiles.update(profileId) { p ->
                // The kind may have changed meanwhile (a restore): an adult's avatar never goes to a child.
                p.copy(name = clean ?: p.name, avatar = avatar?.takeIf { it in avatarsOf(p) } ?: p.avatar)
            }
        } ?: return error(R.string.phone_profile_not_found)
        return RemoteResult.ok(get(R.string.phone_profile_saved, updated.name))
    }

    /** Never the last adult ([ProfileRepository.delete] refuses it too), never the profile on the TV now. */
    suspend fun deleteProfile(profileId: String): RemoteResult {
        val profile = profiles.get(profileId) ?: return error(R.string.phone_profile_not_found)
        if (profile.id == currentId()) return error(R.string.phone_profile_delete_current)
        return when (whole { profiles.delete(profileId) }) {
            DeleteProfileResult.Deleted -> RemoteResult.ok(get(R.string.phone_profile_deleted, profile.name))
            DeleteProfileResult.LastAdult -> error(R.string.phone_profile_delete_last_adult)
            DeleteProfileResult.NotFound -> error(R.string.phone_profile_not_found)
        }
    }

    private fun avatarsOf(profile: Profile): List<String> = if (profile.isChild) Avatars.childKeys else Avatars.keys

    /** Spaces folded and trimmed; null when nothing is left or it is longer than the TV takes. */
    private fun cleanName(name: String): String? =
        name.replace(Regex("\\s+"), " ").trim().takeIf { it.isNotEmpty() && it.length <= PROFILE_NAME_MAX }

    suspend fun setFlags(profileId: String, hideShorts: Boolean?, hideComments: Boolean?): RemoteResult {
        val profile = profiles.get(profileId) ?: return error(R.string.phone_profile_not_found)
        if (hideComments != null && profile.isChild && !hideComments) return error(R.string.phone_comments_child)
        val updated = whole {
            profiles.update(profileId) {
                it.copy(
                    hideShorts = hideShorts ?: it.hideShorts,
                    hideComments = if (it.isChild) it.hideComments else hideComments ?: it.hideComments,
                )
            }
        } ?: return error(R.string.phone_profile_not_found)
        return RemoteResult.ok(get(R.string.phone_flags_saved, updated.name))
    }

    suspend fun setSchedule(profileId: String, fromMinute: Int?, untilMinute: Int?): RemoteResult {
        val schedule = if (fromMinute == null || untilMinute == null) null else Schedule.fromPicker(fromMinute, untilMinute)
        val updated = whole { profiles.update(profileId) { it.copy(schedule = schedule) } } ?: return error(R.string.phone_profile_not_found)
        return RemoteResult.ok(
            if (schedule == null) {
                get(R.string.phone_schedule_all_day, updated.name)
            } else {
                get(R.string.phone_schedule_saved, updated.name, times.time(schedule.allowedFromMinute), times.time(schedule.allowedUntilMinute))
            },
        )
    }

    suspend fun blocklist(): RemoteBlocklist {
        val household = ParentalLists.householdBlocklist(profiles.all())
        return RemoteBlocklist(household.channels.map { RemoteListEntry(it.channelId, it.name) }, household.titleKeywords)
    }

    suspend fun blockKeyword(keyword: String): RemoteResult {
        val word = keyword.trim()
        return if (whole { lists.addKeyword(word) }) RemoteResult.ok(get(R.string.phone_keyword_blocked, word)) else RemoteResult.ok(get(R.string.phone_keyword_exists, word))
    }

    suspend fun unblockKeyword(keyword: String): RemoteResult {
        val normalized = TextNormalizer.normalize(keyword)
        val there = blocklist().keywords.firstOrNull { TextNormalizer.normalize(it) == normalized } ?: return error(R.string.phone_keyword_not_there)
        whole { lists.removeKeyword(there) }
        return RemoteResult.ok(get(R.string.phone_keyword_unblocked, there))
    }

    suspend fun blockChannel(link: String): RemoteResult {
        val parsed = source.parseLink(link) as? YouTubeLink.Channel ?: return error(R.string.phone_not_a_channel)
        return try {
            val channelId = source.resolveChannelId(parsed)
            val name = source.channel(channelId).channel.name.trim().ifEmpty { channelId }
            whole { lists.blockChannel(ListedChannel(channelId, name)) }
            RemoteResult.ok(get(R.string.phone_channel_blocked, name))
        } catch (e: SourceException) {
            RemoteResult.error(errors(e))
        }
    }

    suspend fun unblockChannel(channelId: String): RemoteResult {
        val there = blocklist().channels.firstOrNull { it.id == channelId } ?: return error(R.string.phone_channel_not_there)
        whole { lists.unblockChannel(channelId) }
        return RemoteResult.ok(get(R.string.phone_channel_unblocked, there.title))
    }

    suspend fun stats(): List<RemoteStats> {
        val today = engine.today()
        return profiles.all().map { p -> p.stats(usage.summary(p.id, today, zone())) }
    }

    private fun Profile.stats(summary: UsageSummary) = RemoteStats(
        profileId = id,
        name = name,
        kind = if (isChild) RemoteProfileKind.CHILD else RemoteProfileKind.ADULT,
        days = summary.dailyMinutes.map { it.toInt() },
        thisWeekMinutes = summary.thisWeekMinutes.toInt(),
        previousWeekMinutes = summary.previousWeekMinutes.toInt(),
        changePercent = summary.changePercent,
        topChannels = summary.topChannels.map { RemoteChannelUsage(it.channelName ?: it.channelId, it.minutes.toInt()) },
        surrenders = summary.surrendersLast7Days,
    )

    private suspend fun <T> whole(write: suspend () -> T): T = withContext(NonCancellable) { write() }

    private fun get(@StringRes id: Int, vararg args: Any): String = resources.getString(id, *args)

    private fun error(@StringRes id: Int) = RemoteResult.error(get(id))

    private companion object {
        const val MINUTES_PER_DAY = 24 * 60
    }
}
