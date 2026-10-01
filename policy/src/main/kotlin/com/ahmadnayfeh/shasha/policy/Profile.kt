package com.ahmadnayfeh.shasha.policy

import kotlinx.serialization.Serializable
import java.util.UUID

enum class ProfileKind { ADULT, CHILD }

/**
 * A viewer profile. There is one ADULT profile (the owner, entered with the parent PIN) and any
 * number of CHILD profiles (entered freely, left only with the parent PIN).
 *
 * [allowlist] is only consulted for CHILD profiles; [blocklist] applies to everyone.
 * Comments are always hidden for CHILD profiles regardless of [hideComments].
 */
@Serializable
data class Profile(
    val id: String,
    val name: String,
    val kind: ProfileKind,
    val avatar: String,
    val dailyQuotaMinutes: Int? = null,
    val schedule: Schedule? = null,
    val hideShorts: Boolean = false,
    val hideComments: Boolean = false,
    val allowlist: Allowlist = Allowlist(),
    val blocklist: Blocklist = Blocklist(),
) {
    init {
        require(dailyQuotaMinutes == null || dailyQuotaMinutes in 0..MINUTES_PER_DAY) {
            "dailyQuotaMinutes must be null or in 0..$MINUTES_PER_DAY, was $dailyQuotaMinutes"
        }
    }

    val isChild: Boolean get() = kind == ProfileKind.CHILD
    val isAdult: Boolean get() = kind == ProfileKind.ADULT

    companion object {
        const val DEFAULT_ADULT_AVATAR = "person"
        const val DEFAULT_CHILD_AVATAR = "star"
        const val DEFAULT_CHILD_QUOTA_MINUTES = 60

        /** 07:00 to 22:00, as asked in SPEC section 3 ("ممنوع بعد 10 مساءً وقبل 7 صباحاً"). */
        val DEFAULT_CHILD_SCHEDULE = Schedule(allowedFromMinute = 7 * 60, allowedUntilMinute = 22 * 60)

        fun newAdult(
            name: String,
            id: String = UUID.randomUUID().toString(),
            avatar: String = DEFAULT_ADULT_AVATAR,
        ) = Profile(
            id = id,
            name = name,
            kind = ProfileKind.ADULT,
            avatar = avatar,
            dailyQuotaMinutes = null,
            schedule = null,
            hideShorts = false,
            hideComments = false,
        )

        fun newChild(
            name: String,
            id: String = UUID.randomUUID().toString(),
            avatar: String = DEFAULT_CHILD_AVATAR,
        ) = Profile(
            id = id,
            name = name,
            kind = ProfileKind.CHILD,
            avatar = avatar,
            dailyQuotaMinutes = DEFAULT_CHILD_QUOTA_MINUTES,
            schedule = DEFAULT_CHILD_SCHEDULE,
            hideShorts = true,
            hideComments = true,
        )
    }
}

internal const val MINUTES_PER_DAY = 24 * 60

/**
 * Daily window in which watching is allowed, in minutes since local midnight: [allowedFromMinute]
 * inclusive to [allowedUntilMinute] exclusive. The window may wrap midnight (e.g. 20:00 to 06:00).
 * Equal bounds (or 0 to 1440) mean "always allowed". [allowedUntilMinute] may be 1440 (midnight).
 */
@Serializable
data class Schedule(val allowedFromMinute: Int, val allowedUntilMinute: Int) {
    init {
        require(allowedFromMinute in 0 until MINUTES_PER_DAY) { "allowedFromMinute out of range: $allowedFromMinute" }
        require(allowedUntilMinute in 0..MINUTES_PER_DAY) { "allowedUntilMinute out of range: $allowedUntilMinute" }
    }

    val isAlwaysAllowed: Boolean
        get() = allowedFromMinute == allowedUntilMinute ||
            (allowedFromMinute == 0 && allowedUntilMinute == MINUTES_PER_DAY)

    val wrapsMidnight: Boolean get() = allowedFromMinute > allowedUntilMinute

    /** [minuteOfDay] is 0..1439. */
    fun allows(minuteOfDay: Int): Boolean = when {
        isAlwaysAllowed -> true
        wrapsMidnight -> minuteOfDay >= allowedFromMinute || minuteOfDay < allowedUntilMinute
        else -> minuteOfDay >= allowedFromMinute && minuteOfDay < allowedUntilMinute
    }

    companion object {
        fun of(fromHour: Int, fromMinute: Int, untilHour: Int, untilMinute: Int) =
            Schedule(fromHour * 60 + fromMinute, untilHour * 60 + untilMinute)

        /**
         * A window picked as two clock times (0..1439 each, the TV's picker and the phone's): the same time twice is no
         * window at all (all day, null), and "until 0:00" is the midnight that ends the day.
         */
        fun fromPicker(fromMinute: Int, untilMinute: Int): Schedule? =
            if (fromMinute == untilMinute) null else Schedule(fromMinute, if (untilMinute == 0) MINUTES_PER_DAY else untilMinute)
    }
}

@Serializable
data class ListedChannel(val channelId: String, val name: String)

@Serializable
data class ListedPlaylist(val playlistId: String, val title: String)

/** What a CHILD profile may see: these channels, plus videos reached through these playlists. */
@Serializable
data class Allowlist(
    val channels: List<ListedChannel> = emptyList(),
    val playlists: List<ListedPlaylist> = emptyList(),
) {
    fun hasChannel(channelId: String?): Boolean = channelId != null && channels.any { it.channelId == channelId }
    fun hasPlaylist(playlistId: String?): Boolean = playlistId != null && playlists.any { it.playlistId == playlistId }

    /** Adds or renames the channel; the list never holds the same id twice. */
    fun withChannel(channel: ListedChannel) = copy(channels = channels.filterNot { it.channelId == channel.channelId } + channel)
    fun withPlaylist(playlist: ListedPlaylist) = copy(playlists = playlists.filterNot { it.playlistId == playlist.playlistId } + playlist)

    /** Removes a channel or playlist by id. */
    fun without(id: String) = copy(
        channels = channels.filterNot { it.channelId == id },
        playlists = playlists.filterNot { it.playlistId == id },
    )
}

/** Applies to every profile: blocked channels and title keywords (see [TextNormalizer]). */
@Serializable
data class Blocklist(
    val channels: List<ListedChannel> = emptyList(),
    val titleKeywords: List<String> = emptyList(),
) {
    fun hasChannel(channelId: String?): Boolean = channelId != null && channels.any { it.channelId == channelId }

    fun withChannel(channel: ListedChannel) = copy(channels = channels.filterNot { it.channelId == channel.channelId } + channel)
    fun withoutChannel(channelId: String) = copy(channels = channels.filterNot { it.channelId == channelId })

    /** Adds a keyword unless it is blank or already present (after normalization). */
    fun withKeyword(keyword: String): Blocklist {
        val normalized = TextNormalizer.normalize(keyword)
        if (normalized.isEmpty() || titleKeywords.any { TextNormalizer.normalize(it) == normalized }) return this
        return copy(titleKeywords = titleKeywords + keyword.trim())
    }

    fun withoutKeyword(keyword: String): Blocklist {
        val normalized = TextNormalizer.normalize(keyword)
        return copy(titleKeywords = titleKeywords.filterNot { TextNormalizer.normalize(it) == normalized })
    }
}
