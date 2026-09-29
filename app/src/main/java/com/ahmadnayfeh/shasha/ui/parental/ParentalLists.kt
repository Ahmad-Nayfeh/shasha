package com.ahmadnayfeh.shasha.ui.parental

import com.ahmadnayfeh.shasha.data.ProfileRepository
import com.ahmadnayfeh.shasha.policy.Blocklist
import com.ahmadnayfeh.shasha.policy.ListedChannel
import com.ahmadnayfeh.shasha.policy.ListedPlaylist
import com.ahmadnayfeh.shasha.policy.Profile
import com.ahmadnayfeh.shasha.policy.TextNormalizer

/**
 * The parent's list edits (SPEC §3): each child's allowlist, and the block list. Every change is one
 * [ProfileRepository.update] per profile (read, change and write in one transaction, so an edit from the
 * phone at the same moment is not lost), made with the :policy list operations.
 *
 * The block list is the household's: the engine keeps one per profile, and a block made here applies to
 * every profile (a channel the parent blocks is not for anyone in the house). The screens show the union.
 */
class ParentalLists(private val profiles: ProfileRepository) {

    /** Adds (or renames) a channel in [profileId]'s allowlist. Null when the profile is gone. */
    suspend fun allowChannel(profileId: String, channel: ListedChannel): Profile? =
        profiles.update(profileId) { it.copy(allowlist = it.allowlist.withChannel(channel)) }

    suspend fun allowPlaylist(profileId: String, playlist: ListedPlaylist): Profile? =
        profiles.update(profileId) { it.copy(allowlist = it.allowlist.withPlaylist(playlist)) }

    /** Removes a channel or a playlist (by id) from [profileId]'s allowlist. */
    suspend fun disallow(profileId: String, id: String): Profile? =
        profiles.update(profileId) { it.copy(allowlist = it.allowlist.without(id)) }

    suspend fun blockChannel(channel: ListedChannel) = everyProfile { it.withChannel(channel) }

    suspend fun unblockChannel(channelId: String) = everyProfile { it.withoutChannel(channelId) }

    /** False when [keyword] is blank or already blocked (spelling variants count as the same word). */
    suspend fun addKeyword(keyword: String): Boolean {
        val normalized = TextNormalizer.normalize(keyword)
        if (normalized.isEmpty()) return false
        if (householdBlocklist(profiles.all()).titleKeywords.any { TextNormalizer.normalize(it) == normalized }) return false
        everyProfile { it.withKeyword(keyword) }
        return true
    }

    suspend fun removeKeyword(keyword: String) = everyProfile { it.withoutKeyword(keyword) }

    private suspend fun everyProfile(change: (Blocklist) -> Blocklist) {
        profiles.all().forEach { profile -> profiles.update(profile.id) { it.copy(blocklist = change(it.blocklist)) } }
    }

    companion object {
        /**
         * What the block list screen shows: every profile's blocked channels and keywords, once each (they
         * are the same everywhere unless a profile was restored from an older backup).
         */
        fun householdBlocklist(all: List<Profile>): Blocklist {
            val channels = all.flatMap { it.blocklist.channels }.distinctBy { it.channelId }
            val keywords = all.flatMap { it.blocklist.titleKeywords }.distinctBy { TextNormalizer.normalize(it) }
            return Blocklist(channels = channels, titleKeywords = keywords)
        }
    }
}
