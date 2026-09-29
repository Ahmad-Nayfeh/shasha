package com.ahmadnayfeh.shasha.ui.screens.home

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ahmadnayfeh.shasha.R
import com.ahmadnayfeh.shasha.ui.components.ContentFocus
import com.ahmadnayfeh.shasha.ui.components.FocusArea
import com.ahmadnayfeh.shasha.ui.components.ShashaButton
import com.ahmadnayfeh.shasha.ui.components.ShashaIcons
import com.ahmadnayfeh.shasha.ui.components.ShashaLoader
import com.ahmadnayfeh.shasha.ui.format.Formats
import com.ahmadnayfeh.shasha.ui.screens.error.CheckUpdatesButton
import com.ahmadnayfeh.shasha.ui.screens.error.ErrorIllustration
import com.ahmadnayfeh.shasha.ui.screens.error.ErrorLayout
import com.ahmadnayfeh.shasha.ui.screens.error.ErrorTexts
import com.ahmadnayfeh.shasha.ui.theme.ShashaColors
import com.ahmadnayfeh.shasha.youtube.SourceErrorKind
import com.ahmadnayfeh.shasha.youtube.SourceException

/** The header's status: updating, when the feed last updated, and how many channels failed to. */
@Composable
internal fun SyncStatus(sync: HomeSync, nowMs: Long, formats: Formats) {
    val health = sync.health
    if (health == null && !sync.running) return // not read yet: a frame or two
    val lastAttempt = health?.lastAttemptAtMs
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (sync.running) {
            ShashaLoader(size = 22.dp)
            Spacer(Modifier.width(10.dp))
        }
        val text = when {
            sync.running -> stringResource(R.string.home_syncing)
            lastAttempt != null -> stringResource(R.string.home_last_sync, formats.ago(lastAttempt, nowMs))
            else -> stringResource(R.string.home_never_synced)
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = ShashaColors.Muted, modifier = Modifier.testTag("home_sync_status"))
        if (!sync.running && health != null && health.failed > 0) {
            Text(" · ", style = MaterialTheme.typography.labelLarge, color = ShashaColors.Muted)
            Text(
                stringResource(R.string.home_sync_failures, health.failed.toString()),
                style = MaterialTheme.typography.labelLarge,
                color = ShashaColors.Secondary,
                modifier = Modifier.testTag("home_sync_failures"),
            )
        }
    }
}

/** Above the feed while its sync is failing: the unified error in brief (SPEC §1), the feed stays usable. */
@Composable
internal fun SyncErrorBanner(error: SourceException, extractorVersion: String, onCheckUpdates: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    val edge = if (error.kind == SourceErrorKind.NETWORK) ShashaColors.Outline else ShashaColors.Error.copy(alpha = 0.45f)
    Row(
        Modifier
            .fillMaxWidth()
            .background(ShashaColors.Surface, shape)
            .border(1.dp, edge, shape)
            .padding(horizontal = 24.dp, vertical = 16.dp)
            .testTag("home_sync_error"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ErrorIllustration(error.kind, Modifier.size(width = 52.dp, height = 56.dp))
        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(ErrorTexts.title(error.kind)), style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth())
            Text(
                stringResource(syncExplanation(error.kind)),
                style = MaterialTheme.typography.bodyMedium,
                color = ShashaColors.Muted,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Fact(stringResource(R.string.error_detail_name), ErrorTexts.technical(error.kind, error.technicalName))
                Spacer(Modifier.width(18.dp))
                Fact(stringResource(R.string.error_detail_extractor), extractorVersion)
            }
        }
        Spacer(Modifier.width(20.dp))
        CheckUpdatesButton(onCheckUpdates)
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Text("$label: ", style = MaterialTheme.typography.labelMedium, color = ShashaColors.Muted)
    // Technical values are Latin: always left-to-right.
    Text(
        value,
        style = MaterialTheme.typography.labelMedium.copy(textDirection = TextDirection.Ltr),
        color = ShashaColors.Text,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** No videos, and the sync cannot bring any: the unified error, with «تحديث» to try again. */
@Composable
internal fun SyncErrorPanel(
    error: SourceException,
    extractorVersion: String,
    focus: ContentFocus,
    onRefresh: () -> Unit,
    onCheckUpdates: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val refresh = remember { FocusRequester() }
    FocusArea(focus, refresh, modifier.fillMaxSize().testTag("home_sync_error")) {
        ErrorLayout(error, extractorVersion, explanation = stringResource(syncExplanation(error.kind))) {
            ShashaButton(
                stringResource(R.string.home_refresh),
                onRefresh,
                icon = ShashaIcons.Refresh,
                modifier = Modifier.focusRequester(refresh).testTag("home_refresh"),
            )
            CheckUpdatesButton(onCheckUpdates)
        }
    }
}

/** The kind's own explanation when it says what to do (no network, too many requests), else the feed's. */
@StringRes
private fun syncExplanation(kind: SourceErrorKind): Int = when (kind) {
    SourceErrorKind.NETWORK, SourceErrorKind.RATE_LIMITED -> ErrorTexts.explanation(kind)
    else -> R.string.home_sync_error_body
}
