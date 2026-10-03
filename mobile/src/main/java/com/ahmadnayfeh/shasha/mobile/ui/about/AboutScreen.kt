package com.ahmadnayfeh.shasha.mobile.ui.about

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ahmadnayfeh.shasha.mobile.BuildConfig
import com.ahmadnayfeh.shasha.mobile.R
import com.ahmadnayfeh.shasha.mobile.ui.components.SubScreen

/**
 * About: who made the app (and why), the developer's other apps, and the licences, with SponsorBlock's attribution (its
 * skip data is licensed CC BY-NC-SA 4.0 and must be credited as the project asks).
 */
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val uris = LocalUriHandler.current
    SubScreen(title = stringResource(R.string.about_title), onBack = onBack) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            // The app.
            Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Image(
                    painterResource(R.drawable.ic_launcher_foreground),
                    contentDescription = null,
                    modifier = Modifier.size(96.dp).clip(RoundedCornerShape(24.dp)).background(Color(0xFF0B0F14)),
                )
                Text(stringResource(R.string.app_name), Modifier.padding(top = 12.dp), style = MaterialTheme.typography.headlineSmall)
                Text(
                    stringResource(R.string.about_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toString()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // The developer.
            Card(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.about_developer_caption), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.about_developer_name), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 4.dp))
                    Text(
                        stringResource(R.string.about_made_with_love),
                        Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            // The developer's other apps.
            SectionTitle(stringResource(R.string.about_more_apps))
            for (app in OtherApps.all) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(app.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (link in app.links) {
                            OutlinedButton(onClick = { uris.openUri(link.url) }) { Text(stringResource(link.label)) }
                        }
                    }
                }
            }

            // SponsorBlock's attribution, exactly as the project asks for it.
            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            SectionTitle(stringResource(R.string.about_sponsorblock_title))
            Text(
                stringResource(R.string.sponsorblock_attribution_exact),
                Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                stringResource(R.string.sponsorblock_attribution),
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { uris.openUri(AboutLinks.SPONSORBLOCK) }, Modifier.padding(horizontal = 8.dp)) { Text("sponsor.ajay.app") }

            // The licences.
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionTitle(stringResource(R.string.about_licenses))
            Text(
                stringResource(R.string.about_licenses_intro),
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            for (entry in Licenses.libraries) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(entry.name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        Text(entry.license, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                    Text(stringResource(entry.role), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}
