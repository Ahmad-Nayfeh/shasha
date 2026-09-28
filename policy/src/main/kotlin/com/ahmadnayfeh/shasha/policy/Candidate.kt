package com.ahmadnayfeh.shasha.policy

/**
 * Anything the app is about to show in a list or play. The app maps its YouTube models to this.
 *
 * @property channelId null when unknown; a CHILD profile then only sees it via an allowlisted playlist.
 * @property durationSeconds null (or ≤ 0) when unknown, e.g. RSS items.
 * @property viaPlaylistId the playlist the item was taken from. Set it ONLY for items of that
 *   playlist's own fetched pages (the playlist screen, auto-advance inside it): the engine trusts
 *   it, and in a child profile it admits videos of any channel. Never fill it from a link's
 *   `list=` parameter; check links with [PolicyEngine.canPlayLink].
 * @property isLive live streams are never considered shorts because of their duration.
 */
data class Candidate(
    val videoId: String,
    val title: String,
    val channelId: String?,
    val durationSeconds: Long?,
    val url: String,
    val isShortFormFlag: Boolean,
    val viaPlaylistId: String? = null,
    val isLive: Boolean = false,
)
