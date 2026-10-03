package com.ahmadnayfeh.shasha.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ahmadnayfeh.shasha.R
import com.ahmadnayfeh.shasha.ui.screens.error.CheckUpdatesButton
import com.ahmadnayfeh.shasha.ui.screens.error.ErrorTexts
import com.ahmadnayfeh.shasha.ui.theme.LocalAccent
import com.ahmadnayfeh.shasha.ui.theme.ShashaColors
import com.ahmadnayfeh.shasha.youtube.SourceException
import com.ahmadnayfeh.shasha.ui.theme.AppTextDirection

/** Grid geometry shared by every browsing screen (4 columns of ~181 dp in the area beside the rail). */
object GridDefaults {
    val MinCellWidth = 176.dp

    /** A child's grid: three bigger cards per row, easier to aim at and to read from the sofa. */
    val KidsCellWidth = 224.dp
    val ColumnGap = 20.dp
    val RowGap = 30.dp

    /** Room for the focused card's zoom and glow; the far side keeps the overscan margin. */
    val ContentPadding = PaddingValues(start = 24.dp, end = 48.dp, top = 44.dp, bottom = 48.dp)
}

/**
 * The standard browsing grid. Focus comes back to the card that had it when the user returns from
 * the player or the rail ([focusRestorer]: the card is remembered with the screen's saved state);
 * the first time, it goes to [firstItem]. [focus] (when the grid is the whole screen) moves focus into it.
 */
@Composable
fun ShashaGrid(
    state: LazyGridState,
    focus: ContentFocus?,
    firstItem: FocusRequester,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = GridDefaults.ContentPadding,
    minCellWidth: Dp = GridDefaults.MinCellWidth,
    content: LazyGridScope.() -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minCellWidth),
        state = state,
        modifier = modifier.then(if (focus != null) Modifier.contentFocus(focus) else Modifier).focusRestorer(firstItem),
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(GridDefaults.ColumnGap),
        verticalArrangement = Arrangement.spacedBy(GridDefaults.RowGap),
        content = content,
    )
}

/** A full-width row of a grid (headers, footers). */
fun LazyGridScope.fullWidthItem(key: String, content: @Composable () -> Unit) =
    item(key = key, span = { GridItemSpan(maxLineSpan) }, contentType = key) { content() }

/** Title on the right, [actions] on the left (RTL). */
@Composable
fun ScreenHeader(
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineMedium, maxLines = 1)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = ShashaColors.Muted, maxLines = 1)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

/** A calm, centred explanation for an empty screen, with what to do next. */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
    details: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) {
    Column(modifier.widthIn(max = 640.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (busy) {
            ShashaLoader(size = 72.dp)
        } else {
            val accent = LocalAccent.current
            Box(
                Modifier
                    .size(88.dp)
                    .background(accent.tint(0.12f), CircleShape)
                    .border(1.dp, accent.tint(0.35f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = accent.bright, modifier = Modifier.size(42.dp))
            }
        }
        Spacer(Modifier.height(20.dp))
        // Full width (centred): mixed Arabic/Latin text laid out at its own measured width can wrap its last word.
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyLarge.copy(textDirection = AppTextDirection),
            color = ShashaColors.Muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        details()
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp), content = actions)
    }
}

/**
 * The end of a paged list: a quiet loader while the next page comes, or what went wrong. The list itself
 * stays, and nothing fails silently: a YouTube failure carries the unified error's title, its name, the
 * extractor version and «فحص التحديثات» (SPEC §1); no network or too many requests only need a retry.
 */
@Composable
fun PagingFooter(
    loading: Boolean,
    error: SourceException?,
    onRetry: () -> Unit,
    extractorVersion: String,
    onCheckUpdates: () -> Unit,
    modifier: Modifier = Modifier,
    /** What failed, when it is not "more of the list" (a section's first page). */
    @StringRes errorTitle: Int = R.string.paging_error,
) {
    Box(modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        when {
            error != null -> Column(
                Modifier
                    .background(ShashaColors.Surface, RoundedCornerShape(18.dp))
                    .border(1.dp, ShashaColors.Outline, RoundedCornerShape(18.dp))
                    .padding(horizontal = 20.dp, vertical = 12.dp)
                    .testTag("paging_error"),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val updateMayHelp = ErrorTexts.suggestsUpdate(error.kind)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(ShashaIcons.Info, contentDescription = null, tint = ShashaColors.Error, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            stringResource(if (updateMayHelp) ErrorTexts.title(error.kind) else errorTitle),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            "${ErrorTexts.technical(error.kind, error.technicalName)} · $extractorVersion",
                            style = MaterialTheme.typography.labelMedium.copy(textDirection = TextDirection.Ltr),
                            color = ShashaColors.Muted,
                            maxLines = 1,
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    ShashaButton(stringResource(R.string.action_retry), onRetry, icon = ShashaIcons.Refresh, compact = true)
                    if (updateMayHelp) CheckUpdatesButton(onCheckUpdates, compact = true)
                }
            }
            loading -> ShashaLoader(size = 40.dp)
        }
    }
}
