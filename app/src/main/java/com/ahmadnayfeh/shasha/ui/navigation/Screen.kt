package com.ahmadnayfeh.shasha.ui.navigation

import kotlinx.serialization.Serializable

/**
 * Type-safe Navigation Compose routes. Each profile kind registers only its own ([profileGraph]): a
 * child's graph has none of the adult-only destinations.
 */
sealed interface Screen {
    /** The rail and the profile's tabs ([com.ahmadnayfeh.shasha.ui.main.Tab.Adult] or Kids). */
    @Serializable
    data object Main : Screen

    /** [name] shows while the channel loads (it is known from the card that was opened). */
    @Serializable
    data class Channel(val channelId: String, val name: String? = null) : Screen

    @Serializable
    data class Playlist(val playlistId: String) : Screen

    /**
     * Inside a playlist, [playlistIndex] is the video's position: the next one plays when it ends.
     * [fromLink]: the video arrived as a link (the phone, another app); the policy then ignores any
     * playlist it names and asks for the video's own channel. [diagnostic]: one of تشخيص's fixed test
     * videos (adult only): it is not locked (SPEC §4's lock is about watching, not about checking that
     * playback works; the live test never ends and would only leave through a surrender).
     * [startSeconds]: where a shared link asks the video to start (its `t=`), in place of the saved position.
     */
    @Serializable
    data class Player(
        val videoId: String,
        val playlistId: String? = null,
        val playlistIndex: Int = -1,
        val fromLink: Boolean = false,
        val diagnostic: Boolean = false,
        val startSeconds: Long? = null,
    ) : Screen

    /** Placeholder until the GitHub Releases updater arrives (SPEC phase 7). */
    @Serializable
    data object Updates : Screen

    // ---- Adult profile only ---------------------------------------------------------------------

    /** Fixed test videos ("فيديوهات تجريبية"), under Settings → تشخيص. */
    @Serializable
    data object Diagnostics : Screen

    /** Settings → استيراد الاشتراكات. */
    @Serializable
    data object ImportSubscriptions : Screen

    /** Settings → الملفات (behind the parent PIN). */
    @Serializable
    data object Profiles : Screen

    @Serializable
    data class ProfileEdit(val profileId: String) : Screen

    /** A child's allowlist (from the profile's page). */
    @Serializable
    data class Allowlist(val profileId: String) : Screen

    /** Settings → قائمة الحظر (behind the parent PIN). */
    @Serializable
    data object Blocklist : Screen

    /** Settings → تغيير رمز PIN (asks for the current one first). */
    @Serializable
    data object ChangePin : Screen

    /** Settings → الإحصائيات (behind the parent PIN). */
    @Serializable
    data object Stats : Screen

    /** Settings → حالة الحماية (SPEC §4): the guard's permissions and its measured returns. */
    @Serializable
    data object Protection : Screen

    /** Settings → التحكم من الجوال (SPEC §5, behind the parent PIN): the pairing QR code, on/off, a new token. */
    @Serializable
    data object RemoteControl : Screen

    /** Settings → الاستيراد والتصدير (behind the parent PIN): the manual JSON backup. */
    @Serializable
    data object Backup : Screen
}
