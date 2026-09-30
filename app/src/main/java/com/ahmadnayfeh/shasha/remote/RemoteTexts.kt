package com.ahmadnayfeh.shasha.remote

import android.content.res.Resources
import androidx.annotation.StringRes
import com.ahmadnayfeh.shasha.R
import com.ahmadnayfeh.shasha.backup.BackupProblem
import com.ahmadnayfeh.shasha.data.takeout.TakeoutException
import com.ahmadnayfeh.shasha.policy.DenyReason
import com.ahmadnayfeh.shasha.policy.messageRes
import com.ahmadnayfeh.shasha.ui.format.Formats
import com.ahmadnayfeh.shasha.ui.screens.error.ErrorTexts
import com.ahmadnayfeh.shasha.youtube.SourceErrorKind
import com.ahmadnayfeh.shasha.youtube.SourceException

/**
 * The Arabic sentences the phone shows (strings.xml, Western digits; titles and names arrive plain and the
 * strings put them in «…» with FSI…PDI around them).
 */
class RemoteTexts(private val resources: Resources) {
    private val formats = Formats(resources)

    fun get(@StringRes id: Int, vararg args: Any): String = resources.getString(id, *args)

    fun minutes(n: Int): String = formats.count(R.array.count_minutes, n.toLong())

    /** After «بعد»: «دقيقتين», not «دقيقتان». */
    fun minutesAfter(n: Int): String = formats.count(R.array.count_minutes_after, n.toLong())

    fun channels(n: Int): String = formats.count(R.array.count_channels, n.toLong())

    fun profiles(n: Int): String = formats.count(R.array.count_profiles, n.toLong())

    fun videos(n: Long): String = formats.count(R.array.count_videos, n)

    fun subscribers(n: Long): String = formats.subscribers(n)

    fun deny(reason: DenyReason): String = get(reason.messageRes())

    /** «رمز PIN غير صحيح. بقيت 3 محاولات.» */
    fun wrongPin(attemptsLeft: Int): String =
        if (attemptsLeft <= 0) get(R.string.remote_pin_wrong) else get(R.string.remote_pin_wrong_left, formats.count(R.array.attempts_left, attemptsLeft.toLong()))

    /** What went wrong with YouTube, in one sentence for the phone. */
    fun sourceError(e: SourceException): String = when (e.kind) {
        SourceErrorKind.NETWORK -> get(R.string.remote_offline)
        SourceErrorKind.BOT_CHECK, SourceErrorKind.BLOCKED_403, SourceErrorKind.LOW_QUALITY_ONLY, SourceErrorKind.EXTRACTION_BROKEN ->
            get(R.string.remote_youtube_changed, get(ErrorTexts.explanation(e.kind)))
        else -> get(ErrorTexts.explanation(e.kind))
    }

    fun show(result: ShowResult): String = get(
        when (result) {
            ShowResult.SHOWN -> R.string.remote_play_sent_plain
            ShowResult.SCREEN_OFF -> R.string.remote_screen_off
            ShowResult.NOT_IN_FRONT -> R.string.remote_not_in_front
            ShowResult.NO_PROFILE -> R.string.remote_no_profile
            ShowResult.PROFILE_CHANGED -> R.string.remote_profile_changed
            ShowResult.FAILED -> R.string.remote_show_failed
        },
    )

    fun takeout(reason: TakeoutException.Reason): String = get(
        when (reason) {
            TakeoutException.Reason.EMPTY -> R.string.import_error_empty
            TakeoutException.Reason.TOO_LARGE -> R.string.import_error_too_large
            TakeoutException.Reason.BROKEN_ZIP -> R.string.import_error_broken_zip
            TakeoutException.Reason.NO_CSV -> R.string.import_error_no_csv
        },
    )

    fun backup(problem: BackupProblem): String = get(
        when (problem) {
            BackupProblem.NOT_A_BACKUP -> R.string.backup_error_not_backup
            BackupProblem.NEWER_VERSION -> R.string.backup_error_newer
            BackupProblem.NO_ADULT -> R.string.backup_error_no_adult
            BackupProblem.TOO_LARGE -> R.string.backup_error_too_large
            BackupProblem.FAILED -> R.string.backup_error_failed
        },
    )
}
