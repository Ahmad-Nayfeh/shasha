package com.ahmadnayfeh.shasha.ui.screens.diagnostics

import androidx.annotation.StringRes
import com.ahmadnayfeh.shasha.R
import com.ahmadnayfeh.shasha.youtube.SourceException
import com.ahmadnayfeh.shasha.youtube.StreamDetails

/** The fixed test videos: each one exercises a case that breaks differently when YouTube changes. */
enum class TestVideo(val videoId: String, @StringRes val label: Int, @StringRes val about: Int) {
    NORMAL("dQw4w9WgXcQ", R.string.diag_case_normal, R.string.diag_about_normal),
    LIVE("nI725iVsyoQ", R.string.diag_case_live, R.string.diag_about_live),
    KIDS("it3OtbTxQk0", R.string.diag_case_kids, R.string.diag_about_kids),
    DELETED("_PL2HJKxnOM", R.string.diag_case_deleted, R.string.diag_about_deleted),
    SPONSOR_BLOCK("lC3h3Lm3q54", R.string.diag_case_sponsorblock, R.string.diag_about_sponsorblock),
}

/** What the screen shows about a checked stream (plain data, so the screen can use fakes). */
data class StreamSummary(
    val title: String,
    val channelName: String?,
    val thumbnailUrl: String?,
    val durationSeconds: Long?,
    val isLive: Boolean,
    val bestHeight: Int?,
    val subtitleCount: Int,
) {
    companion object {
        fun of(details: StreamDetails) = StreamSummary(
            title = details.video.title,
            channelName = details.video.channelName,
            thumbnailUrl = details.video.thumbnailUrl,
            durationSeconds = details.video.durationSeconds,
            isLive = details.video.isLive,
            bestHeight = details.videoTracks.maxOfOrNull { it.height },
            subtitleCount = details.subtitles.distinctBy { it.languageCode }.size,
        )
    }
}

sealed interface CheckStatus {
    data object Waiting : CheckStatus
    data object Checking : CheckStatus
    data class Ready(val summary: StreamSummary, val elapsedMs: Long) : CheckStatus
    data class Failed(val error: SourceException, val elapsedMs: Long) : CheckStatus
}

data class DiagnosticItem(val video: TestVideo, val status: CheckStatus)
