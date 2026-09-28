package com.ahmadnayfeh.shasha.ui.screens.updates

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ahmadnayfeh.shasha.R
import com.ahmadnayfeh.shasha.ui.components.ShashaButton
import com.ahmadnayfeh.shasha.ui.components.ShashaIcons
import com.ahmadnayfeh.shasha.ui.theme.ShashaColors
import com.ahmadnayfeh.shasha.ui.theme.ShashaDimens

/** Where "فحص التحديثات" leads until the GitHub Releases updater exists (SPEC phase 7). */
@Composable
fun UpdatesPlaceholderScreen(appVersion: String, extractorVersion: String, onBack: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Box(Modifier.fillMaxSize().padding(ShashaDimens.ScreenPadding), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 640.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Image(painterResource(R.drawable.splash_lockup), contentDescription = null, modifier = Modifier.height(56.dp))
            Spacer(Modifier.height(32.dp))
            Text(stringResource(R.string.updates_title), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.updates_body),
                style = MaterialTheme.typography.bodyLarge,
                color = ShashaColors.Muted,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.updates_current_version, appVersion, extractorVersion),
                style = MaterialTheme.typography.labelLarge,
                color = ShashaColors.Secondary,
            )
            Spacer(Modifier.height(32.dp))
            ShashaButton(
                text = stringResource(R.string.action_back),
                onClick = onBack,
                icon = ShashaIcons.Back,
                modifier = Modifier.focusRequester(focus),
            )
        }
    }
}
