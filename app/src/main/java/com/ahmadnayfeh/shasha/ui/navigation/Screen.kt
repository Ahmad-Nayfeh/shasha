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

    /**
     * [ownerChannelId]: the channel whose page listed the playlist (its Playlists section): a child who may open that channel
     * may open its playlists ([com.ahmadnayfeh.shasha.policy.ContentFilter.playlist]); null from anywhere else.
     */
    @Serializable
    data class Playlist(val playlistId: String, val ownerChannelId: String? = null) : Screen

    /**
     * Inside a playlist, [playlistIndex] is the video's position: the next one plays when it ends.
     * [fromLink]: the video arrived as a link (the phone, another app); the policy then ignores any
     * playlist it names and asks for the video's own channel.
     * [startSeconds]: where a shared link asks the video to start (its `t=`), in place of the saved position.
     * [phoneApproved] (v1.1.1): a request from the phone that was accepted on the TV; the phone that sent it is there,
     * so this video needs no «تأكيد» from the phone before it starts.
     */
    @Serializable
    data class Player(
        val videoId: String,
        val playlistId: String? = null,
        val playlistIndex: Int = -1,
        val fromLink: Boolean = false,
        val startSeconds: Long? = null,
        val phoneApproved: Boolean = false,
    ) : Screen

    /**
     * The Shorts player: [videoId] plays first, and UP and DOWN go through the list it was chosen from ([queue]: its key in
     * [com.ahmadnayfeh.shasha.player.ShortsQueues]; a list that is gone leaves the one Short). [fromLink] and [phoneApproved] are
     * [Player]'s: a Short that came as a link, and one the TV accepted from the phone.
     */
    @Serializable
    data class Shorts(
        val videoId: String,
        val queue: String? = null,
        val fromLink: Boolean = false,
        val phoneApproved: Boolean = false,
    ) : Screen

    /**
     * «فحص التحديثات» (SPEC §6): checks at once and, when a newer version is out, downloads and installs it. Reached
     * from Settings and from the error screens; a child's graph has it too, but shows only who can update the app.
     */
    @Serializable
    data object Updates : Screen

    // ---- Adult profile only ---------------------------------------------------------------------

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

    /** Settings → الدليل والفيديو → «دليل شاشة»: its pages, in the app's language. */
    @Serializable
    data object Guide : Screen

    /** Settings → الدليل والفيديو → the explainer video, in the app's language. */
    @Serializable
    data object Explainer : Screen
}
