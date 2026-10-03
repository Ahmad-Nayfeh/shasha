package com.ahmadnayfeh.shasha.ui.screens.error

import androidx.annotation.StringRes
import com.ahmadnayfeh.shasha.R
import com.ahmadnayfeh.shasha.youtube.SourceErrorKind

/** Arabic texts for every [SourceErrorKind]; `when` is exhaustive, so a new kind cannot be forgotten. */
object ErrorTexts {
    @StringRes
    fun title(kind: SourceErrorKind): Int = when (kind) {
        SourceErrorKind.NETWORK -> R.string.error_title_network
        // Nothing changed at YouTube: the title must not say so.
        SourceErrorKind.DEVICE_DECODER -> R.string.error_title_device_decoder
        else -> R.string.error_title_youtube
    }

    @StringRes
    fun explanation(kind: SourceErrorKind): Int = when (kind) {
        SourceErrorKind.CONTENT_UNAVAILABLE -> R.string.error_explain_content_unavailable
        SourceErrorKind.PRIVATE -> R.string.error_explain_private
        SourceErrorKind.AGE_RESTRICTED -> R.string.error_explain_age_restricted
        SourceErrorKind.GEO_RESTRICTED -> R.string.error_explain_geo_restricted
        SourceErrorKind.PAID -> R.string.error_explain_paid
        SourceErrorKind.ACCOUNT_TERMINATED -> R.string.error_explain_account_terminated
        SourceErrorKind.BOT_CHECK -> R.string.error_explain_bot_check
        SourceErrorKind.BLOCKED_403 -> R.string.error_explain_blocked_403
        SourceErrorKind.RATE_LIMITED -> R.string.error_explain_rate_limited
        SourceErrorKind.LOW_QUALITY_ONLY -> R.string.error_explain_low_quality_only
        SourceErrorKind.NETWORK -> R.string.error_explain_network
        SourceErrorKind.EXTRACTION_BROKEN -> R.string.error_explain_extraction_broken
        SourceErrorKind.DEVICE_DECODER -> R.string.error_explain_device_decoder
    }

    /**
     * Whether a newer app (extractor) may fix it: not for a missing network, YouTube asking to slow down, or this TV's own
     * decoder failing. The unified error screen offers «فحص التحديثات» for every kind but the last ([offersUpdateCheck]);
     * smaller places only for these.
     */
    fun suggestsUpdate(kind: SourceErrorKind): Boolean =
        kind != SourceErrorKind.NETWORK && kind != SourceErrorKind.RATE_LIMITED && kind != SourceErrorKind.DEVICE_DECODER

    /**
     * Whether the unified error screen offers «فحص التحديثات»: the way out of every YouTube breakage (SPEC §6), and of a
     * missing network too, where it is the only place that says which version this is. Not when the TV's decoder failed: an
     * update does not touch it, so the screen's main action is another try.
     */
    fun offersUpdateCheck(kind: SourceErrorKind): Boolean = kind != SourceErrorKind.DEVICE_DECODER

    /** "HTTP 403 · BLOCKED_403": the exception's technical name plus our category, for bug reports. */
    fun technical(kind: SourceErrorKind, technicalName: String): String =
        if (technicalName.isBlank() || technicalName == kind.name) kind.name else "$technicalName · ${kind.name}"
}
