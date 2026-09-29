package com.ahmadnayfeh.shasha.sponsorblock

import androidx.annotation.StringRes
import com.ahmadnayfeh.shasha.R

/**
 * The SponsorBlock categories this app can skip (https://wiki.sponsor.ajay.app/w/Segment_Categories), with
 * their API keys. SPEC §1: selectable, with sponsor, selfpromo, interaction, intro and outro on by default.
 * Highlights (poi), whole-video labels (full) and chapters are not segments to skip: never asked for.
 */
enum class SponsorCategory(
    val apiKey: String,
    /** «تم تخطي: …» and the settings row's name. */
    @StringRes val label: Int,
    /** One line under the name in the settings. */
    @StringRes val description: Int,
    val enabledByDefault: Boolean,
) {
    SPONSOR("sponsor", R.string.sponsor_category_sponsor, R.string.sponsor_category_sponsor_body, true),
    SELF_PROMO("selfpromo", R.string.sponsor_category_selfpromo, R.string.sponsor_category_selfpromo_body, true),
    INTERACTION("interaction", R.string.sponsor_category_interaction, R.string.sponsor_category_interaction_body, true),
    INTRO("intro", R.string.sponsor_category_intro, R.string.sponsor_category_intro_body, true),
    OUTRO("outro", R.string.sponsor_category_outro, R.string.sponsor_category_outro_body, true),
    PREVIEW("preview", R.string.sponsor_category_preview, R.string.sponsor_category_preview_body, false),
    HOOK("hook", R.string.sponsor_category_hook, R.string.sponsor_category_hook_body, false),
    FILLER("filler", R.string.sponsor_category_filler, R.string.sponsor_category_filler_body, false),
    MUSIC_OFFTOPIC("music_offtopic", R.string.sponsor_category_music_offtopic, R.string.sponsor_category_music_offtopic_body, false),
    ;

    companion object {
        /** SPEC §1's default selection. */
        val DEFAULTS: Set<SponsorCategory> = entries.filterTo(LinkedHashSet()) { it.enabledByDefault }

        private val byKey = entries.associateBy { it.apiKey }

        /** Null for a key this app does not skip (a category SponsorBlock added later, "chapter", …). */
        fun fromApiKey(key: String): SponsorCategory? = byKey[key]
    }
}

/** What SponsorBlock says to do with a segment. Only these two are asked for (actionTypes). */
enum class SegmentAction(val apiKey: String) {
    /** Jump over it. */
    SKIP("skip"),

    /** Keep the picture, silence the sound (a sponsor read over the content). */
    MUTE("mute"),
    ;

    companion object {
        fun fromApiKey(key: String): SegmentAction? = entries.firstOrNull { it.apiKey == key }
    }
}

/**
 * One segment of one video, in milliseconds: playback inside `[startMs, endMs)` is the segment.
 * [id] is SponsorBlock's UUID (what "already skipped" and "taken back" remember).
 */
data class SponsorSegment(
    val id: String,
    val category: SponsorCategory,
    val action: SegmentAction,
    val startMs: Long,
    val endMs: Long,
    /** The video's length when the segment was submitted; 0 when SponsorBlock does not know it. */
    val videoDurationMs: Long = 0,
) {
    val lengthMs: Long get() = endMs - startMs

    operator fun contains(positionMs: Long): Boolean = positionMs >= startMs && positionMs < endMs
}
