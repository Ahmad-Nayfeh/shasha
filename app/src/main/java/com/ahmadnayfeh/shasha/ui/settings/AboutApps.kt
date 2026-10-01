package com.ahmadnayfeh.shasha.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ahmadnayfeh.shasha.R
import com.ahmadnayfeh.shasha.ui.screens.settings.SectionLabel
import com.ahmadnayfeh.shasha.ui.settings.remote.QrCode
import com.ahmadnayfeh.shasha.ui.settings.remote.RemoteIcons
import com.ahmadnayfeh.shasha.ui.theme.LocalAccent
import com.ahmadnayfeh.shasha.ui.theme.ShashaColors

/** One link of an app: what it opens ([label]) and where. */
@Immutable
internal data class AppLink(@StringRes val label: Int, val url: String)

/** Another app by Shasha's developer: its name, one line about it (in the app's language) and its links. */
@Immutable
internal data class DeveloperApp(val key: String, @StringRes val name: Int, @StringRes val description: Int, val links: List<AppLink>)

internal val DeveloperApps: List<DeveloperApp> = listOf(
    DeveloperApp(
        "shadra",
        R.string.app_shadra_name,
        R.string.app_shadra_desc,
        listOf(
            AppLink(R.string.about_qr_website, "https://shadhra.app/"),
            AppLink(R.string.about_qr_play, "https://play.google.com/store/apps/details?id=app.shadhra"),
        ),
    ),
    DeveloperApp("silah", R.string.app_silah_name, R.string.app_silah_desc, listOf(AppLink(R.string.about_qr_github, "https://github.com/Ahmad-Nayfeh/silah"))),
    DeveloperApp("masarif", R.string.app_masarif_name, R.string.app_masarif_desc, listOf(AppLink(R.string.about_qr_github, "https://github.com/Ahmad-Nayfeh/masarif"))),
    DeveloperApp("khatwa", R.string.app_khatwa_name, R.string.app_khatwa_desc, listOf(AppLink(R.string.about_qr_github, "https://github.com/Ahmad-Nayfeh/khatwa"))),
)

/** «حول»'s first card: who made Shasha, and for whom. */
@Composable
internal fun DeveloperCard(modifier: Modifier = Modifier) {
    val accent = LocalAccent.current
    ReadingCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(52.dp)
                    .background(accent.tint(0.14f), CircleShape)
                    .border(1.dp, accent.tint(0.35f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(AppearanceIcons.Favorite, contentDescription = null, tint = accent.bright, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.about_dev_title), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.about_dev_subtitle), style = MaterialTheme.typography.bodyMedium, color = ShashaColors.Muted)
            }
        }
    }
}

/** «تطبيقات أخرى من المطوّر»: a card per app, each with the QR codes a phone scans to open its pages. */
@Composable
internal fun MoreApps() {
    SectionLabel(stringResource(R.string.about_more_apps), icon = AppearanceIcons.Apps)
    InfoLine(RemoteIcons.QrCode, stringResource(R.string.about_more_apps_hint))
    DeveloperApps.forEach { AppCard(it) }
}

/** The app's name and line at the start, its QR codes at the end. Focusable to be read (OK does nothing). */
@Composable
private fun AppCard(app: DeveloperApp) {
    ReadingCard(Modifier.paneItem("about_app_${app.key}")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(app.name), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(stringResource(app.description), style = MaterialTheme.typography.bodySmall, color = ShashaColors.Muted)
            }
            Spacer(Modifier.width(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                app.links.forEach { QrBlock(it, Modifier.testTag("about_qr_${app.key}_${app.links.indexOf(it)}")) }
            }
        }
    }
}

/**
 * A link's QR code: black on white whatever the theme (a phone's camera reads it best), with a quiet zone of at least
 * 4 cells (the matrix brings 2, the white margin the rest), and what it opens under it.
 */
@Composable
private fun QrBlock(link: AppLink, modifier: Modifier = Modifier, size: Dp = QrSize) {
    val label = stringResource(link.label)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(size)
                .background(Color.White, RoundedCornerShape(12.dp))
                .border(1.dp, ShashaColors.Outline, RoundedCornerShape(12.dp))
                .padding(size * QR_MARGIN),
        ) {
            QrCode(link.url, Modifier.fillMaxSize(), description = stringResource(R.string.about_qr_description, label))
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = ShashaColors.Muted, maxLines = 1)
    }
}

private val QrSize = 116.dp

/** The white margin around the matrix: with its own 2 cells, at least 4 cells for every link here (≤ 37 cells). */
private const val QR_MARGIN = 0.07f
