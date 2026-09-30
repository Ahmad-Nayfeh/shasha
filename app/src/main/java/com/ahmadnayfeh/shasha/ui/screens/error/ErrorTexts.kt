package com.ahmadnayfeh.shasha.ui.screens.error

import androidx.annotation.StringRes
import com.ahmadnayfeh.shasha.R
import com.ahmadnayfeh.shasha.youtube.SourceErrorKind

/** Arabic texts for every [SourceErrorKind]; `when` is exhaustive, so a new kind cannot be forgotten. */
object ErrorTexts {
    @StringRes
    fun title(kind: SourceErrorKind): Int =
        if (kind == SourceErrorKind.NETWORK) R.string.error_title_network else R.string.error_title_youtube

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
    }

    /** Two or three words, for status chips (diagnostics). */
    @StringRes
    fun short(kind: SourceErrorKind): Int = when (kind) {
        SourceErrorKind.CONTENT_UNAVAILABLE -> R.string.error_short_content_unavailable
        SourceErrorKind.PRIVATE -> R.string.error_short_private
        SourceErrorKind.AGE_RESTRICTED -> R.string.error_short_age_restricted
        SourceErrorKind.GEO_RESTRICTED -> R.string.error_short_geo_restricted
        SourceErrorKind.PAID -> R.string.error_short_paid
        SourceErrorKind.ACCOUNT_TERMINATED -> R.string.error_short_account_terminated
        SourceErrorKind.BOT_CHECK -> R.string.error_short_bot_check
        SourceErrorKind.BLOCKED_403 -> R.string.error_short_blocked_403
        SourceErrorKind.RATE_LIMITED -> R.string.error_short_rate_limited
        SourceErrorKind.LOW_QUALITY_ONLY -> R.string.error_short_low_quality_only
        SourceErrorKind.NETWORK -> R.string.error_short_network
        SourceErrorKind.EXTRACTION_BROKEN -> R.string.error_short_extraction_broken
    }

    /**
     * Whether a newer app (extractor) may fix it: not for a missing network or YouTube asking to slow down.
     * The unified error screen offers «فحص التحديثات» for every kind; smaller places only for these.
     */
    fun suggestsUpdate(kind: SourceErrorKind): Boolean = kind != SourceErrorKind.NETWORK && kind != SourceErrorKind.RATE_LIMITED

    /** "HTTP 403 · BLOCKED_403": the exception's technical name plus our category, for bug reports. */
    fun technical(kind: SourceErrorKind, technicalName: String): String =
        if (technicalName.isBlank() || technicalName == kind.name) kind.name else "$technicalName · ${kind.name}"
}
